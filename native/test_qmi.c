/* SPDX-License-Identifier: GPL-3.0-only */
#define CELLLOCK_PROTOCOL_TEST 1
#define main celllock_application_main
#include "celllock_qmi.c"
#undef main

static unsigned checks;
#define CHECK(x) do { ++checks; if (!(x)) { fprintf(stderr, "CHECK failed at %d: %s\n", __LINE__, #x); exit(1); } } while (0)
static State modem;
static int raw_mode, raw_length, fail_message, fail_tlv, fail_error, fail_transport;
static int ignore_tlv, force_nr_ack, observed_message, observed_length, query_count, set_count, combined_partial;
static uint8_t raw_reply[QMI_MAX], observed[1024];

static void response_start(Reply *r, unsigned id, int result, int error) {
    memset(r, 0, sizeof *r); r->result = result; r->error = error; r->n = 14;
    r->bytes[0] = 2; put16(r->bytes + 3, id); put16(r->bytes + 5, 7);
    r->bytes[7] = 2; put16(r->bytes + 8, 4);
    put16(r->bytes + 10, result); put16(r->bytes + 12, error);
}
static void response_add(Reply *r, unsigned id, const void *data, int length) {
    r->n = add_tlv(r->bytes, r->n, (uint8_t)id, data, length);
    put16(r->bytes + 5, r->n - 7);
}
static void raw_use(const Reply *r) { raw_mode = 1; raw_length = r->n; memcpy(raw_reply, r->bytes, r->n); }
static void fresh(void) {
    memset(&modem, 0, sizeof modem);
    modem.lte.known = modem.nr.known = modem.pref.known = 1; modem.nr.type = NR_LOCK_NONE;
    modem.pref.has_lte = modem.pref.has_sa = modem.pref.has_nsa = modem.pref.has_legacy = 1;
    modem.pref.rat = RAT_KNOWN;
    modem.pref.legacy = gsm_bits(850) | wcdma_bits(1) | (1ull << 63);
    bit_set(modem.pref.lte, 3); bit_set(modem.pref.sa, 78); bit_set(modem.pref.nsa, 78);
    raw_mode = raw_length = fail_message = fail_tlv = fail_error = fail_transport = 0;
    ignore_tlv = force_nr_ack = observed_message = observed_length = query_count = set_count = 0;
    mock_calls = mock_lookup_error = mock_connect_error = mock_socket_error = 0;
    mock_clock = 0; nas_fd = 12; mutated = writes_sent = writes_accepted = 0;
    nr_combined_only = step_count = step_writes = step_accepted = combined_partial = 0;
    last_result = last_error = -1; report("arguments", "参数错误");
}
static Hardware hardware(void) {
    Hardware hw = {.known = 1, .legacy = gsm_bits(850) | wcdma_bits(1)};
    bit_set(hw.lte, 3); bit_set(hw.nr, 78); return hw;
}

