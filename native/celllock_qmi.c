/* SPDX-License-Identifier: GPL-3.0-only
 *
 * CellLock 基带命令行：通过 QRTR 上的 QMI NAS 服务锁小区、锁频段、锁制式。
 * 协议编码核对自 qcom-band-menu 与 BunnyLock，见 SOURCES.md。
 *
 * 每次调用：严格绑定指定 SIM → 读取当前状态 → 写入 → 再次读取并比对。
 * stdout 输出一行 JSON，ok=true 仅表示写入后回读与目标一致。
 */
#ifdef CELLLOCK_PROTOCOL_TEST
#include "test_transport.inc"
#else
#include "qrtr_transport.inc"
#endif
#include <signal.h>
#include <stdlib.h>

#define NAS_SERVICE 3
#define DMS_SERVICE 2

#define NAS_SET_SYS_SEL  0x0033
#define NAS_GET_SYS_SEL  0x0034
#define NAS_BIND         0x0045
#define NAS_LTE_CELL_GET 0x00d7
#define NAS_LTE_CELL_SET 0x00d8
#define NAS_NR_CELL_SET  0x010e
#define NAS_NR_CELL_GET  0x010f
#define DMS_GET_BANDS    0x0045

/* system selection preference：写入与读取使用不同的 TLV 编号 */
#define SET_DURATION    0x17
#define SET_MODE        0x11
#define SET_LEGACY      0x12
#define SET_LTE         0x15
#define SET_LTE_EXT     0x24
#define SET_NR_COMBINED 0x2b
#define SET_NR_MODE     0x2e
#define SET_NR_SA       0x2f
#define SET_NR_NSA      0x30
#define GET_MODE        0x11
#define GET_LEGACY      0x12
#define GET_LTE         0x15
#define GET_LTE_EXT     0x23
#define GET_NR_MODE     0x2b
#define GET_NR_SA       0x2c
#define GET_NR_NSA      0x2d

#define RAT_GSM   0x04
#define RAT_WCDMA 0x08
#define RAT_LTE   0x10
#define RAT_NR    0x40

#define NR_LOCK_PCI   0
#define NR_LOCK_ARFCN 1
#define NR_LOCK_NONE  2

#define MAX_CELLS 64
#define MAX_ARFCNS 16

typedef struct { int n, result, error, transport; uint8_t bytes[QMI_MAX]; } Reply;

typedef struct {
    int known, count, result, error, transport;
    unsigned pci[MAX_CELLS], earfcn[MAX_CELLS];
} LteCell;

typedef struct {
    int known, ack_only, type, pci, scs, band, result, error, transport;
    long arfcn;
    int arfcn_count;
    unsigned arfcns[MAX_ARFCNS], arfcn_scs[MAX_ARFCNS];
    uint8_t bands[64];
} NrCell;

typedef struct {
    int known, result, error, transport;
    int rat, nr_mode, has_legacy, has_lte, has_sa, has_nsa;
    uint64_t legacy;
    uint8_t lte[32], sa[64], nsa[64];
} Pref;

typedef struct {
    int known;
    uint64_t legacy;
    uint8_t lte[32], nr[64];
} Hardware;

typedef struct { LteCell lte; NrCell nr; Pref pref; } State;

static const int SCS_KHZ[] = {15, 30, 60, 120, 240};
static const unsigned GSM_BANDS[] = {850, 900, 1800, 1900};

static Reply reply;
static uint16_t txn;
static volatile sig_atomic_t nas_fd = -1;
static volatile sig_atomic_t mutated;
static int writes_sent, writes_accepted, last_result = -1, last_error = -1;

static void on_signal(int sig) {
    const char *text = mutated
        ? "{\"ok\":false,\"stage\":\"interrupted\",\"message\":\"操作中断，命令可能已生效，请重新读取状态\",\"mutated\":true}\n"
        : "{\"ok\":false,\"stage\":\"interrupted\",\"message\":\"操作中断，未写入基带\",\"mutated\":false}\n";
    if (nas_fd >= 0) close(nas_fd);
    write(STDOUT_FILENO, text, strlen(text));
    _exit(sig == SIGALRM ? 124 : 130);
}

/* ---------- 字节与位掩码 ---------- */

static uint32_t le32(const uint8_t *p) {
    return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}
static uint64_t le64(const uint8_t *p) { return (uint64_t)le32(p) | (uint64_t)le32(p + 4) << 32; }
static void put16(uint8_t *p, unsigned v) { p[0] = v; p[1] = v >> 8; }
static void put32(uint8_t *p, unsigned v) { for (int i = 0; i < 4; ++i) p[i] = v >> (i * 8); }
static void put64(uint8_t *p, uint64_t v) { for (int i = 0; i < 8; ++i) p[i] = v >> (i * 8); }

static void bit_set(uint8_t *m, unsigned band) { m[(band - 1) / 8] |= 1u << ((band - 1) % 8); }
static int bit_has(const uint8_t *m, unsigned band) { return (m[(band - 1) / 8] >> ((band - 1) % 8)) & 1; }
static int mask_empty(const uint8_t *m, int bytes) {
    for (int i = 0; i < bytes; ++i) if (m[i]) return 0;
    return 1;
}
static int mask_disjoint(const uint8_t *a, const uint8_t *b, int bytes) {
    for (int i = 0; i < bytes; ++i) if (a[i] & b[i]) return 0;
    return 1;
}
static int mask_has_above(const uint8_t *m, int bytes, unsigned band) {
    for (unsigned b = band + 1; b <= (unsigned)bytes * 8; ++b) if (bit_has(m, b)) return 1;
    return 0;
}
/* 只比较本机硬件支持的频段，基带会忽略其余位 */
static int mask_equal_within(const uint8_t *a, const uint8_t *b, const uint8_t *hw, int bytes) {
    for (int i = 0; i < bytes; ++i) if ((a[i] ^ b[i]) & (hw ? hw[i] : 0xff)) return 0;
    return 1;
}

