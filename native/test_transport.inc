/* SPDX-License-Identifier: GPL-3.0-only
 * Pure protocol tests: replaces every QRTR/socket operation.
 * Never opens a socket, phone, device node, or network connection.
 */
#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>
#define QMI_HEADER 7
#define QMI_MAX 65536
#define QMI_RESPONSE 2
#define AF_QIPCRTR 42
#define SOCK_DGRAM 2
#define SOCK_CLOEXEC 0
#define SOL_SOCKET 1
#define SO_RCVTIMEO 1
#define SO_SNDTIMEO 2
#ifndef SIGALRM
#define SIGALRM 14
#endif
struct sockaddr { int unused; };
struct sockaddr_qrtr { int sq_family; uint32_t sq_node, sq_port; };
struct test_timeval { long tv_sec, tv_usec; };
#define timeval test_timeval
static int64_t mock_clock;
static int mock_lookup_error, mock_connect_error, mock_socket_error, mock_calls;
static uint16_t le16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static int64_t now_ms(void) { return mock_clock; }
static int mock_sleep(unsigned usec) { mock_clock += usec / 1000; return 0; }
static unsigned mock_alarm(unsigned seconds) { (void)seconds; return 0; }
static int mock_close(int fd) { (void)fd; return 0; }
static int mock_socket(int family, int type, int protocol) {
    (void)family; (void)type; (void)protocol; return mock_socket_error ? -1 : 12;
}
static int mock_connect(int fd, const struct sockaddr *address, size_t size) {
    (void)fd; (void)address; (void)size; return mock_connect_error;
}
static int mock_setsockopt(int fd, int level, int name, const void *value, size_t length) {
    (void)fd; (void)level; (void)name; (void)value; (void)length; return 0;
}
static int lookup(uint32_t service, uint32_t instance, struct sockaddr_qrtr *server) {
    (void)service; (void)instance; memset(server, 0, sizeof *server); return mock_lookup_error;
}
static ssize_t exchange(int fd, uint8_t *buf, size_t len, int timeout_ms, const char **failed);
#define socket mock_socket
#define connect mock_connect
#define setsockopt mock_setsockopt
#define close mock_close
#define usleep mock_sleep
#define alarm mock_alarm