/* Stateful fake modem. All writes affect this process's memory only. */
static ssize_t exchange(int fd, uint8_t *buf, size_t length, int timeout_ms, const char **failed) {
    (void)fd; (void)timeout_ms; (void)failed; ++mock_calls; ++mock_clock;
    unsigned id = le16(buf + 3), transaction = le16(buf + 1);
    observed_message = (int)id; observed_length = (int)length;
    if (length > sizeof observed) abort();
    memcpy(observed, buf, length);
    if (raw_mode) { memcpy(buf, raw_reply, raw_length); put16(buf + 1, transaction); return raw_length; }
    Reply request_frame = {.n = (int)length}; memcpy(request_frame.bytes, buf, length);
    int n = 0; const uint8_t *value = NULL;
    unsigned field = id == NAS_SET_SYS_SEL && length > 7 ? buf[7] : 0;
    if (field == SET_DURATION && length > 11) field = buf[11];
    if (id == NAS_SET_SYS_SEL || id == NAS_LTE_CELL_SET || id == NAS_NR_CELL_SET) ++set_count;
    else ++query_count;
    if (id == NAS_SET_SYS_SEL && has_tlv(&request_frame, SET_DURATION) &&
        (field == SET_NR_MODE || field == SET_NR_SA || field == SET_NR_NSA)) {
        Reply error; response_start(&error, id, 1, 17);
        memcpy(buf, error.bytes, error.n); put16(buf + 1, transaction); return error.n;
    }
    if ((int)id == fail_message && (!fail_tlv || (int)field == fail_tlv)) {
        if (fail_transport) { mock_clock += 1800; return -fail_transport; }
        Reply error; response_start(&error, id, 1, fail_error);
        memcpy(buf, error.bytes, error.n); put16(buf + 1, transaction); return error.n;
    }
    if (id == NAS_SET_SYS_SEL && (int)field != ignore_tlv) {
        value = tlv(&request_frame, field, &n); if (!value) abort();
        if (field == SET_LTE_EXT || field == SET_LTE) { memset(modem.pref.lte, 0, 32); memcpy(modem.pref.lte, value, n); }
        else if (field == SET_NR_SA) memcpy(modem.pref.sa, value, 64);
        else if (field == SET_NR_NSA) memcpy(modem.pref.nsa, value, 64);
        else if (field == SET_NR_COMBINED) { memcpy(modem.pref.sa, value, 64); if(!combined_partial)memcpy(modem.pref.nsa, value, 64); }
        else if (field == SET_MODE) modem.pref.rat = le16(value);
        else if (field == SET_NR_MODE) modem.pref.nr_mode = (int)le32(value);
        else if (field == SET_LEGACY) modem.pref.legacy = le64(value);
    } else if (id == NAS_LTE_CELL_SET) {
        value = tlv(&request_frame, 1, &n); if (!value) abort(); modem.lte.count = value[0]; modem.lte.known = 1;
        for (int i=0;i<value[0];++i) { modem.lte.pci[i] = le16(value + 1+i*6); modem.lte.earfcn[i] = le32(value + 3+i*6); }
    } else if (id == NAS_NR_CELL_SET) {
        value = tlv(&request_frame, 1, &n); if (!value) abort(); modem.nr.type = (int)le32(value); modem.nr.known = 1;
        if (modem.nr.type == NR_LOCK_PCI) {
            value = tlv(&request_frame, 0x10, &n); if (!value) abort();
            modem.nr.pci = le16(value); modem.nr.scs = SCS_KHZ[le32(value + 2)];
            modem.nr.arfcn = le32(value + 6); memcpy(modem.nr.bands, value + 10, 64);
        } else if (modem.nr.type == NR_LOCK_ARFCN) {
            value = tlv(&request_frame, 0x11, &n); if (!value) abort();
            modem.nr.arfcn_count = value[0];
            for(int i=0;i<value[0];++i){modem.nr.arfcns[i]=le32(value+1+i*8);modem.nr.arfcn_scs[i]=SCS_KHZ[le32(value+5+i*8)];}
        }
    }
    Reply out; response_start(&out, id, 0, 0); uint8_t v[512] = {0};
    if (id == NAS_LTE_CELL_GET) {
        v[0] = modem.lte.count;
        for (int i = 0; i < modem.lte.count; ++i) { put16(v + 1 + i * 4, modem.lte.pci[i]); put16(v + 3 + i * 4, modem.lte.earfcn[i]); }
        response_add(&out, 0x10, v, 1 + modem.lte.count * 4);
    } else if (id == NAS_NR_CELL_GET && !force_nr_ack) {
        put32(v, modem.nr.type); response_add(&out, 0x10, v, 4);
        if (modem.nr.type == NR_LOCK_PCI) {
            put16(v, modem.nr.pci); put32(v + 2, scs_index(modem.nr.scs)); put32(v + 6, (unsigned)modem.nr.arfcn);
            memcpy(v + 10, modem.nr.bands, 64); response_add(&out, 0x11, v, 74);
        } else if (modem.nr.type == NR_LOCK_ARFCN) {
            v[0] = modem.nr.arfcn_count;
            for (int i = 0; i < modem.nr.arfcn_count; ++i) { put32(v + 1 + i * 8, modem.nr.arfcns[i]); put32(v + 5 + i * 8, scs_index(modem.nr.arfcn_scs[i])); }
            response_add(&out, 0x12, v, 1 + 8 * modem.nr.arfcn_count);
        }
    } else if (id == NAS_GET_SYS_SEL) {
        put16(v, modem.pref.rat); response_add(&out, GET_MODE, v, 2);
        put64(v, modem.pref.legacy); response_add(&out, GET_LEGACY, v, 8);
        put32(v, modem.pref.nr_mode); response_add(&out, GET_NR_MODE, v, 4);
        if (modem.pref.has_lte) response_add(&out, GET_LTE_EXT, modem.pref.lte, 32);
        if (modem.pref.has_sa) response_add(&out, GET_NR_SA, modem.pref.sa, 64);
        if (modem.pref.has_nsa) response_add(&out, GET_NR_NSA, modem.pref.nsa, 64);
    }
    memcpy(buf, out.bytes, out.n); put16(buf + 1, transaction); return out.n;
}

