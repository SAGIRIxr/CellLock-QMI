/* SPDX-License-Identifier: GPL-3.0-only */
/* Pure redaction check: never calls the native program or a modem interface. */
#define main read_program_main
#include "celllock_read.c"
#undef main
#include <assert.h>

int main(void) {
    uint8_t lte[29], nr[22], uim[14] = {1,2,0,0,0,1,0,0,0,1,3,0x12,0x34,0x56};
    memset(lte, 0x12, sizeof lte); memset(nr, 0x34, sizeof nr);
    redact(3, 0x43, 0x13, lte, sizeof lte);
    redact(3, 0x43, 0x2f, nr, sizeof nr);
    for (unsigned i=0; i<sizeof lte; i++) assert(lte[i]==0x12);
    for (unsigned i=0; i<sizeof nr; i++) assert(nr[i]==0x34);
    redact(11, 0x47, 0x10, uim, sizeof uim);
    assert(uim[11]==0 && uim[12]==0 && uim[13]==0 && uim[1]==2 && uim[5]==1);
    uim[10]=255; uim[11]=0x12;
    redact(11, 0x47, 0x10, uim, sizeof uim);
    assert(uim[11]==0x12);
    puts("PASS: NAS identities retained; ICCID redacted; truncated UIM bounded");
    return 0;
}