/* GSM / WCDMA 共用 64 位 legacy 掩码 */
static uint64_t gsm_bits(unsigned band) {
    switch (band) {
        case 850: return 1ull << 19;
        case 900: return (1ull << 8) | (1ull << 9);
        case 1800: return 1ull << 7;
        case 1900: return 1ull << 21;
        default: return 0;
    }
}
static uint64_t wcdma_bits(unsigned band) {
    static const int bit[] = {0, 22, 23, 24, 25, 26, 27, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60};
    return band >= 1 && band <= 19 ? 1ull << bit[band] : 0;
}
static uint64_t all_gsm_bits(void) {
    uint64_t m = 0;
    for (unsigned i = 0; i < 4; ++i) m |= gsm_bits(GSM_BANDS[i]);
    return m;
}
static uint64_t all_wcdma_bits(void) {
    uint64_t m = 0;
    for (unsigned b = 1; b <= 19; ++b) m |= wcdma_bits(b);
    return m;
}

/* ---------- QMI 收发 ---------- */

static int valid_frame(const Reply *r) {
    if (r->n < QMI_HEADER || le16(r->bytes + 5) != r->n - QMI_HEADER) return 0;
    for (int at = QMI_HEADER; at < r->n;) {
        if (at + 3 > r->n) return 0;
        at += 3 + le16(r->bytes + at + 1);
        if (at > r->n) return 0;
    }
    return 1;
}

/* 重复出现的 TLV 视为异常回包 */
static const uint8_t *tlv(const Reply *r, unsigned id, int *length) {
    const uint8_t *found = NULL;
    *length = 0;
    if (!valid_frame(r)) return NULL;
    for (int at = QMI_HEADER; at < r->n;) {
        int count = le16(r->bytes + at + 1);
        if (r->bytes[at] == id) {
            if (found) return NULL;
            found = r->bytes + at + 3;
            *length = count;
        }
        at += 3 + count;
    }
    return found;
}

static int has_tlv(const Reply *r, unsigned id) {
    if (!valid_frame(r)) return 0;
    for (int at = QMI_HEADER; at < r->n; at += 3 + le16(r->bytes + at + 1))
        if (r->bytes[at] == id) return 1;
    return 0;
}

static int allowed(unsigned service, unsigned id) {
    if (service == DMS_SERVICE) return id == DMS_GET_BANDS;
    return service == NAS_SERVICE && (id == NAS_BIND || id == NAS_SET_SYS_SEL || id == NAS_GET_SYS_SEL ||
           id == NAS_LTE_CELL_GET || id == NAS_LTE_CELL_SET ||
           id == NAS_NR_CELL_GET || id == NAS_NR_CELL_SET);
}

static void request_on(int fd, unsigned service, unsigned id, const uint8_t *body, int length, Reply *r) {
    r->n = 0;
    r->result = r->error = -1;
    r->transport = 0;
    if (!allowed(service, id) || length < 0 || length > 512 || (length && !body)) {
        r->transport = EINVAL; return;
    }
    if (++txn == 0) ++txn;
    r->bytes[0] = 0;
    put16(r->bytes + 1, txn);
    put16(r->bytes + 3, id);
    put16(r->bytes + 5, length);
    if (length) memcpy(r->bytes + QMI_HEADER, body, length);
    const char *failed = NULL;
    r->n = (int)exchange(fd, r->bytes, QMI_HEADER + length, 1800, &failed);
    if (r->n < 0) { r->transport = -r->n; r->n = 0; return; }
    int len;
    const uint8_t *result = tlv(r, 0x02, &len);
    if (result && len == 4) {
        r->result = le16(result);
        r->error = le16(result + 2);
    } else {
        r->transport = EPROTO;
    }
}

static int request(unsigned id, const uint8_t *body, int length, Reply *r) {
    request_on(nas_fd, NAS_SERVICE, id, body, length, r);
    return !r->transport && r->result == 0 && r->error == 0;
}

static int write_request(unsigned id, const uint8_t *body, int length) {
    mutated = 1;
    ++writes_sent;
    int ok = request(id, body, length, &reply);
    last_result = reply.result;
    last_error = reply.transport ? reply.transport : reply.error;
    if (ok) ++writes_accepted;
    return ok;
}

static int add_tlv(uint8_t *p, int at, uint8_t id, const void *value, int length) {
    p[at] = id;
    put16(p + at + 1, length);
    memcpy(p + at + 3, value, length);
    return at + 3 + length;
}

/* 所有 system selection 写入都附带“永久生效”时长，并且只改一个字段 */
static int set_preference(uint8_t id, const void *value, int length) {
    uint8_t body[96];
    const uint8_t permanent = 1;
    int at = add_tlv(body, 0, SET_DURATION, &permanent, 1);
    at = add_tlv(body, at, id, value, length);
    return write_request(NAS_SET_SYS_SEL, body, at);
}

/* ---------- 读取 ---------- */

static void read_lte_cell(LteCell *s) {
    memset(s, 0, sizeof *s);
    request(NAS_LTE_CELL_GET, NULL, 0, &reply);
    s->result = reply.result; s->error = reply.error; s->transport = reply.transport;
    if (reply.transport || reply.result != 0 || reply.error != 0) return;
    int len;
    const uint8_t *v = tlv(&reply, 0x10, &len);
    if (!v || len < 1 || v[0] > MAX_CELLS || len != 1 + 4 * v[0]) return;
    for (unsigned i = 0; i < v[0]; ++i) {
        s->pci[i] = le16(v + 1 + i * 4);
        s->earfcn[i] = le16(v + 3 + i * 4);
        if (s->pci[i] > 503) return;
    }
    s->count = v[0];
    s->known = 1;
}

/* 成功但只带结果 TLV 的 NR 回包：状态未知，不代表未锁定 */
static int ack_only(const Reply *r) {
    return !r->transport && r->result == 0 && r->error == 0 && valid_frame(r) &&
           r->n == QMI_HEADER + 7 && r->bytes[0] == QMI_RESPONSE &&
           le16(r->bytes + 3) == NAS_NR_CELL_GET && r->bytes[7] == 2 &&
           le16(r->bytes + 8) == 4 && le32(r->bytes + 10) == 0;
}

