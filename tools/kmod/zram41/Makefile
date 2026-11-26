# out-of-tree: zram_drv.c zcomp.c zcomp_lzo.c (drivers/block/zram) + zsmalloc.c (mm) copied from Amazon's tree by build-zram41.sh
obj-m := zram.o
zram-y := zcomp_lzo.o zcomp.o zram_drv.o zsmalloc.o vd_main.o
ccflags-y := -fno-pic -I$(src)
CFLAGS_zram_drv.o := -include $(src)/vd_modinit.h -DVD_INIT=vd_zram_init -DVD_EXIT=vd_zram_exit
CFLAGS_zsmalloc.o := -include $(src)/vd_modinit.h -DVD_INIT=vd_zs_init -DVD_EXIT=vd_zs_exit