static void test_arguments(void) {
    unsigned v; uint8_t mask[64] = {0}; uint64_t legacy;
    CHECK(number("65535", 65535, &v) && v == 65535);
    CHECK(!number("65536", 65535, &v)); CHECK(!number("-1", 65535, &v));
    CHECK(!number("1;id", 65535, &v)); CHECK(!number("999999999999", 65535, &v));
    CHECK(!number(NULL, 1, &v)); CHECK(!number("", 1, &v));
    CHECK(band_list("1,3,256", 256, mask) == 3 && bit_has(mask, 256));
    CHECK(band_list(",1", 256, mask) < 0); CHECK(band_list("1,", 256, mask) < 0);
    CHECK(band_list("1,,3", 256, mask) < 0); CHECK(band_list("257", 256, mask) < 0);
    CHECK(band_list("0", 256, mask) < 0); CHECK(band_list("1;id", 256, mask) < 0);
    CHECK(parse_rats("gsm,wcdma,lte,nr", &v) && v == RAT_KNOWN);
    CHECK(!parse_rats("lte,", &v)); CHECK(!parse_rats("lte,,nr", &v)); CHECK(!parse_rats("cdma", &v));
    CHECK(parse_legacy("850,900", 1, &legacy) && legacy == (gsm_bits(850) | gsm_bits(900)));
    CHECK(!parse_legacy("850,", 1, &legacy)); CHECK(!parse_legacy("7", 1, &legacy));
    CHECK(parse_legacy("1,19", 0, &legacy) && legacy == (wcdma_bits(1) | wcdma_bits(19)));
    CHECK(scs_index(15) == 0 && scs_index(240) == 4 && scs_index(25) == -1);
    Job job; char *good[] = {"test", "cell-nr", "1", "3279165", "1007", "512", "240"};
    CHECK(parse_job(7, good, &job) && job.slot == 1 && job.scs == 4);
    good[2] = "2"; CHECK(!parse_job(7, good, &job)); good[2] = "0";
    CHECK(!parse_job(6, good, &job)); good[3] = "3279166"; CHECK(!parse_job(7, good, &job));
    char *freq[] = {"test", "freq-nr", "0", "0", "30"}; CHECK(parse_job(5, freq, &job));
    CHECK(job.cmd == CMD_FREQ_NR && job.scs == 1);
    Hardware hw = {0}; job = (Job){.cmd = CMD_RAT, .all = 1}; CHECK(!resolve_against_hardware(&job, &hw));
    CHECK(all_rats(&hw) == 0); hw = hardware(); CHECK(resolve_against_hardware(&job, &hw) && job.rat == RAT_KNOWN);
    job = (Job){.cmd = CMD_BAND_LTE}; bit_set(job.mask, 250);
    CHECK(!resolve_against_hardware(&job, &hw)); bit_set(job.mask, 3);
    CHECK(resolve_against_hardware(&job, &hw) && bit_has(job.mask, 250));
    job.all = 1; CHECK(resolve_against_hardware(&job, &hw) && !bit_has(job.mask, 250));
}

