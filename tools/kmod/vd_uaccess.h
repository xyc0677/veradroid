/*
 * VeraDroid: user-memory access for out-of-tree modules on Kindle vendor kernels.
 *
 * put_user()/get_user() on ARM are inline asm calls into __put_user_N/__get_user_N helpers with a
 * register calling convention that Amazon's kernels changed (seen on the PW3 3.0.35 kernel: put_user
 * to a valid stack address fails with EFAULT although addr_limit is right). copy_to_user()/
 * copy_from_user() go through plain C functions, so route every single-value access through them.
 * Included by binder_main.c right after binder.h.
 */
#ifndef VD_UACCESS_H
#define VD_UACCESS_H
#include <linux/uaccess.h>
#undef put_user
#undef get_user
#undef __put_user
#undef __get_user
#define put_user(x, p) ({ \
	__typeof__(*(p)) __vdv = (__typeof__(*(p)))(x); \
	copy_to_user((p), &__vdv, sizeof(__vdv)) ? -EFAULT : 0; })
#define get_user(x, p) ({ \
	__typeof__(*(p)) __vdv; \
	int __vdr = copy_from_user(&__vdv, (p), sizeof(__vdv)) ? -EFAULT : 0; \
	(x) = __vdr ? (__typeof__(*(p)))0 : __vdv; __vdr; })
#define __put_user(x, p) put_user(x, p)
#define __get_user(x, p) get_user(x, p)
#endif