static void read_nr_cell(NrCell *s) {
    memset(s, 0, sizeof *s);
    s->type = s->pci = -1;
    s->arfcn = -1;
    request(NAS_NR_CELL_GET, NULL, 0, &reply);
    s->result = reply.result; s->error = reply.error; s->transport = reply.transport;
    if (reply.transport || reply.result != 0 || reply.error != 0) return;
    s->ack_only = ack_only(&reply);
    int len;
    const uint8_t *v = tlv(&reply, 0x10, &len);
    if (!v || len != 4 || le32(v) > 4) return;
    s->type = (int)le32(v);
    if (s->type == NR_LOCK_PCI) {
        v = tlv(&reply, 0x11, &len);
        if (!v || len != 74 || le16(v) > 1007 || le32(v + 2) > 4 || le32(v + 6) > 3279165) return;
        s->pci = le16(v);
        s->scs = SCS_KHZ[le32(v + 2)];
        s->arfcn = le32(v + 6);
        memcpy(s->bands, v + 10, 64);
        for (unsigned b = 1; b <= 512 && !s->band; ++b) if (bit_has(s->bands, b)) s->band = (int)b;
        if (!s->band) return;
    } else if (s->type == NR_LOCK_ARFCN) {
        v = tlv(&reply, 0x12, &len);
        if (!v || len < 1 || v[0] > MAX_ARFCNS || len != 1 + 8 * v[0]) return;
        for (unsigned i = 0; i < v[0]; ++i) {
            s->arfcns[i] = le32(v + 1 + i * 8);
            unsigned scs = le32(v + 5 + i * 8);
            if (scs > 4 || s->arfcns[i] > 3279165) return;
            s->arfcn_scs[i] = SCS_KHZ[scs];
        }
        s->arfcn_count = v[0];
    }
    s->known = 1;
}

static void read_pref(Pref *s) {
    memset(s, 0, sizeof *s);
    s->rat = s->nr_mode = -1;
    request(NAS_GET_SYS_SEL, NULL, 0, &reply);
    s->result = reply.result; s->error = reply.error; s->transport = reply.transport;
    if (reply.transport || reply.result != 0 || reply.error != 0) return;
    int len;
    const uint8_t *v;
    if ((v = tlv(&reply, GET_MODE, &len)) && len == 2) s->rat = le16(v);
    if ((v = tlv(&reply, GET_LEGACY, &len)) && len == 8) { s->legacy = le64(v); s->has_legacy = 1; }
    if ((v = tlv(&reply, GET_LTE_EXT, &len)) && len == 32) { memcpy(s->lte, v, 32); s->has_lte = 1; }
    else if (!has_tlv(&reply, GET_LTE_EXT) && (v = tlv(&reply, GET_LTE, &len)) && len == 8) {
        memcpy(s->lte, v, 8); s->has_lte = 1;
    }
    if ((v = tlv(&reply, GET_NR_MODE, &len)) && len == 4 && le32(v) <= 2) s->nr_mode = (int)le32(v);
    if ((v = tlv(&reply, GET_NR_SA, &len)) && len == 64) { memcpy(s->sa, v, 64); s->has_sa = 1; }
    if ((v = tlv(&reply, GET_NR_NSA, &len)) && len == 64) { memcpy(s->nsa, v, 64); s->has_nsa = 1; }
    s->known = s->rat >= 0;
}

/* 可选字段缺失可以继续；存在但重复、截断或越界则不把部分列表当完整硬件能力。 */
static void parse_hardware(const Reply *r, Hardware *hw) {
    Hardware parsed = {0};
    memset(hw, 0, sizeof *hw);
    if (r->transport || r->result != 0 || r->error != 0 || !valid_frame(r)) return;
    int len;
    const uint8_t *v;
    if (has_tlv(r, 0x01)) {
        v = tlv(r, 0x01, &len);
        if (!v || len != 8) return;
        parsed.legacy = le64(v);
    }
    if (has_tlv(r, 0x10)) {
        v = tlv(r, 0x10, &len);
        if (!v || len != 8) return;
        memcpy(parsed.lte, v, 8);
    }
    for (unsigned id = 0x12; id <= 0x13; ++id) {
        if (!has_tlv(r, id)) continue;
        v = tlv(r, id, &len);
        if (!v || len < 2 || len != 2 + 2 * le16(v)) return;
        for (unsigned i = 0; i < le16(v); ++i) {
            unsigned b = le16(v + 2 + 2 * i);
            if (!b || b > (id == 0x12 ? 256u : 512u)) return;
            bit_set(id == 0x12 ? parsed.lte : parsed.nr, b);
        }
    }
    parsed.known = !mask_empty(parsed.lte, 32) || !mask_empty(parsed.nr, 64) || parsed.legacy;
    *hw = parsed;
}

/* DMS 报告本机射频支持的频段，与 SIM 无关 */
static void read_hardware(Hardware *hw) {
    memset(hw, 0, sizeof *hw);
    struct sockaddr_qrtr server;
    if (lookup(DMS_SERVICE, 1, &server) && lookup(DMS_SERVICE, 0, &server)) return;
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return;
    if (connect(fd, (struct sockaddr *)&server, sizeof server) == 0) {
        request_on(fd, DMS_SERVICE, DMS_GET_BANDS, NULL, 0, &reply);
        parse_hardware(&reply, hw);
    }
    close(fd);
}

static void read_state(State *s) {
    read_lte_cell(&s->lte);
    read_nr_cell(&s->nr);
    read_pref(&s->pref);
}

/* ---------- JSON 输出 ---------- */

static void print_mask(const char *name, const uint8_t *m, unsigned max, int known) {
    printf("\"%s\":", name);
    if (!known) { fputs("null", stdout); return; }
    putchar('[');
    for (unsigned b = 1, first = 1; b <= max; ++b) {
        if (!bit_has(m, b)) continue;
        printf("%s%u", first ? "" : ",", b);
        first = 0;
    }
    putchar(']');
}

static void print_legacy(const char *gsm_name, const char *wcdma_name, uint64_t m, int known) {
    printf("\"%s\":", gsm_name);
    if (!known) fputs("null", stdout);
    else {
        putchar('[');
        for (unsigned i = 0, first = 1; i < 4; ++i) {
            if (!(m & gsm_bits(GSM_BANDS[i]))) continue;
            printf("%s%u", first ? "" : ",", GSM_BANDS[i]);
            first = 0;
        }
        putchar(']');
    }
    printf(",\"%s\":", wcdma_name);
    if (!known) fputs("null", stdout);
    else {
        putchar('[');
        for (unsigned b = 1, first = 1; b <= 19; ++b) {
            if (!(m & wcdma_bits(b))) continue;
            printf("%s%u", first ? "" : ",", b);
            first = 0;
        }
        putchar(']');
    }
}