static void test_frames(void) {
    fresh(); Reply r; uint8_t v[128] = {0}; LteCell lte; NrCell nr; Pref pref; Hardware hw;
    CHECK(allowed(NAS_SERVICE, NAS_SET_SYS_SEL)); CHECK(allowed(DMS_SERVICE, DMS_GET_BANDS));
    CHECK(!allowed(77, NAS_SET_SYS_SEL)); CHECK(!allowed(DMS_SERVICE, NAS_SET_SYS_SEL)); CHECK(!allowed(NAS_SERVICE, 0xffff));
    request_on(12, 77, NAS_SET_SYS_SEL, NULL, 0, &r); CHECK(r.transport == EINVAL && !mock_calls);
    request_on(12, NAS_SERVICE, NAS_SET_SYS_SEL, NULL, 1, &r); CHECK(r.transport == EINVAL && !mock_calls);
    response_start(&r, NAS_NR_CELL_GET, 0, 0); CHECK(ack_only(&r)); raw_use(&r); read_nr_cell(&nr);
    CHECK(nr.ack_only && !nr.known && !nr_cell_clear(&nr) && nr_can_write(&nr));
    response_add(&r, 0x55, v, 0); raw_use(&r); read_nr_cell(&nr); CHECK(!nr.ack_only && !nr.known && !nr_can_write(&nr));
    response_start(&r, NAS_NR_CELL_GET, 0, 0); response_add(&r, 2, v, 4); raw_use(&r); read_nr_cell(&nr);
    CHECK(nr.transport == EPROTO && !nr.ack_only && !nr_can_write(&nr));
    response_start(&r, NAS_NR_CELL_GET, 1, 3); raw_use(&r); read_nr_cell(&nr); CHECK(!nr.known && !nr_cell_clear(&nr) && nr_can_write(&nr));
    response_start(&r, NAS_NR_CELL_GET, 1, 71); raw_use(&r); read_nr_cell(&nr); CHECK(!nr_can_write(&nr));
    response_start(&r, NAS_LTE_CELL_GET, 0, 0); v[0] = 0; response_add(&r, 0x10, v, 1); raw_use(&r); read_lte_cell(&lte); CHECK(lte_cell_clear(&lte));
    v[0] = 1; put16(v + 1, 503); put16(v + 3, 65535);
    response_start(&r, NAS_LTE_CELL_GET, 0, 0); response_add(&r, 0x10, v, 5); raw_use(&r); read_lte_cell(&lte); CHECK(lte_cell_is(&lte, 65535, 503));
    r.n--; raw_use(&r); read_lte_cell(&lte); CHECK(!lte.known && lte.transport == EPROTO);
    response_start(&r, NAS_LTE_CELL_GET, 0, 3); response_add(&r, 0x10, v, 5); raw_use(&r); read_lte_cell(&lte); CHECK(!lte.known);
    put16(v + 1, 504); response_start(&r, NAS_LTE_CELL_GET, 0, 0); response_add(&r, 0x10, v, 5); raw_use(&r); read_lte_cell(&lte); CHECK(!lte.known);
    response_start(&r, NAS_NR_CELL_GET, 0, 0); put32(v, NR_LOCK_ARFCN); response_add(&r, 0x10, v, 4);
    v[0] = 1; put32(v + 1, 3279166); put32(v + 5, 1); response_add(&r, 0x12, v, 9); raw_use(&r); read_nr_cell(&nr); CHECK(!nr.known);
    response_start(&r, NAS_GET_SYS_SEL, 0, 0); put32(v, 256); response_add(&r, GET_NR_MODE, v, 4);
    response_add(&r, GET_LTE_EXT, v, 1); response_add(&r, GET_LTE, v, 8); raw_use(&r); read_pref(&pref); CHECK(pref.nr_mode == -1 && !pref.has_lte);
    response_start(&r, DMS_GET_BANDS, 0, 0); put16(v, 2); put16(v + 2, 3); put16(v + 4, 66); response_add(&r, 0x12, v, 6);
    put16(v, 1); put16(v + 2, 257); response_add(&r, 0x13, v, 4); parse_hardware(&r, &hw);
    CHECK(hw.known && bit_has(hw.lte, 3) && bit_has(hw.lte, 66) && bit_has(hw.nr, 257));
    response_add(&r, 0x13, v, 4); parse_hardware(&r, &hw); CHECK(!hw.known);
    response_start(&r, DMS_GET_BANDS, 0, 0); put16(v, 2); response_add(&r, 0x13, v, 4); parse_hardware(&r, &hw); CHECK(!hw.known);
    response_start(&r, DMS_GET_BANDS, 0, 0); put16(v, 1); put16(v + 2, 513); response_add(&r, 0x13, v, 4); parse_hardware(&r, &hw); CHECK(!hw.known);
}

