/* SPDX-License-Identifier: GPL-3.0-only */
/* Buffer-only test: never initializes DCI or opens a modem interface. */
#define main diag_program_main
#include "celllock_diag.c"
#undef main
#include <assert.h>

int main(void) {
    uint8_t packet[65535] = {0};
    packet[0] = 16; packet[2] = 0x7f; packet[3] = 0xb9;
    on_log(packet, 15);
    assert(!retained);
    for (int i = 0; i < 40; i++) {
        packet[15] = (uint8_t)i;
        on_log(packet, 16);
    }
    assert(packets == 40 && retained == 32);
    assert(captured[17] == 8 && captured[captured_size - 1] == 39);
    packet[0] = 255; packet[1] = 255;
    packet[65534] = 42;
    on_log(packet, 65535);
    packet[65534] = 43;
    on_log(packet, 65535);
    assert(retained == 1 && captured_size == 65537);
    assert(captured[captured_size - 1] == 43);
    size_t before = captured_size;
    packet[2] = 0;
    on_log(packet, 65535);
    assert(captured_size == before);
    assert(2 * captured_size + 32 * 4 + 128 < 256 * 1024);
    puts("PASS: diagnostic buffer bounds, eviction, latest packet, invalid header");
    return 0;
}
