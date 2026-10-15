/* SPDX-License-Identifier: GPL-3.0-only */
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* A short foreground DCI session; no logging-mode, NV, or EFS changes. */
static uint8_t captured[96 * 1024];
static size_t captured_size;
static unsigned packets, retained;
static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t stopped;
static void stop(int sig) { (void)sig; stopped = 1; }
static void notify(int sig) { (void)sig; }
static void expired(int sig) { (void)sig; _exit(124); }
static void on_log(unsigned char *data, int size) {
    if (!data || stopped || size < 16 || size > 65535) return;
    if (size >= 20 && data[0] == 0x10 && !data[1]) { data += 4; size -= 4; }
    if (data[2] != 0x7f || data[3] != 0xb9 || (data[0] | data[1] << 8) != size) return;
    pthread_mutex_lock(&mutex);
    /* Bound JSON below RootShell's output limit; evict oldest complete packets. */
    while (retained >= 32 || captured_size + (size_t)size + 2 > sizeof captured) {
        size_t oldest = (size_t)(captured[0] | captured[1] << 8) + 2;
        captured_size -= oldest;
        memmove(captured, captured + oldest, captured_size);
        retained--;
    }
    captured[captured_size++] = (uint8_t)size;
    captured[captured_size++] = (uint8_t)(size >> 8);
    memcpy(captured + captured_size, data, (size_t)size);
    captured_size += (size_t)size;
    retained++;
    packets++;
    pthread_mutex_unlock(&mutex);
}

int main(int argc, char **argv) {
    if (argc != 2 || (argv[1][0] != '0' && argv[1][0] != '1') || argv[1][1]) return 2;
    setbuf(stdout, NULL);
    signal(SIGUSR1, notify); signal(SIGTERM, stop); signal(SIGINT, stop);
    signal(SIGALRM, expired); alarm(8); /* Vendor init/deinit must not leave a resident helper. */
    void *library = dlopen("/vendor/lib64/libdiag.so", RTLD_NOW);
    if (!library) { puts("{\"error\":\"library\"}"); return 1; }
    int (*init)(void *) = dlsym(library, "Diag_LSM_Init");
    int (*deinit)(void) = dlsym(library, "Diag_LSM_DeInit");
    int (*reg)(int *, uint16_t *, int, void *) = dlsym(library, "diag_register_dci_client");
    int (*stream)(int, void (*)(unsigned char *, int), void (*)(unsigned char *, int)) = dlsym(library, "diag_register_dci_stream_proc");
    int (*config)(int, int, uint16_t *, int) = dlsym(library, "diag_log_stream_config");
    int (*release)(int *) = dlsym(library, "diag_release_dci_client");
    int (*vote)(int, int) = dlsym(library, "diag_dci_vote_real_time");
    if (!init || !deinit || !reg || !stream || !config || !release || !vote) { puts("{\"error\":\"api\"}"); return 1; }
    if (!init(NULL)) { puts("{\"error\":\"init\"}"); return 1; }
    int client = -1, signal_number = SIGUSR1;
    uint16_t peripherals = 0, code = 0xb97f;
    int registered = reg(&client, &peripherals, argv[1][0] - '0', &signal_number);
    const char *error = "register";
    int enabled = 0, clean = 1;
    if (registered == 1001 && client > 0) {
        error = "stream";
        if (stream(client, on_log, NULL) == 1001) {
            vote(client, 1);
            error = "enable";
            enabled = 1;
            if (config(client, 1, &code, 1) == 1001) {
                struct timespec start, now;
                clock_gettime(CLOCK_MONOTONIC, &start);
                do {
                    usleep(100000);
                    clock_gettime(CLOCK_MONOTONIC, &now);
                } while (!stopped && now.tv_sec - start.tv_sec < 3);
                error = "no_log";
            }
        }
    }
    if (enabled && config(client, 0, &code, 1) != 1001) clean = 0;
    if (client > 0 && release(&client) != 1001) clean = 0;
    if (!deinit()) clean = 0;
    pthread_mutex_lock(&mutex);
    if (!clean) puts("{\"error\":\"cleanup\"}");
    else if (retained) {
        printf("{\"processor\":%c,\"packets\":%u,\"logs\":[", argv[1][0], packets);
        size_t at = 0;
        for (unsigned i = 0; i < retained; i++) {
            size_t size = captured[at] | captured[at + 1] << 8;
            at += 2;
            printf("%s\"", i ? "," : "");
            for (size_t j = 0; j < size; j++) printf("%02x", captured[at + j]);
            printf("\"");
            at += size;
        }
        puts("]}");
    } else printf("{\"error\":\"%s\"}\n", error);
    int exit_code = clean && retained ? 0 : 1;
    pthread_mutex_unlock(&mutex);
    return exit_code;
}