static void test_payloads(void) {
    fresh(); CHECK(lte_cell_set(0, 38950, 123));
    const uint8_t expected[] = {1,7,0,1,123,0,0x26,0x98,0,0,0x10,1,0,1};
    CHECK(observed_message == NAS_LTE_CELL_SET && observed_length == 21 && !memcmp(observed + 7, expected, 14));
    CHECK(lte_cell_set(1, 0, 0)); const uint8_t clear[] = {1,1,0,0,0x10,1,0,1}; CHECK(!memcmp(observed + 7, clear, 8));
    CHECK(nr_cell_set(NR_LOCK_PCI, 633984, 321, 78, 1)); const uint8_t *p = observed + 7;
    CHECK(observed_length == 91 && le32(p + 3) == 0 && p[7] == 0x10 && le16(p + 8) == 74);
    CHECK(le16(p + 10) == 321 && le32(p + 12) == 1 && le32(p + 16) == 633984);
    CHECK(bit_has(p + 20, 78)); unsigned bits = 0; for (int i = 20; i < 84; ++i) bits += __builtin_popcount(p[i]); CHECK(bits == 1);
    CHECK(nr_cell_set(NR_LOCK_ARFCN, 504990, 0, 0, 1)); p = observed + 7;
    CHECK(observed_length == 26 && le32(p + 3) == 1 && p[7] == 0x11 && le16(p + 8) == 9);
    CHECK(p[10] == 1 && le32(p + 11) == 504990 && le32(p + 15) == 1);
    CHECK(nr_cell_set(NR_LOCK_NONE, 0, 0, 0, 0) && observed_length == 14 && le32(observed + 10) == 2);
    CHECK(rat_set(RAT_LTE)); CHECK(observed[7] == SET_DURATION && observed[10] == 1 && observed[11] == SET_MODE && le16(observed + 14) == RAT_LTE);
    CHECK(nr_mode_set(2) && observed_length == 14 && observed[7] == SET_NR_MODE && le32(observed + 10) == 2);
    CHECK(legacy_set(gsm_bits(850)) && observed[11] == SET_LEGACY && le64(observed + 14) == gsm_bits(850));
    fresh(); CHECK(connect_nas(1) && observed_message == NAS_BIND && observed[10] == 1 && !mutated);
    fresh(); fail_message = NAS_BIND; fail_error = 3; CHECK(!connect_nas(0) && set_count == 0 && !strcmp(stage, "bind"));
}

static void test_jobs(void) {
    fresh(); Hardware hw = hardware(); State s = modem; Job j = {.cmd = CMD_CELL_LTE, .arfcn = 1650, .pci = 321};
    CHECK(run_job(&j, &hw, &s) == 1 && lte_cell_is(&s.lte, 1650, 321) && writes_sent == 1 && writes_accepted == 1);
    fresh(); s = modem; s.lte.known = 0; CHECK(run_job(&j, &hw, &s) == -1 && !writes_sent);
    fresh(); s = modem; fail_message = NAS_LTE_CELL_SET; fail_transport = ETIMEDOUT;
    CHECK(run_job(&j, &hw, &s) == 0 && writes_sent == 1 && writes_accepted == 0 && query_count == 3 && s.lte.known);
    fresh(); s = modem; j = (Job){.cmd = CMD_CELL_CLEAR, .lte = 1}; CHECK(run_job(&j, &hw, &s) == 1 && !writes_sent);
    fresh(); s = modem; j = (Job){.cmd = CMD_CELL_NR, .arfcn = 633984, .pci = 202, .band = 78, .scs = 1};
    CHECK(run_job(&j, &hw, &s) == 1 && nr_cell_is(&s.nr, 633984, 202, 78, 1));
    bit_set(s.nr.bands, 41); CHECK(!nr_cell_is(&s.nr, 633984, 202, 78, 1));
    fresh(); s = modem; s.nr.known = 0; s.nr.transport = EPROTO; CHECK(run_job(&j, &hw, &s) == -1 && !writes_sent);
    fresh(); force_nr_ack = 1; read_state(&s); CHECK(run_job(&j, &hw, &s) == 0 && writes_accepted == 1 && s.nr.ack_only && !s.nr.known);
    fresh(); s = modem; j = (Job){.cmd = CMD_FREQ_NR, .arfcn = 504990, .scs = 1}; CHECK(run_job(&j, &hw, &s) == 1 && nr_freq_is(&s.nr, 504990, 1));
    fresh(); s = modem; j = (Job){.cmd = CMD_BAND_LTE}; bit_set(j.mask, 3); CHECK(run_job(&j, &hw, &s) == 1 && !writes_sent);
    fresh(); bit_set(hw.lte, 66); s = modem; memset(j.mask, 0, 64); bit_set(j.mask, 66); CHECK(run_job(&j, &hw, &s) == 1 && bit_has(s.pref.lte, 66));
    fresh(); s = modem; fail_message = NAS_SET_SYS_SEL; fail_transport = ETIMEDOUT;
    CHECK(run_job(&j, &hw, &s) == 0 && set_count == 1 && query_count == 1 && !bit_has(s.pref.lte, 66));
    fresh(); uint8_t mask[32] = {0}; bit_set(mask, 3); fail_message = NAS_SET_SYS_SEL; fail_tlv = SET_LTE_EXT; fail_error = 71;
    CHECK(lte_bands_set(mask, &hw) && writes_sent == 2 && writes_accepted == 1);
    fresh(); fail_message = NAS_SET_SYS_SEL; fail_tlv = SET_LTE_EXT; fail_error = 3; CHECK(!lte_bands_set(mask, &hw) && writes_sent == 1);
    fresh(); s = modem; j = (Job){.cmd = CMD_BAND_NR, .sa = 1, .nsa = 1}; bit_set(j.mask, 41); bit_set(hw.nr, 41);
    fail_message = NAS_SET_SYS_SEL; fail_tlv = SET_NR_NSA; fail_transport = ETIMEDOUT;
    CHECK(run_job(&j, &hw, &s) == 0 && writes_sent == 2 && writes_accepted == 1 && bit_has(s.pref.sa, 41) && bit_has(s.pref.nsa, 78));
    fresh(); s = modem; j = (Job){.cmd = CMD_BAND_GSM, .legacy = gsm_bits(900)}; hw.legacy |= gsm_bits(900);
    CHECK(run_job(&j, &hw, &s) == 1 && (s.pref.legacy & (1ull << 63)) && (s.pref.legacy & wcdma_bits(1)));
    uint64_t target = s.pref.legacy; s.pref.legacy ^= 1ull << 63; CHECK(!pref_matches(&j, &hw, &s.pref, target));
    fresh(); s = modem; j = (Job){.cmd = CMD_RAT, .rat = RAT_LTE}; ignore_tlv = SET_MODE;
    CHECK(run_job(&j, &hw, &s) == 0 && writes_accepted == 1 && mock_clock >= 4200 && mock_clock < 5000);
}

