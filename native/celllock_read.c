/* SPDX-License-Identifier: GPL-3.0-only */
#include "qrtr_transport.inc"
#include <stdlib.h>

/* Fixed read-only commands; NAS bind changes only this client's subscription. */
static uint16_t transaction;
static uint8_t packet[QMI_MAX];
static int64_t elapsed_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_BOOTTIME,&ts);
    return (int64_t)ts.tv_sec*1000+ts.tv_nsec/1000000;
}
static void put16(uint8_t *p, unsigned value) { p[0]=value; p[1]=value>>8; }
static int allowed(unsigned service, unsigned id) {
    return (service==11 && (id==0x2f || id==0x47)) ||
        (service==3 && (id==0x45 || id==0x43));
}
static int connect_service(unsigned service) {
    struct sockaddr_qrtr server;
    int result=lookup(service,0,&server);
    if(result<0) return result;
    int fd=socket(AF_QIPCRTR,SOCK_DGRAM|SOCK_CLOEXEC,0);
    if(fd<0) return -errno;
    if(connect(fd,(struct sockaddr *)&server,sizeof server)<0) { result=-errno; close(fd); return result; }
    return fd;
}
static void redact(unsigned service, unsigned message, unsigned type, uint8_t *data, unsigned length) {
    if(service==11 && message==0x47 && type==0x10 && length) {
        unsigned at=1;
        for(unsigned i=0;i<data[0];i++) {
            if(at+10>length) return;
            unsigned size=data[at+9]; at+=10;
            if(size>length-at) return;
            memset(data+at,0,size); at+=size;
        }
    }
}
static int request(int fd,unsigned service,unsigned id,const uint8_t *body,unsigned length,int slot) {
    if(!allowed(service,id)||length>4) return -1;
    packet[0]=0; put16(packet+1,++transaction); put16(packet+3,id); put16(packet+5,length);
    if(length) memcpy(packet+7,body,length);
    const char *failed=NULL;
    ssize_t size=exchange(fd,packet,7+length,2000,&failed);
    int result=-1,error=-1;
    if(size>=7 && size==7+le16(packet+5)) {
        size_t at=7;
        while(at+3<=(size_t)size) {
            unsigned count=le16(packet+at+1);
            if(count>(size_t)size-at-3) break;
            if(packet[at]==2 && count==4) {result=le16(packet+at+3);error=le16(packet+at+5);}
            at+=3+count;
        }
        if(at!=(size_t)size) size=-EPROTO;
    } else if(size>=0) size=-EPROTO;
    printf("{\"service\":%u,\"message\":%u,\"slot\":%d,\"transport\":%d,\"result\":%d,\"error\":%d,\"elapsed\":%lld,\"tlvs\":{",
        service,id,slot,size<0?(int)-size:0,result,error,(long long)elapsed_ms());
    int first=1;
    if(size>=7) for(size_t at=7;at+3<=(size_t)size;) {
        unsigned type=packet[at],count=le16(packet+at+1); uint8_t *data=packet+at+3;
        /* Only known TLVs; retain network/cell identity, never expose ICCID. */
        int keep=type==2 || (service==11 && type==0x10) ||
            (service==3 && id==0x43 && (type==0x13||type==0x14||type==0x27||type==0x28||type==0x2e||type==0x2f));
        if(keep) {
            redact(service,id,type,data,count);
            printf("%s\"%02x\":\"",first?"":",",type); first=0;
            for(unsigned i=0;i<count;i++) printf("%02x",data[i]);
            printf("\"");
        }
        at+=3+count;
    }
    printf("}}\n"); fflush(stdout);
    return size>=7 && result==0 && error==0 ? 0:-1;
}
int main(int argc,char **argv) {
    if(!((argc==2 && !strcmp(argv[1],"sims")) || (argc==3 && !strcmp(argv[1],"cells")
            && (argv[2][0]=='0' || argv[2][0]=='1') && !argv[2][1]))) {
        fprintf(stderr,"usage: celllock_read sims | cells 0|1\n");return 2;
    }
    setbuf(stdout,NULL);
    if(!strcmp(argv[1],"sims")) {
        int uim=connect_service(11);
        if(uim>=0) { request(uim,11,0x2f,NULL,0,-1);request(uim,11,0x47,NULL,0,-1);close(uim); }
        else printf("{\"service\":11,\"lookupError\":%d}\n",-uim);
        return 0;
    }
    int slot=argv[2][0]-'0';
    {
        int nas=connect_service(3);
        if(nas<0) {printf("{\"service\":3,\"slot\":%d,\"lookupError\":%d}\n",slot,-nas);return 1;}
        uint8_t bind[]={1,1,0,(uint8_t)slot};
        if(request(nas,3,0x45,bind,sizeof bind,slot)==0) {
            request(nas,3,0x43,NULL,0,slot);
        }
        close(nas);
    }
    return 0;
}
