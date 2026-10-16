/* SPDX-License-Identifier: GPL-3.0-only */
/* Fixed paths only. No arbitrary EFS access is exposed. */
static const char *const EFS_BAND_PATHS[] = {
    "/nv/item_files/modem/mmode/nr_band_pref",
    "/nv/item_files/modem/mmode/nr_nsa_band_pref",
    "/nv/item_files/modem/mmode/lte_bandpref",
    "/nv/item_files/modem/mmode/lte_bandpref_extn_65_256",
    "/nv/item_files/modem/mmode/nr_band_pref_Subscription01",
    "/nv/item_files/modem/mmode/nr_nsa_band_pref_Subscription01",
    "/nv/item_files/modem/mmode/lte_bandpref_Subscription01",
    "/nv/item_files/modem/mmode/lte_bandpref_extn_65_256_Subscription01"
};
static size_t efs_command(int argc, char **argv, unsigned char *packet, size_t capacity) {
    if (capacity < 128 || argc < 2) return 0;
    memset(packet, 0, capacity);packet[0]=0x4b;packet[1]=0x13;
    if (argc==2 && strlen(argv[1])==5 && !strncmp(argv[1],"get-",4) && argv[1][4]>='0' && argv[1][4]<='7') {
        unsigned index=argv[1][4]-'0';const char *path=EFS_BAND_PATHS[index];
        packet[2]=39;packet[4]=index%4<2?64:index%4==2?8:24;packet[8]=strlen(path)+1;
        memcpy(packet+14,path,strlen(path)+1);return 14+strlen(path)+1;
    }
    if (argc==2 && strlen(argv[1])==8 && !strncmp(argv[1],"unlink-",7) && argv[1][7]>='0' && argv[1][7]<='3') {
        const char *path=EFS_BAND_PATHS[argv[1][7]-'0'];packet[2]=8;memcpy(packet+4,path,strlen(path)+1);return 4+strlen(path)+1;
    }
    if (argc==2 && !strcmp(argv[1],"sync")) {packet[2]=48;packet[4]=1;packet[6]='/';return 8;}
    if (argc==3 && !strcmp(argv[1],"sync-status")) {
        const char *token=argv[2];if(!*token || strlen(token)>10 || strspn(token,"0123456789")!=strlen(token))return 0;
        uint64_t value=0;for(;*token;++token)value=value*10+(unsigned)(*token-'0');if(value>UINT32_MAX)return 0;
        packet[2]=49;packet[4]=1;for(int i=0;i<4;i++)packet[6+i]=(unsigned char)(value>>(i*8));packet[10]='/';return 12;
    }
    return 0;
}