static void test_reset(void) {
    fresh(); Hardware hw = hardware(); Job j = {.cmd = CMD_RESET}; State s = modem;
    CHECK(run_job(&j, &hw, &s) == 1 && writes_sent == 0);
    fresh(); s = modem; force_nr_ack = 1; s.nr.known = 0; s.nr.ack_only = 1;
    CHECK(run_job(&j, &hw, &s) == -1 && !strcmp(stage, "unverified") && strstr(message, "NR 小区锁") && s.nr.ack_only);
    fresh(); bit_set(hw.nr, 41); modem.pref.nr_mode = 2; s = modem; ignore_tlv = SET_NR_NSA;
    CHECK(run_job(&j, &hw, &s) == -1 && strstr(message, "NR 频段") && bit_has(s.pref.sa, 41) && !bit_has(s.pref.nsa, 41));
    fresh(); hw = hardware(); modem.pref.nr_mode = 2; s = modem; ignore_tlv = SET_NR_MODE;
    CHECK(run_job(&j, &hw, &s) == -1 && strstr(message, "5G 组网"));
    fresh(); hw.legacy |= gsm_bits(900); s = modem; ignore_tlv = SET_LEGACY;
    CHECK(run_job(&j, &hw, &s) == -1 && strstr(message, "2G/3G"));
    fresh(); hw = hardware(); hw.legacy |= gsm_bits(900); s = modem; fail_message = NAS_SET_SYS_SEL; fail_tlv = SET_LEGACY; fail_error = 3;
    CHECK(run_job(&j, &hw, &s) == -1 && writes_sent == 1 && !writes_accepted && strstr(message, "2G/3G"));
    fresh(); hw = hardware(); s = modem; s.pref.has_legacy = 0; CHECK(run_job(&j, &hw, &s) == -1 && !writes_sent);
    fresh(); hw = hardware(); bit_set(hw.lte, 66); bit_set(hw.nr, 41); hw.legacy |= gsm_bits(900);
    modem.pref.rat = RAT_LTE; modem.pref.nr_mode = 2; modem.lte.count = 1; modem.lte.pci[0] = 321; modem.lte.earfcn[0] = 1650;
    modem.nr.type = NR_LOCK_PCI; modem.nr.pci = 202; modem.nr.arfcn = 633984; modem.nr.scs = 30; bit_set(modem.nr.bands, 78); s = modem;
    CHECK(run_job(&j, &hw, &s) == 1 && lte_cell_clear(&s.lte) && nr_cell_clear(&s.nr) && s.pref.nr_mode == 0 && s.pref.rat == RAT_KNOWN);
    CHECK(writes_sent == writes_accepted && writes_sent == 8 && (s.pref.legacy & (1ull << 63)));
    CHECK(!memcmp(s.pref.lte, hw.lte, 32) && !memcmp(s.pref.sa, hw.nr, 64) && !memcmp(s.pref.nsa, hw.nr, 64));
    fresh(); s = modem; Pref before = s.pref;
    modem.lte.count = s.lte.count = 1; modem.nr.type = s.nr.type = NR_LOCK_PCI;
    j = (Job){.cmd = CMD_CELL_CLEAR, .lte = 1, .nr = 1};
    CHECK(run_job(&j, &hw, &s) == 1 && lte_cell_clear(&s.lte) && nr_cell_clear(&s.nr));
    CHECK(!memcmp(&s.pref, &before, sizeof before));
}
static void test_exact_and_compatibility(void) {
    fresh();Hardware nr_hw=hardware();State nr_state=modem;Job nr_job={.cmd=CMD_NR_MODE,.nr_mode=2};
    CHECK(run_job(&nr_job,&nr_hw,&nr_state)==1&&nr_state.pref.nr_mode==2&&writes_sent==1&&writes_accepted==1);
    nr_job.nr_mode=0;
    CHECK(run_job(&nr_job,&nr_hw,&nr_state)==1&&nr_state.pref.nr_mode==0&&writes_sent==2);
    nr_job=(Job){.cmd=CMD_BAND_NR,.sa=1};bit_set(nr_job.mask,41);
    CHECK(run_job(&nr_job,&nr_hw,&nr_state)==1&&bit_has(nr_state.pref.sa,41)&&bit_has(nr_state.pref.nsa,78)&&!nr_combined_only);
    nr_job.sa=0;nr_job.nsa=1;memset(nr_job.mask,0,sizeof nr_job.mask);bit_set(nr_job.mask,1);
    CHECK(run_job(&nr_job,&nr_hw,&nr_state)==1&&bit_has(nr_state.pref.sa,41)&&bit_has(nr_state.pref.nsa,1)&&!nr_combined_only);
    fresh();Hardware hw=hardware();State s=modem;Job j={.cmd=CMD_BAND_LTE,.exact=1};
    bit_set(j.mask,3);bit_set(j.mask,250);
    CHECK(!pref_matches(&j,&hw,&s.pref,0));
    CHECK(run_job(&j,&hw,&s)==1&&writes_sent==1&&bit_has(s.pref.lte,250));
    CHECK(run_job(&j,&hw,&s)==1&&writes_sent==1);
    memset(j.mask,0,sizeof j.mask);CHECK(run_job(&j,&hw,&s)==1&&mask_empty(s.pref.lte,32));
    fresh();s=modem;j=(Job){.cmd=CMD_LEGACY,.exact=1,.legacy=gsm_bits(850)|wcdma_bits(1)};
    CHECK(run_job(&j,&hw,&s)==1&&s.pref.legacy==j.legacy&&!(s.pref.legacy&(1ull<<63)));
    char *lte[]={"test","restore-lte","0","none"};CHECK(parse_job(4,lte,&j)&&j.exact&&mask_empty(j.mask,64));
    lte[3]="all";CHECK(!parse_job(4,lte,&j));lte[3]="257";CHECK(!parse_job(4,lte,&j));
    char *legacy[]={"test","restore-legacy","0","ffffffffffffffff"};CHECK(parse_job(4,legacy,&j)&&j.legacy==UINT64_MAX);
    legacy[3]="-1";CHECK(!parse_job(4,legacy,&j));
    char *nr[]={"test","restore-nr-combined","0","sa","41"};CHECK(!parse_job(5,nr,&j));
    nr[3]="both";CHECK(parse_job(5,nr,&j)&&j.combined&&j.exact);
    fresh();s=modem;j=(Job){.cmd=CMD_BAND_NR,.sa=1,.nsa=1};bit_set(j.mask,41);
    fail_message=NAS_SET_SYS_SEL;fail_tlv=SET_NR_SA;fail_error=17;
    CHECK(run_job(&j,&hw,&s)==1&&nr_combined_only&&writes_sent==2&&writes_accepted==1&&bit_has(s.pref.sa,41)&&bit_has(s.pref.nsa,41));
    fresh();s=modem;fail_message=NAS_SET_SYS_SEL;fail_tlv=SET_NR_NSA;fail_error=17;
    CHECK(run_job(&j,&hw,&s)==1&&nr_combined_only&&writes_sent==3&&writes_accepted==2);
    fresh();s=modem;j.nsa=0;fail_message=NAS_SET_SYS_SEL;fail_tlv=SET_NR_SA;fail_error=17;
    CHECK(run_job(&j,&hw,&s)==0&&!nr_combined_only&&writes_sent==1&&bit_has(s.pref.nsa,78));
    fresh();s=modem;j.nsa=1;fail_message=NAS_SET_SYS_SEL;fail_tlv=SET_NR_SA;fail_transport=ETIMEDOUT;
    CHECK(run_job(&j,&hw,&s)==0&&writes_sent==1&&!nr_combined_only);
    fresh();s=modem;fail_message=NAS_SET_SYS_SEL;fail_tlv=SET_NR_SA;fail_error=17;modem.pref.has_nsa=0;
    CHECK(run_job(&j,&hw,&s)==0&&writes_sent==1&&!nr_combined_only);
    fresh();s=modem;fail_message=NAS_SET_SYS_SEL;fail_tlv=SET_NR_SA;fail_error=17;combined_partial=1;
    CHECK(run_job(&j,&hw,&s)==0&&writes_accepted==1&&bit_has(s.pref.sa,41)&&!bit_has(s.pref.nsa,41));
    fresh();s=modem;j=(Job){.cmd=CMD_RESET};force_nr_ack=1;s.nr.known=0;s.nr.ack_only=1;
    CHECK(run_job(&j,&hw,&s)==-1&&step_count==7&&!strcmp(steps[6].status,"unknown")&&!strcmp(steps[0].status,"unchanged"));
    fresh();s=modem;j=(Job){.cmd=CMD_BAND_NR,.sa=1,.nsa=1,.exact=1};bit_set(j.mask,78);bit_set(j.mask,257);
    CHECK(run_job(&j,&hw,&s)==1&&bit_has(s.pref.sa,257)&&bit_has(s.pref.nsa,257));
    fresh();s=modem;j.combined=1;memset(j.mask,0,sizeof j.mask);bit_set(j.mask,41);
    CHECK(run_job(&j,&hw,&s)==1&&writes_sent==1&&observed_message==NAS_GET_SYS_SEL);
}
static void test_saved_locks(void) {
    Job j; Hardware hw=hardware(); State s;
    fresh();s=modem;char *lte[]={"test","cell-lte-list","0","1650,42,1850,43"};
    CHECK(parse_job(4,lte,&j)&&j.count==2&&run_job(&j,&hw,&s)==1&&s.lte.count==2&&writes_sent==1);
    unsigned temp=s.lte.pci[0];s.lte.pci[0]=s.lte.pci[1];s.lte.pci[1]=temp;temp=s.lte.earfcn[0];s.lte.earfcn[0]=s.lte.earfcn[1];s.lte.earfcn[1]=temp;
    CHECK(lte_list_is(&j,&s.lte));s.lte.earfcn[0]++;CHECK(!lte_list_is(&j,&s.lte));
    lte[3]="1650,42,";CHECK(!parse_job(4,lte,&j));lte[3]="1650";CHECK(!parse_job(4,lte,&j));lte[3]="1650,504";CHECK(!parse_job(4,lte,&j));
    fresh();s=modem;char *nr[]={"test","cell-nr-mask","1","633984","202","41,78,257","30"};
    CHECK(parse_job(7,nr,&j)&&run_job(&j,&hw,&s)==1&&bit_has(s.nr.bands,257)&&bit_has(s.nr.bands,41));
    nr[5]="513";CHECK(!parse_job(7,nr,&j));nr[5]="none";CHECK(!parse_job(7,nr,&j));
    fresh();s=modem;char *freq[]={"test","freq-nr-list","0","633984,30,158290,15"};
    CHECK(parse_job(4,freq,&j)&&j.count==2&&run_job(&j,&hw,&s)==1&&s.nr.arfcn_count==2&&s.nr.arfcn_scs[1]==15);
    freq[3]="633984,25";CHECK(!parse_job(4,freq,&j));freq[3]="3279166,30";CHECK(!parse_job(4,freq,&j));
    fresh();s=modem;freq[3]="633984,30,158290,15";CHECK(parse_job(4,freq,&j));fail_message=NAS_NR_CELL_SET;fail_transport=ETIMEDOUT;
    CHECK(run_job(&j,&hw,&s)==0&&writes_sent==1);
}
int main(void) {
    test_saved_locks(); test_arguments(); test_frames(); test_payloads(); test_jobs(); test_reset();test_exact_and_compatibility();
    printf("PASS: %u pure protocol checks (mock transport; no device/network access)\n", checks); return 0;
}