static void print_lte_cell(const LteCell *s) {
    printf("{\"known\":%s,\"cells\":[", s->known ? "true" : "false");
    for (int i = 0; s->known && i < s->count; ++i)
        printf("%s{\"earfcn\":%u,\"pci\":%u}", i ? "," : "", s->earfcn[i], s->pci[i]);
    printf("],\"error\":%d}", s->transport ? -s->transport : s->error);
}

static void print_nr_cell(const NrCell *s) {
    printf("{\"known\":%s,\"ackOnly\":%s,\"type\":%d", s->known ? "true" : "false",
           s->ack_only ? "true" : "false", s->type);
    if (s->type == NR_LOCK_PCI && s->known)
        printf(",\"arfcn\":%ld,\"pci\":%d,\"band\":%d,\"scs\":%d", s->arfcn, s->pci, s->band, s->scs);
    if (s->type == NR_LOCK_ARFCN && s->known) {
        fputs(",\"arfcns\":[", stdout);
        for (int i = 0; i < s->arfcn_count; ++i)
            printf("%s{\"arfcn\":%u,\"scs\":%u}", i ? "," : "", s->arfcns[i], s->arfcn_scs[i]);
        putchar(']');
    }
    printf(",\"error\":%d}", s->transport ? -s->transport : s->error);
}

static void print_pref(const Pref *s) {
    printf("{\"known\":%s,\"rat\":%d,\"nrMode\":%d,", s->known ? "true" : "false", s->rat, s->nr_mode);
    print_mask("lte", s->lte, 256, s->has_lte); putchar(',');
    print_mask("nrSa", s->sa, 512, s->has_sa); putchar(',');
    print_mask("nrNsa", s->nsa, 512, s->has_nsa); putchar(',');
    print_legacy("gsm", "wcdma", s->legacy, s->has_legacy);
    fputs(",\"legacyMask\":", stdout);
    if (s->has_legacy) printf("\"%016llx\"", (unsigned long long)s->legacy);
    else fputs("null", stdout);
    printf(",\"error\":%d}", s->transport ? -s->transport : s->error);
}

static void print_hardware(const Hardware *hw) {
    printf("{\"known\":%s,", hw->known ? "true" : "false");
    print_mask("lte", hw->lte, 256, hw->known); putchar(',');
    print_mask("nr", hw->nr, 512, hw->known); putchar(',');
    print_legacy("gsm", "wcdma", hw->legacy, hw->known);
    putchar('}');
}

/* ---------- 参数 ---------- */

static int number(const char *s, unsigned limit, unsigned *v) {
    if (!s || !*s) return 0;
    unsigned n = 0;
    for (; *s; ++s) {
        if (*s < '0' || *s > '9' || n > limit / 10) return 0;
        n = n * 10 + (unsigned)(*s - '0');
        if (n > limit) return 0;
    }
    *v = n;
    return 1;
}

