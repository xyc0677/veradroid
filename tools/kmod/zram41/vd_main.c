/* VeraDroid: entry points of zram.ko = zsmalloc + zram (see vd_modinit.h, tools/kmod/build-zram41.sh). GPL-2.0 like the
 * kernel sources it glues together. */
#include <linux/init.h>
#include <linux/module.h>

int vd_zs_init(void);
void vd_zs_exit(void);
int vd_zram_init(void);
void vd_zram_exit(void);

static int __init vd_init(void)
{
	int r = vd_zs_init();

	if (r)
		return r;
	r = vd_zram_init();
	if (r)
		vd_zs_exit();	/* unregisters zsmalloc's CPU notifier before the module is freed */
	return r;
}

static void __exit vd_exit(void)
{
	vd_zram_exit();
	vd_zs_exit();
}

module_init(vd_init);
module_exit(vd_exit);
MODULE_LICENSE("Dual BSD/GPL");
MODULE_DESCRIPTION("zram with zsmalloc built in (Amazon Kindle 4.1.15 GPL source, unmodified)");
