/* VeraDroid: zram and zsmalloc built into ONE module (zram.ko) for the Kindle 4.1.15 kernels that have neither
 * (CONFIG_ZRAM / CONFIG_ZSMALLOC unset: PW4 / KT4 / Oasis 2 / Oasis 3 firmware 5.14.1 - 5.15.1.1).
 * Force-included (-include) into drivers/block/zram/zram_drv.c and mm/zsmalloc.c, which are compiled unmodified from
 * Amazon's GPL tree of that firmware. Each file ends with its own module_init()/module_exit(); in a module both would
 * define init_module/cleanup_module. Here they become plain functions VD_INIT()/VD_EXIT() (named per file with -D),
 * called in order by vd_main.c: zsmalloc first, zram second; on unload the reverse. tools/kmod/build-zram41.sh */
#include <linux/init.h>          /* only init.h: module.h would pull in printk.h before the file's own pr_fmt */
#undef module_init
#undef module_exit
#define module_init(fn) int __init VD_INIT(void) { return fn(); }
#define module_exit(fn) void VD_EXIT(void) { fn(); }
/* the files' __exit functions stay in .text: vd_main.c's init also calls zsmalloc's exit when zram's init fails */
#undef __exit
#define __exit __used __cold notrace