/* "1,3,41" → 位掩码；返回频段个数，格式错误返回 -1 */
static int band_list(const char *arg, unsigned max, uint8_t *mask) {
    char buf[1024];
    if (!arg || !*arg || strlen(arg) >= sizeof buf || arg[0] == ',' ||
        arg[strlen(arg) - 1] == ',' || strstr(arg, ",,")) return -1;
    strcpy(buf, arg);
    int count = 0;
    for (char *save = NULL, *tok = strtok_r(buf, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        unsigned b;
        if (!number(tok, max, &b) || b == 0) return -1;
        bit_set(mask, b);
        ++count;
    }
    return count;
}

static int scs_index(unsigned khz) {
    for (int i = 0; i < 5; ++i) if (SCS_KHZ[i] == (int)khz) return i;
    return -1;
}

/* ---------- 写入 ---------- */

static int lte_cell_set(int clear, unsigned earfcn, unsigned pci) {
    uint8_t p[32];
    p[0] = 1;
    put16(p + 1, clear ? 1 : 7);
    p[3] = clear ? 0 : 1;
    int at = 4;
    if (!clear) { put16(p + at, pci); put32(p + at + 2, earfcn); at += 6; }
    p[at++] = 0x10; put16(p + at, 1); at += 2; p[at++] = 1;
    return write_request(NAS_LTE_CELL_SET, p, at);
}

static int nr_cell_set(int type, unsigned arfcn, unsigned pci, unsigned band, unsigned scs) {
    uint8_t p[96];
    p[0] = 1; put16(p + 1, 4); put32(p + 3, type);
    int at = 7;
    if (type == NR_LOCK_PCI) {
        p[at] = 0x10; put16(p + at + 1, 74);
        put16(p + at + 3, pci); put32(p + at + 5, scs); put32(p + at + 9, arfcn);
        memset(p + at + 13, 0, 64);
        bit_set(p + at + 13, band);
        at += 77;
    } else if (type == NR_LOCK_ARFCN) {
        p[at] = 0x11; put16(p + at + 1, 9);
        p[at + 3] = 1; put32(p + at + 4, arfcn); put32(p + at + 8, scs);
        at += 12;
    }
    return write_request(NAS_NR_CELL_SET, p, at);
}

/* B1–B64 用旧字段最稳；有更高频段时只能用扩展字段（两者同时发会被拒） */
static int unsupported_field(void) {
    /* QMI INVALID_QMI_COMMAND / NOT_SUPPORTED；超时、内部错误不触发第二种写法。 */
    return !reply.transport && reply.result == 1 && (reply.error == 71 || reply.error == 94);
}

static int lte_bands_set(const uint8_t *mask, const Hardware *hw) {
    int high = mask_has_above(mask, 32, 64) || (hw->known && mask_has_above(hw->lte, 32, 64));
    if (high) {
        if (set_preference(SET_LTE_EXT, mask, 32)) return 1;
        if (!unsupported_field()) return 0;
    }
    if (mask_has_above(mask, 32, 64)) return 0;
    return set_preference(SET_LTE, mask, 8);
}

/* 优先分别写 SA / NSA；旧固件只认合并字段 */
static int nr_bands_set(int sa, int nsa, const uint8_t *mask) {
    int ok = 1;
    if (sa) ok = set_preference(SET_NR_SA, mask, 64);
    if (ok && nsa) ok = set_preference(SET_NR_NSA, mask, 64);
    if (!ok && sa && nsa && unsupported_field()) ok = set_preference(SET_NR_COMBINED, mask, 64);
    return ok;
}

static int legacy_set(uint64_t m) {
    uint8_t v[8];
    put64(v, m);
    return set_preference(SET_LEGACY, v, 8);
}

static int rat_set(unsigned mask) {
    uint8_t v[2];
    put16(v, mask);
    return set_preference(SET_MODE, v, 2);
}

static int nr_mode_set(unsigned mode) {
    uint8_t v[4] = {(uint8_t)mode, 0, 0, 0};
    return set_preference(SET_NR_MODE, v, 4);
}

static unsigned all_rats(const Hardware *hw) {
    if (!hw->known) return 0;
    unsigned m = 0;
    if (hw->legacy & all_gsm_bits()) m |= RAT_GSM;
    if (hw->legacy & all_wcdma_bits()) m |= RAT_WCDMA;
    if (!mask_empty(hw->lte, 32)) m |= RAT_LTE;
    if (!mask_empty(hw->nr, 64)) m |= RAT_NR;
    return m;
}

/* ---------- 校验 ---------- */

static int lte_cell_is(const LteCell *s, unsigned earfcn, unsigned pci) {
    return s->known && s->count == 1 && s->earfcn[0] == earfcn && s->pci[0] == pci;
}
static int lte_cell_clear(const LteCell *s) { return s->known && s->count == 0; }
static int nr_cell_clear(const NrCell *s) { return s->known && s->type == NR_LOCK_NONE; }

static int nr_can_write(const NrCell *s) {
    return s->known || s->ack_only || (!s->transport && s->result == 1 && s->error == 3);
}

static int nr_cell_is(const NrCell *s, unsigned arfcn, unsigned pci, unsigned band, unsigned scs) {
    uint8_t expected[64] = {0};
    bit_set(expected, band);
    return s->known && s->type == NR_LOCK_PCI && s->arfcn == (long)arfcn && s->pci == (int)pci &&
           s->scs == SCS_KHZ[scs] && memcmp(expected, s->bands, 64) == 0;
}

static int nr_freq_is(const NrCell *s, unsigned arfcn, unsigned scs) {
    return s->known && s->type == NR_LOCK_ARFCN && s->arfcn_count == 1 &&
           s->arfcns[0] == arfcn && s->arfcn_scs[0] == (unsigned)SCS_KHZ[scs];
}

/* ---------- 主流程 ---------- */

typedef enum {
    CMD_STATE, CMD_CELL_LTE, CMD_CELL_NR, CMD_FREQ_NR, CMD_CELL_CLEAR,
    CMD_BAND_LTE, CMD_BAND_NR, CMD_BAND_GSM, CMD_BAND_WCDMA, CMD_RAT, CMD_NR_MODE, CMD_RESET
} Command;

static const struct { const char *name; Command cmd; int argc; } COMMANDS[] = {
    {"state", CMD_STATE, 3},
    {"cell-lte", CMD_CELL_LTE, 5},
    {"cell-nr", CMD_CELL_NR, 7},
    {"freq-nr", CMD_FREQ_NR, 5},
    {"cell-clear", CMD_CELL_CLEAR, 4},
    {"band-lte", CMD_BAND_LTE, 4},
    {"band-nr", CMD_BAND_NR, 5},
    {"band-gsm", CMD_BAND_GSM, 4},
    {"band-wcdma", CMD_BAND_WCDMA, 4},
    {"rat", CMD_RAT, 4},
    {"nr-mode", CMD_NR_MODE, 4},
    {"reset", CMD_RESET, 3},
};

#define RAT_KNOWN (RAT_GSM | RAT_WCDMA | RAT_LTE | RAT_NR)

typedef struct {
    Command cmd;
    const char *name;
    unsigned slot, arfcn, pci, band, scs, rat, nr_mode;
    int lte, nr, sa, nsa, all;
    uint8_t mask[64];
    uint64_t legacy;
} Job;

static const char *stage = "arguments";
static char message[256] = "命令或参数无效";

static void report(const char *s, const char *m) {
    stage = s;
    snprintf(message, sizeof message, "%s", m);
}

static int parse_rats(const char *arg, unsigned *out) {
    char buf[64];
    if (!arg || !*arg || strlen(arg) >= sizeof buf || arg[0] == ',' ||
        arg[strlen(arg) - 1] == ',' || strstr(arg, ",,")) return 0;
    strcpy(buf, arg);
    unsigned m = 0;
    for (char *save = NULL, *tok = strtok_r(buf, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        if (!strcmp(tok, "gsm")) m |= RAT_GSM;
        else if (!strcmp(tok, "wcdma")) m |= RAT_WCDMA;
        else if (!strcmp(tok, "lte")) m |= RAT_LTE;
        else if (!strcmp(tok, "nr")) m |= RAT_NR;
        else return 0;
    }
    *out = m;
    return m != 0;
}

/* GSM 以 MHz 表示，WCDMA 以频段号表示 */
static int parse_legacy(const char *arg, int gsm, uint64_t *out) {
    char buf[128];
    if (!arg || !*arg || strlen(arg) >= sizeof buf || arg[0] == ',' ||
        arg[strlen(arg) - 1] == ',' || strstr(arg, ",,")) return 0;
    strcpy(buf, arg);
    uint64_t m = 0;
    for (char *save = NULL, *tok = strtok_r(buf, ",", &save); tok; tok = strtok_r(NULL, ",", &save)) {
        unsigned band;
        if (!number(tok, 1900, &band)) return 0;
        uint64_t bits = gsm ? gsm_bits(band) : wcdma_bits(band);
        if (!bits) return 0;
        m |= bits;
    }
    *out = m;
    return m != 0;
}

static int parse_job(int argc, char **argv, Job *job) {
    memset(job, 0, sizeof *job);
    int found = 0;
    for (unsigned i = 0; i < sizeof COMMANDS / sizeof *COMMANDS; ++i) {
        if (argc >= 2 && !strcmp(argv[1], COMMANDS[i].name) && argc == COMMANDS[i].argc) {
            job->cmd = COMMANDS[i].cmd;
            job->name = COMMANDS[i].name;
            found = 1;
        }
    }
    if (!found || !number(argv[2], 1, &job->slot)) return 0;

    unsigned khz = 0;
    int idx;
    switch (job->cmd) {
        case CMD_CELL_LTE:
            if (number(argv[3], 65535, &job->arfcn) && number(argv[4], 503, &job->pci)) return 1;
            report("arguments", "LTE 锁小区需要 EARFCN 0–65535 与 PCI 0–503");
            return 0;
        case CMD_CELL_NR:
            if (number(argv[3], 3279165, &job->arfcn) && number(argv[4], 1007, &job->pci) &&
                number(argv[5], 512, &job->band) && job->band && number(argv[6], 240, &khz) &&
                (idx = scs_index(khz)) >= 0) {
                job->scs = (unsigned)idx;
                return 1;
            }
            report("arguments", "NR 锁小区需要 ARFCN、PCI 0–1007、频段与子载波间隔");
            return 0;
        case CMD_FREQ_NR:
            if (number(argv[3], 3279165, &job->arfcn) && number(argv[4], 240, &khz) &&
                (idx = scs_index(khz)) >= 0) {
                job->scs = (unsigned)idx;
                return 1;
            }
            report("arguments", "NR 锁频点需要 ARFCN 与子载波间隔");
            return 0;
        case CMD_CELL_CLEAR:
            job->lte = !strcmp(argv[3], "lte") || !strcmp(argv[3], "all");
            job->nr = !strcmp(argv[3], "nr") || !strcmp(argv[3], "all");
            return job->lte || job->nr;
        case CMD_BAND_LTE:
            job->all = !strcmp(argv[3], "all");
            return job->all || band_list(argv[3], 256, job->mask) > 0;
        case CMD_BAND_NR:
            job->sa = !strcmp(argv[3], "sa") || !strcmp(argv[3], "both");
            job->nsa = !strcmp(argv[3], "nsa") || !strcmp(argv[3], "both");
            job->all = !strcmp(argv[4], "all");
            return (job->sa || job->nsa) && (job->all || band_list(argv[4], 512, job->mask) > 0);
        case CMD_BAND_GSM:
        case CMD_BAND_WCDMA:
            job->all = !strcmp(argv[3], "all");
            return job->all || parse_legacy(argv[3], job->cmd == CMD_BAND_GSM, &job->legacy);
        case CMD_RAT:
            job->all = !strcmp(argv[3], "all");
            return job->all || parse_rats(argv[3], &job->rat);
        case CMD_NR_MODE:
            if (!strcmp(argv[3], "both")) job->nr_mode = 0;
            else if (!strcmp(argv[3], "nsa")) job->nr_mode = 1;
            else if (!strcmp(argv[3], "sa")) job->nr_mode = 2;
            else return 0;
            return 1;
        default:
            return 1;
    }
}

static int needs_hardware(Command cmd) {
    return cmd == CMD_STATE || cmd == CMD_RESET || cmd == CMD_BAND_LTE || cmd == CMD_BAND_NR ||
           cmd == CMD_BAND_GSM || cmd == CMD_BAND_WCDMA || cmd == CMD_RAT;
}

/* "all" 展开为本机支持的全部频段。手选列表允许包含硬件不支持的位（用于原样
 * 写回初始配置），但至少要有一个本机支持的频段，否则锁定后必然无服务。 */
static int resolve_against_hardware(Job *job, const Hardware *hw) {
    if (job->cmd >= CMD_BAND_LTE && job->cmd <= CMD_RAT && !hw->known) {
        report("hardware", "未能读取本机支持的频段，未写入");
        return 0;
    }
    switch (job->cmd) {
        case CMD_BAND_LTE:
            if (job->all) {
                if (!hw->known || mask_empty(hw->lte, 32)) break;
                memcpy(job->mask, hw->lte, 32);
            } else if (hw->known && mask_disjoint(job->mask, hw->lte, 32)) {
                report("unsupported", "所选 LTE 频段本机都不支持，锁定后会没有信号");
                return 0;
            }
            return 1;
        case CMD_BAND_NR:
            if (job->all) {
                if (!hw->known || mask_empty(hw->nr, 64)) break;
                memcpy(job->mask, hw->nr, 64);
            } else if (hw->known && mask_disjoint(job->mask, hw->nr, 64)) {
                report("unsupported", "所选 NR 频段本机都不支持");
                return 0;
            }
            return 1;
        case CMD_BAND_GSM:
        case CMD_BAND_WCDMA: {
            uint64_t family = job->cmd == CMD_BAND_GSM ? all_gsm_bits() : all_wcdma_bits();
            if (job->all) {
                if (!hw->known || !(hw->legacy & family)) break;
                job->legacy = hw->legacy & family;
            } else if (hw->known && !(job->legacy & hw->legacy)) {
                report("unsupported", "所选频段本机都不支持");
                return 0;
            }
            return 1;
        }
        case CMD_RAT:
            if (job->all) job->rat = all_rats(hw);
            if (!(job->rat & all_rats(hw))) {
                report("unsupported", "所选制式本机都不支持");
                return 0;
            }
            return 1;
        default:
            return 1;
    }
    report("hardware", "未能读取该制式支持的频段，未写入");
    return 0;
}

static int connect_nas(unsigned slot) {
    struct sockaddr_qrtr server;
    int rc = lookup(NAS_SERVICE, 1, &server);
    if (rc) rc = lookup(NAS_SERVICE, 0, &server);
    if (rc) { report("lookup", "找不到基带 NAS 服务"); return 0; }
    nas_fd = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (nas_fd < 0) { report("connect", "无法创建 QRTR 连接"); return 0; }
    struct timeval tv = {.tv_sec = 2, .tv_usec = 0};
    setsockopt(nas_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(nas_fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    if (connect(nas_fd, (struct sockaddr *)&server, sizeof server) < 0) {
        report("connect", "无法连接基带 NAS 服务");
        return 0;
    }
    const uint8_t bind[] = {1, 1, 0, (uint8_t)slot};
    if (!request(NAS_BIND, bind, sizeof bind, &reply)) {
        report("bind", "无法绑定所选 SIM，未发送任何命令");
        return 0;
    }
    return 1;
}

/* system selection 的写入是异步生效的：写完立刻回读常常还是旧值 */
static int pref_matches(const Job *job, const Hardware *hw, const Pref *p, uint64_t legacy_target) {
    const uint8_t *hw_lte = hw->known ? hw->lte : NULL;
    const uint8_t *hw_nr = hw->known ? hw->nr : NULL;
    switch (job->cmd) {
        case CMD_BAND_LTE:
            return p->has_lte && mask_equal_within(p->lte, job->mask, hw_lte, 32);
        case CMD_BAND_NR:
            return (!job->sa || (p->has_sa && mask_equal_within(p->sa, job->mask, hw_nr, 64))) &&
                   (!job->nsa || (p->has_nsa && mask_equal_within(p->nsa, job->mask, hw_nr, 64)));
        case CMD_BAND_GSM:
        case CMD_BAND_WCDMA: {
            uint64_t family = job->cmd == CMD_BAND_GSM ? all_gsm_bits() : all_wcdma_bits();
            uint64_t within = family & (hw->known ? hw->legacy : ~0ull);
            return p->has_legacy && ((p->legacy ^ legacy_target) & (within | ~family)) == 0;
        }
        case CMD_RAT:
            return p->rat >= 0 && ((unsigned)p->rat & RAT_KNOWN) == (job->rat & RAT_KNOWN);
        case CMD_NR_MODE:
            return p->nr_mode == (int)job->nr_mode;
        default:
            return 0;
    }
}

static int await_pref(const Job *job, const Hardware *hw, Pref *p, uint64_t legacy_target) {
    int64_t deadline = now_ms() + 4200;
    do {
        read_pref(p);
        if (pref_matches(job, hw, p, legacy_target)) return 1;
        if (now_ms() >= deadline) break;
        usleep(300 * 1000);
    } while (now_ms() < deadline);
    return 0;
}

/* 1 = 写入并回读一致；0 = 失败；-1 = 已设置好 stage/message 的部分失败 */
static int run_job(const Job *job, const Hardware *hw, State *s) {
    int sent = 1;
    uint64_t legacy_target = 0;

    switch (job->cmd) {
        case CMD_CELL_LTE:
            if (!s->lte.known) { report("state", "LTE 小区锁状态未知，未写入"); return -1; }
            sent = lte_cell_set(0, job->arfcn, job->pci);
            read_state(s);
            return sent && lte_cell_is(&s->lte, job->arfcn, job->pci);

        case CMD_CELL_NR:
            if (!nr_can_write(&s->nr)) { report("state", "NR 小区锁查询异常，未写入"); return -1; }
            sent = nr_cell_set(NR_LOCK_PCI, job->arfcn, job->pci, job->band, job->scs);
            read_state(s);
            return sent && nr_cell_is(&s->nr, job->arfcn, job->pci, job->band, job->scs);

        case CMD_FREQ_NR:
            if (!nr_can_write(&s->nr)) { report("state", "NR 小区锁查询异常，未写入"); return -1; }
            sent = nr_cell_set(NR_LOCK_ARFCN, job->arfcn, 0, 0, job->scs);
            read_state(s);
            return sent && nr_freq_is(&s->nr, job->arfcn, job->scs);

        case CMD_CELL_CLEAR:
            if ((job->lte && !s->lte.known) || (job->nr && !nr_can_write(&s->nr))) {
                report("state", "所选小区锁查询异常，未写入"); return -1;
            }
            /* 部分固件在无锁时拒绝 NR 清除，已确认无锁就不再写 */
            if (job->lte && !lte_cell_clear(&s->lte)) sent = lte_cell_set(1, 0, 0);
            if (sent && job->nr && !nr_cell_clear(&s->nr)) sent = nr_cell_set(NR_LOCK_NONE, 0, 0, 0, 0);
            read_state(s);
            return sent && (!job->lte || lte_cell_clear(&s->lte)) && (!job->nr || nr_cell_clear(&s->nr));

        case CMD_BAND_LTE:
        case CMD_BAND_NR:
        case CMD_BAND_GSM:
        case CMD_BAND_WCDMA:
        case CMD_RAT:
        case CMD_NR_MODE:
            if (job->cmd == CMD_BAND_GSM || job->cmd == CMD_BAND_WCDMA) {
                if (!s->pref.has_legacy) { report("state", "未能读取当前 2G/3G 频段设置，未写入"); return -1; }
                uint64_t family = job->cmd == CMD_BAND_GSM ? all_gsm_bits() : all_wcdma_bits();
                legacy_target = (s->pref.legacy & ~family) | job->legacy;
            }
            if (pref_matches(job, hw, &s->pref, legacy_target)) return 1;
            if (job->cmd == CMD_BAND_LTE) sent = lte_bands_set(job->mask, hw);
            else if (job->cmd == CMD_BAND_NR) sent = nr_bands_set(job->sa, job->nsa, job->mask);
            else if (job->cmd == CMD_RAT) sent = rat_set(job->rat);
            else if (job->cmd == CMD_NR_MODE) sent = nr_mode_set(job->nr_mode);
            else sent = legacy_set(legacy_target);
            if (!sent) { read_pref(&s->pref); return 0; }
            return await_pref(job, hw, &s->pref, legacy_target);

        case CMD_RESET: {
            /* 全部开放不是初始快照回滚。每项单独记 ACK，失败后仍读取实际状态。 */
            char failed[192] = "";
            unsigned rats = all_rats(hw);
            uint64_t family = all_gsm_bits() | all_wcdma_bits();
            int need_legacy = (hw->legacy & family) != 0;
            int need_lte = !mask_empty(hw->lte, 32), need_nr = !mask_empty(hw->nr, 64);
            if (!hw->known || !rats || (need_legacy && !s->pref.has_legacy)) {
                report("state", "缺少硬件或当前 2G/3G 设置，未写入"); return -1;
            }
            legacy_target = (s->pref.legacy & ~family) | (hw->legacy & family);
            int legacy_ok = !need_legacy || s->pref.legacy == legacy_target || legacy_set(legacy_target);
            int lte_ok = !need_lte || (s->pref.has_lte && mask_equal_within(s->pref.lte, hw->lte, hw->lte, 32)) ||
                         lte_bands_set(hw->lte, hw);
            int nr_ok = !need_nr || (s->pref.has_sa && s->pref.has_nsa &&
                        mask_equal_within(s->pref.sa, hw->nr, hw->nr, 64) &&
                        mask_equal_within(s->pref.nsa, hw->nr, hw->nr, 64)) || nr_bands_set(1, 1, hw->nr);
            int mode_ok = !need_nr || s->pref.nr_mode == 0 || nr_mode_set(0);
            int rat_ok = s->pref.rat >= 0 && ((unsigned)s->pref.rat & RAT_KNOWN) == rats;
            if (!rat_ok) rat_ok = rat_set(rats);
            int lte_clear_ok = !need_lte || lte_cell_clear(&s->lte);
            if (!lte_clear_ok && s->lte.known) lte_clear_ok = lte_cell_set(1, 0, 0);
            int nr_clear_ok = !need_nr || nr_cell_clear(&s->nr);
            if (!nr_clear_ok && nr_can_write(&s->nr)) nr_clear_ok = nr_cell_set(NR_LOCK_NONE, 0, 0, 0, 0);

            int64_t deadline = now_ms() + 4200;
            do {
                read_pref(&s->pref);
                if ((!need_legacy || (s->pref.has_legacy && s->pref.legacy == legacy_target)) &&
                    (!need_lte || (s->pref.has_lte && mask_equal_within(s->pref.lte, hw->lte, hw->lte, 32))) &&
                    (!need_nr || (s->pref.has_sa && s->pref.has_nsa && s->pref.nr_mode == 0 &&
                     mask_equal_within(s->pref.sa, hw->nr, hw->nr, 64) &&
                     mask_equal_within(s->pref.nsa, hw->nr, hw->nr, 64))) &&
                    s->pref.rat >= 0 && ((unsigned)s->pref.rat & RAT_KNOWN) == rats) break;
                if (now_ms() >= deadline) break;
                usleep(300 * 1000);
            } while (now_ms() < deadline);
            read_lte_cell(&s->lte);
            read_nr_cell(&s->nr);

            if (need_legacy && (!legacy_ok || !s->pref.has_legacy || s->pref.legacy != legacy_target))
                strcat(failed, " 2G/3G 频段");
            if (need_lte && (!lte_ok || !s->pref.has_lte || !mask_equal_within(s->pref.lte, hw->lte, hw->lte, 32)))
                strcat(failed, " LTE 频段");
            if (need_nr && (!nr_ok || !s->pref.has_sa || !s->pref.has_nsa ||
                !mask_equal_within(s->pref.sa, hw->nr, hw->nr, 64) ||
                !mask_equal_within(s->pref.nsa, hw->nr, hw->nr, 64))) strcat(failed, " NR 频段");
            if (need_nr && (!mode_ok || s->pref.nr_mode != 0)) strcat(failed, " 5G 组网");
            if (!rat_ok || s->pref.rat < 0 || ((unsigned)s->pref.rat & RAT_KNOWN) != rats)
                strcat(failed, " 网络制式");
            if (need_lte && (!lte_clear_ok || !lte_cell_clear(&s->lte))) strcat(failed, " LTE 小区锁");
            if (need_nr && (!nr_clear_ok || !nr_cell_clear(&s->nr))) strcat(failed, " NR 小区锁");
            if (failed[0]) {
                snprintf(message, sizeof message, "以下项目未确认恢复，其他项目可能已生效：%s", failed);
                stage = "unverified";
                return -1;
            }
            return 1;
        }
        default:
            return 0;
    }
}

int main(int argc, char **argv) {
    Job job;
    State state;
    Hardware hw;
    int ok = 0, bound = 0, have_hw = 0;
    memset(&job, 0, sizeof job);
    memset(&state, 0, sizeof state);
    memset(&hw, 0, sizeof hw);

    signal(SIGALRM, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    alarm(25);

    if (!parse_job(argc, argv, &job)) goto done;

    if (needs_hardware(job.cmd)) {
        read_hardware(&hw);
        have_hw = 1;
        if (!resolve_against_hardware(&job, &hw)) goto done;
    }
    if (job.cmd == CMD_RESET && !hw.known) {
        report("hardware", "未能读取本机支持的频段，无法恢复默认");
        goto done;
    }

    if (!connect_nas(job.slot)) goto done;
    bound = 1;
    read_state(&state);

    if (job.cmd == CMD_STATE) {
        ok = state.lte.known || state.nr.known || state.pref.known;
        report(ok ? "state" : "unknown", ok ? "已读取" : "基带未返回可解析的状态");
        goto done;
    }

    int verified = run_job(&job, &hw, &state);
    if (verified == 1) {
        ok = 1;
        report("verified", writes_sent ? "已写入，回读与目标一致" : "当前已是目标状态，无需写入");
    } else if (verified == 0) {
        if (writes_sent && (last_result != 0 || last_error != 0))
            report(last_result < 0 ? "transport" : "rejected",
                   last_result < 0 ? "与基带通信中断，命令是否生效未知" : "基带拒绝了该命令");
        else
            report("unverified", "基带已接受命令，但回读与目标不一致");
    }

done:
    if (nas_fd >= 0) { close(nas_fd); nas_fd = -1; }
    alarm(0);
    printf("{\"ok\":%s,\"cmd\":\"%s\",\"slot\":%u,\"stage\":\"%s\",\"message\":\"%s\","
           "\"mutated\":%s,\"writes\":%d,\"accepted\":%d,\"result\":%d,\"error\":%d",
           ok ? "true" : "false", job.name ? job.name : "", job.slot, stage, message,
           mutated ? "true" : "false", writes_sent, writes_accepted, last_result, last_error);
    if (bound) {
        fputs(",\"lteCell\":", stdout); print_lte_cell(&state.lte);
        fputs(",\"nrCell\":", stdout); print_nr_cell(&state.nr);
        fputs(",\"pref\":", stdout); print_pref(&state.pref);
    }
    if (have_hw) { fputs(",\"hardware\":", stdout); print_hardware(&hw); }
    puts("}");
    return ok ? 0 : 1;
}

