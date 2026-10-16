/* SPDX-License-Identifier: GPL-3.0-only */
/* Fixed cleanup operations and one explicit modem restart; no arbitrary DIAG input. */
#define _POSIX_C_SOURCE 200809L
#include <dlfcn.h>
#include <errno.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "efs_protocol.inc"

#define DCI_OK 1001
#define RESPONSE_CAP 16384
typedef unsigned char (*init_fn)(void *);
typedef unsigned char (*deinit_fn)(void);
typedef int (*reg_fn)(int *, uint16_t *, int, void *);
typedef int (*release_fn)(int *);
typedef int (*support_fn)(int, uint16_t *);
typedef void (*response_fn)(unsigned char *, int, void *);
typedef int (*send_fn)(int, unsigned char *, int, unsigned char *, int, response_fn, void *);

static int result_fd = STDOUT_FILENO;
static volatile sig_atomic_t interrupted;
static pthread_mutex_t response_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t response_ready = PTHREAD_COND_INITIALIZER;
static unsigned char response[RESPONSE_CAP], vendor_response[RESPONSE_CAP];
static int response_len, received;

static void on_signal(int sig) { interrupted = sig; }
static void on_alarm(int sig) {
    (void)sig;
    static const char output[] = "{\"success\":false,\"error\":\"cleanup\",\"stage\":\"watchdog_timeout\",\"message\":\"Vendor diagnostic library exceeded the 18 second process deadline\"}\n";
    write(result_fd, output, sizeof(output) - 1);
    _exit(124);
}
static void response_cb(unsigned char *data, int len, void *context) {
    (void)context;
    pthread_mutex_lock(&response_lock);
    if (!received && data && len > 0 && len <= RESPONSE_CAP) {
        memcpy(response, data, (size_t)len);
        response_len = len;
        received = 1;
        pthread_cond_signal(&response_ready);
    }
    pthread_mutex_unlock(&response_lock);
}
static void json_string(FILE *out, const char *s) {
    fputc('"', out);
    for (; *s; ++s) {
        unsigned char c = (unsigned char)*s;
        if (c == '"' || c == '\\') fprintf(out, "\\%c", c);
        else if (c < 32 || c >= 127) fprintf(out, "\\u%04x", c);
        else fputc(c, out);
    }
    fputc('"', out);
}

int main(int argc, char **argv) {
    int success = 0, code = 0, saved_errno = 0, client_id = -1, initialized = 0;
    int register_code = -1, send_code = -1, release_code = -1, deinit_code = -1;
    uint16_t support_mask = 0;
    const char *stage = "arguments", *message = "Only fixed EFS operations or reload-once are accepted";
    char detail[1024] = {0};
    void *library = NULL;
    init_fn init = NULL;
    deinit_fn deinit = NULL;
    reg_fn reg = NULL;
    release_fn release = NULL;
    support_fn support = NULL;
    send_fn send = NULL;

    /* Keep machine-readable output separate from vendor printf diagnostics. */
    result_fd = dup(STDOUT_FILENO);
    if (result_fd < 0) return 2;
    dup2(STDERR_FILENO, STDOUT_FILENO);
    signal(SIGALRM, on_alarm);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);
    signal(SIGCONT, SIG_IGN);
    alarm(18);
    unsigned char command[128];
    size_t command_length=efs_command(argc,argv,command,sizeof command);
    if (!command_length) goto done;
    stage = "load_library";
    library = dlopen("/vendor/lib64/libdiag.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        message = "Unable to load device libdiag.so";
        snprintf(detail, sizeof(detail), "%s", dlerror());
        goto done;
    }
#define LOAD(dst, name) do { *(void **)(&(dst)) = dlsym(library, name); if (!(dst)) { message = "Required libdiag symbol missing"; snprintf(detail, sizeof(detail), "%s", name); goto done; } } while (0)
    LOAD(init, "Diag_LSM_Init");
    LOAD(deinit, "Diag_LSM_DeInit");
    LOAD(reg, "diag_register_dci_client");
    LOAD(release, "diag_release_dci_client");
    LOAD(send, "diag_send_dci_async_req");
    *(void **)(&support) = dlsym(library, "diag_get_dci_support_list_proc");
#undef LOAD
    stage = "initialize";
    if (!init(NULL)) { message = "Diag_LSM_Init failed"; saved_errno = errno; goto done; }
    initialized = 1;
    if (support) support(1, &support_mask);
    stage = "register";
    uint16_t peripheral_list = 1; /* DIAG_CON_MPSS; notification mask only. */
    int signal_type = SIGCONT;
    register_code = reg(&client_id, &peripheral_list, 1, &signal_type);
    if (register_code != DCI_OK) { message = "DCI registration failed"; code = register_code; saved_errno = errno; goto done; }
    stage = "send_fixed_efs";
    /* Only the validated fixed command selected above can reach libdiag. */
    send_code = send(client_id, command, (int)command_length, vendor_response, sizeof(vendor_response), response_cb, NULL);
    if (send_code != DCI_OK) { message = "Fixed EFS request failed"; code = send_code; saved_errno = errno; goto done; }
    stage = "wait_fixed_efs";
    struct timespec until;
    clock_gettime(CLOCK_REALTIME, &until);
    until.tv_sec += 5;
    pthread_mutex_lock(&response_lock);
    while (!received && !interrupted) {
        int wait_code = pthread_cond_timedwait(&response_ready, &response_lock, &until);
        if (wait_code == ETIMEDOUT) break;
    }
    pthread_mutex_unlock(&response_lock);
    if (interrupted) { message = "Interrupted; diagnostic client released"; goto done; }
    if (!received) { message = "No response to fixed EFS request within 5 seconds"; goto done; }
    if (response_len < 4 || memcmp(response,command,4)) { message = "Fixed EFS returned an error or unexpected response"; goto done; }
    success = 1;
    stage = "fixed_efs_response";
    message = "Fixed EFS response received; decode its errno separately";
done:
    if (register_code == DCI_OK && release) release_code = release(&client_id);
    if (initialized && deinit) deinit_code = deinit();
    /* Do not dlclose a vendor library that may retain worker threads on failure. */
    alarm(0);
    FILE *out = fdopen(result_fd, "w");
    if (!out) return 2;
    fprintf(out, "{\"success\":%s,\"stage\":", success ? "true" : "false");
    json_string(out, stage);
    fputs(",\"message\":", out); json_string(out, message);
    fputs(",\"detail\":", out); json_string(out, detail);
    fprintf(out, ",\"code\":%d,\"errno\":%d,\"supportMask\":%u,\"registerCode\":%d,\"sendCode\":%d,\"releaseCode\":%d,\"deinitCode\":%d,\"responseLength\":%d,\"responseHex\":\"", code, saved_errno, support_mask, register_code, send_code, release_code, deinit_code, response_len);
    for (int i = 0; i < response_len; ++i) fprintf(out, "%02x", response[i]);
    fprintf(out, "\",\"error\":\"%s\"}\n", (register_code == DCI_OK && release_code != DCI_OK) || (initialized && deinit_code != 1) ? "cleanup" : "");
    fclose(out);
    return success ? 0 : 1;
}
