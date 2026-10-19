/* SPDX-License-Identifier: GPL-3.0-only */
/* Fixed paths only. No arbitrary EFS access is exposed. */
static const char *const EFS_PATHS[] = {
    "/nv/item_files/modem/mmode/nr_band_pref",
    "/nv/item_files/modem/mmode/nr_nsa_band_pref",
    "/nv/item_files/modem/mmode/lte_bandpref",
    "/nv/item_files/modem/mmode/lte_bandpref_extn_65_256",
    "/nv/item_files/modem/mmode/nr_band_pref_Subscription01",
    "/nv/item_files/modem/mmode/nr_nsa_band_pref_Subscription01",
    "/nv/item_files/modem/mmode/lte_bandpref_Subscription01",
    "/nv/item_files/modem/mmode/lte_bandpref_extn_65_256_Subscription01",
    "/nv/item_files/modem/mmode/mode_pref",
    "/nv/item_files/modem/mmode/mode_pref_Subscription01",
    "/nv/item_files/modem/lte/rrc/csp/pci_lock",
    "/nv/item_files/modem/nr5g/RRC/pci_lock_info",
    "/nv/item_files/modem/nr5g/RRC/earfcn_lock",
    "/nv/item_files/modem/nr5g/RRC/earfcn_lock_Subscription01",
    "/nv/item_files/modem/geran/rr_efs_arfcn_list",
    "/nv/item_files/wcdma/rrc/wcdma_rrc_freq_lock_item",
    "/nv/item_files/wcdma/rrc/wcdma_rrc_enable_psc_lock_Subscription01",
    "/nv/item_files/modem/lte/rrc/efs/cell_restrict_opt_params"
};
static size_t efs_command(int argc, char **argv, unsigned char *packet, size_t capacity) {
    if (capacity < 128 || argc < 2) return 0;
    memset(packet, 0, capacity);packet[0]=0x4b;packet[1]=0x13;
    if (argc==2 && !strcmp(argv[1],"reload-once")) {packet[1]=0x25;packet[2]=3;return 4;}
    if (argc==2 && !strcmp(argv[1],"put-lte-restrict")) {
        const char *path=EFS_PATHS[17];
        size_t length=50+strlen(path)+1;if(length>capacity)return 0;
        packet[2]=38;packet[4]=36;packet[8]=0x40;packet[10]=8;packet[12]=1;
        memcpy(packet+50,path,strlen(path)+1);return length;
    }
    if (argc==2) {
        const char *dash=strchr(argv[1],'-');
        if(dash && dash[1] && strspn(dash+1,"0123456789")==strlen(dash+1) && strlen(dash+1)<=2
                && !(dash[1]=='0' && dash[2])) {
            unsigned index=(unsigned)strtoul(dash+1,NULL,10);
            if(index>=sizeof EFS_PATHS/sizeof EFS_PATHS[0])return 0;
            const char *path=EFS_PATHS[index];
            if(!strncmp(argv[1],"get-",4) && dash==argv[1]+3) {
                unsigned length=index<8?(index%4<2?64:index%4==2?8:24):index<10?4:index==17?36:128;
                packet[2]=39;packet[4]=(unsigned char)length;packet[8]=(unsigned char)(strlen(path)+1);
                if(15+strlen(path)>capacity)return 0;
                memcpy(packet+14,path,strlen(path)+1);return 15+strlen(path);
            }
            if((!strncmp(argv[1],"unlink-",7) && dash==argv[1]+6 && index!=17)
                    || (!strncmp(argv[1],"stat-",5) && dash==argv[1]+4)) {
                if(5+strlen(path)>capacity)return 0;
                packet[2]=argv[1][0]=='u'?8:15;memcpy(packet+4,path,strlen(path)+1);return 5+strlen(path);
            }
        }
    }
    if (argc==2 && !strcmp(argv[1],"sync")) {packet[2]=48;packet[4]=1;packet[6]='/';return 8;}
    if (argc==3 && !strcmp(argv[1],"sync-status")) {
        const char *token=argv[2];if(!*token || strlen(token)>10 || strspn(token,"0123456789")!=strlen(token))return 0;
        uint64_t value=0;for(;*token;++token)value=value*10+(unsigned)(*token-'0');if(value>UINT32_MAX)return 0;
        packet[2]=49;packet[4]=1;for(int i=0;i<4;i++)packet[6+i]=(unsigned char)(value>>(i*8));packet[10]='/';return 12;
    }
    return 0;
}
