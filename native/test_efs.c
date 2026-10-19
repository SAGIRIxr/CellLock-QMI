/* SPDX-License-Identifier: GPL-3.0-only */
/* Buffer-only checks. Never loads libdiag or touches a device. */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "efs_protocol.inc"
int main(void) {
    unsigned char packet[128];char action[32];
    char *argv[]={"efs",action,"4294967295"};
    for(unsigned i=0;i<8;i++) {
        snprintf(action,sizeof action,"get-%u",i);size_t length=efs_command(2,argv,packet,sizeof packet);
        assert(length==15+strlen(EFS_PATHS[i])&&packet[0]==0x4b&&packet[1]==0x13&&packet[2]==39&&packet[3]==0);
        assert(packet[4]==(i%4<2?64:i%4==2?8:24)&&packet[8]==strlen(EFS_PATHS[i])+1&&packet[12]==0&&packet[13]==0);
        assert(!strcmp((char*)packet+14,EFS_PATHS[i])&&packet[length-1]==0);
    }
    for(unsigned i=0;i<17;i++) {
        snprintf(action,sizeof action,"unlink-%u",i);assert(efs_command(2,argv,packet,sizeof packet)==5+strlen(EFS_PATHS[i]));assert(packet[2]==8&&!strcmp((char*)packet+4,EFS_PATHS[i]));
    }
    strcpy(action,"unlink-17");assert(!efs_command(2,argv,packet,sizeof packet));
    for(unsigned i=0;i<18;i++){snprintf(action,sizeof action,"stat-%u",i);assert(efs_command(2,argv,packet,sizeof packet)==5+strlen(EFS_PATHS[i]));assert(packet[2]==15&&!strcmp((char*)packet+4,EFS_PATHS[i]));}
    strcpy(action,"put-lte-restrict");assert(efs_command(2,argv,packet,sizeof packet)==51+strlen(EFS_PATHS[17]));
    assert(packet[2]==38&&packet[4]==36&&packet[8]==0x40&&packet[10]==8&&packet[12]==1);
    for(unsigned i=14;i<50;i++)assert(packet[i]==0);
    assert(!strcmp((char*)packet+50,EFS_PATHS[17]));
    strcpy(action,"reload-once");assert(efs_command(2,argv,packet,sizeof packet)==4&&!memcmp(packet,"\x4b\x25\x03\0",4));
    strcpy(action,"sync");assert(efs_command(2,argv,packet,sizeof packet)==8&&packet[2]==48&&packet[4]==1&&packet[6]=='/');
    strcpy(action,"sync-status");assert(efs_command(3,argv,packet,sizeof packet)==12&&packet[2]==49&&packet[6]==255&&packet[9]==255&&packet[10]=='/');
    for(unsigned i=0;i<5;i++){const char *bad[]={"4294967296","-1","1;id","", "999999999999999999999"};argv[2]=(char*)bad[i];assert(!efs_command(3,argv,packet,sizeof packet));}
    strcpy(action,"get-18");assert(!efs_command(2,argv,packet,sizeof packet));strcpy(action,"put-0");assert(!efs_command(2,argv,packet,sizeof packet));
    assert(!efs_command(1,argv,packet,sizeof packet));assert(!efs_command(2,argv,packet,127));
    puts("PASS: EFS fixed reads/stat/unlinks, exact LTE PUT, one restart, sync and argument bounds; no hardware access");return 0;
}
