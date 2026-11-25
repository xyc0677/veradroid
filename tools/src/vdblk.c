// SPDX-License-Identifier: GPL-2.0
/*
 * vdblk: zero-page-cache linear extent remapper for VeraDroid on Linux 4.1.x.
 * Maps a contiguous or multi-extent image file directly onto the underlying
 * eMMC partition block device, bypassing double buffered loop (loop1 -> vfat -> loop0)
 * on kernels < 4.4 that lack LOOP_SET_DIRECT_IO.
 */
#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/init.h>
#include <linux/capability.h>
#include <linux/fs.h>
#include <linux/blkdev.h>
#include <linux/bio.h>
#include <linux/genhd.h>
#include <linux/miscdevice.h>
#include <linux/mutex.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/uaccess.h>
#include <linux/atomic.h>

#define VDBLK_NAME       "vdblk"
#define VDBLK_CTL_NAME   "vdblk-ctl"
#define VDBLK_MAX_EXT    4096

struct vdblk_extent {
	__u64 log_sec;
	__u64 phys_sec;
	__u64 len_sec;
};

struct vdblk_attach_req {
	char bdev_path[128];
	char hold_file[256];
	__u64 total_sectors;
	__u32 nr_extents;
	__u32 flags;          /* bit 0: read-only */
	__u64 extents_ptr;
};

#define VDBLK_IOC_MAGIC  0xB7
#define VDBLK_IOC_ATTACH _IOW(VDBLK_IOC_MAGIC, 1, struct vdblk_attach_req)
#define VDBLK_IOC_DETACH _IO(VDBLK_IOC_MAGIC, 2)

struct vdblk_dev {
	struct mutex lock;
	bool attached;
	bool read_only;
	int major;
	atomic_t open_cnt;
	struct block_device *bdev;
	struct file *hold_filp;
	struct request_queue *queue;
	struct gendisk *disk;
	u64 total_sectors;
	u32 nr_extents;
	struct vdblk_extent *extents;
};

static struct vdblk_dev g_vd;

static const struct vdblk_extent *vdblk_find_extent(const struct vdblk_dev *vd, sector_t sec)
{
	u32 lo = 0, hi = vd->nr_extents - 1;

	if (likely(vd->nr_extents == 1))
		return &vd->extents[0];

	while (lo <= hi) {
		u32 mid = (lo + hi) >> 1;
		const struct vdblk_extent *e = &vd->extents[mid];
		if (sec < e->log_sec) {
			if (mid == 0)
				break;
			hi = mid - 1;
		} else if (sec >= e->log_sec + e->len_sec) {
			lo = mid + 1;
		} else {
			return e;
		}
	}
	return NULL;
}

static void vdblk_make_request(struct request_queue *q, struct bio *bio)
{
	struct vdblk_dev *vd = q->queuedata;

	if (unlikely(!vd || !vd->attached)) {
		bio_io_error(bio);
		return;
	}
	if (unlikely(vd->read_only && bio_data_dir(bio) == WRITE)) {
		bio_io_error(bio);
		return;
	}
	if (unlikely(bio->bi_rw & REQ_DISCARD)) {
		bio_endio(bio, -EOPNOTSUPP);
		return;
	}
	/* Empty cache-flush bio: forward straight to the backing block device */
	if (unlikely(bio->bi_iter.bi_size == 0)) {
		bio->bi_bdev = vd->bdev;
		bio->bi_iter.bi_sector = vd->extents[0].phys_sec;
		generic_make_request(bio);
		return;
	}

	for (;;) {
		sector_t sec = bio->bi_iter.bi_sector;
		unsigned int nr = bio_sectors(bio);
		const struct vdblk_extent *ext;
		u64 off, avail;
		struct bio *split;

		if (unlikely((u64)sec + nr > vd->total_sectors)) {
			bio_io_error(bio);
			return;
		}
		ext = vdblk_find_extent(vd, sec);
		if (unlikely(!ext)) {
			bio_io_error(bio);
			return;
		}
		off = (u64)sec - ext->log_sec;
		avail = ext->len_sec - off;

		if (likely(nr <= avail)) {
			bio->bi_bdev = vd->bdev;
			bio->bi_iter.bi_sector = (sector_t)(ext->phys_sec + off);
			generic_make_request(bio);
			return;
		}

		split = bio_split(bio, (int)avail, GFP_NOIO, fs_bio_set);
		if (unlikely(!split)) {
			bio_io_error(bio);
			return;
		}
		bio_chain(split, bio);
		split->bi_bdev = vd->bdev;
		split->bi_iter.bi_sector = (sector_t)(ext->phys_sec + off);
		generic_make_request(split);
	}
}

static int vdblk_blk_open(struct block_device *bdev, fmode_t mode)
{
	int ret = 0;
	mutex_lock(&g_vd.lock);
	if (!g_vd.attached) {
		ret = -ENXIO;
	} else if ((mode & FMODE_WRITE) && g_vd.read_only) {
		ret = -EROFS;
	} else {
		atomic_inc(&g_vd.open_cnt);
	}
	mutex_unlock(&g_vd.lock);
	return ret;
}

static void vdblk_blk_release(struct gendisk *disk, fmode_t mode)
{
	(void)disk;
	(void)mode;
	atomic_dec(&g_vd.open_cnt);
}

static const struct block_device_operations vdblk_fops = {
	.owner   = THIS_MODULE,
	.open    = vdblk_blk_open,
	.release = vdblk_blk_release,
};

static int vdblk_do_attach(const struct vdblk_attach_req *req)
{
	struct vdblk_extent *exts = NULL;
	struct block_device *bdev = NULL;
	struct file *filp = NULL;
	struct request_queue *q = NULL, *bq;
	struct gendisk *disk = NULL;
	fmode_t bmode;
	u64 cur_log = 0, bdev_secs;
	u32 i;
	size_t extsz;
	int ret;

	if (req->nr_extents == 0 || req->nr_extents > VDBLK_MAX_EXT || req->total_sectors == 0)
		return -EINVAL;

	extsz = (size_t)req->nr_extents * sizeof(*exts);
	exts = vmalloc(extsz);
	if (!exts)
		return -ENOMEM;

	if (copy_from_user(exts, (const void __user *)(unsigned long)req->extents_ptr, extsz)) {
		ret = -EFAULT;
		goto err_exts;
	}

	bmode = FMODE_READ | ((req->flags & 1) ? 0 : FMODE_WRITE);
	bdev = blkdev_get_by_path(req->bdev_path, bmode, NULL);
	if (IS_ERR(bdev)) {
		ret = PTR_ERR(bdev);
		bdev = NULL;
		goto err_exts;
	}
	bdev_secs = (u64)(i_size_read(bdev->bd_inode) >> 9);

	for (i = 0; i < req->nr_extents; i++) {
		if (exts[i].log_sec != cur_log || exts[i].len_sec == 0) {
			ret = -EINVAL;
			goto err_bdev;
		}
		if (exts[i].phys_sec + exts[i].len_sec < exts[i].phys_sec ||
		    exts[i].phys_sec + exts[i].len_sec > bdev_secs) {
			ret = -ERANGE;
			goto err_bdev;
		}
		cur_log += exts[i].len_sec;
	}
	if (cur_log != req->total_sectors) {
		ret = -EINVAL;
		goto err_bdev;
	}

	if (req->hold_file[0]) {
		int oflags = ((req->flags & 1) ? O_RDONLY : O_RDWR) | O_LARGEFILE;
		filp = filp_open(req->hold_file, oflags, 0);
		if (IS_ERR(filp)) {
			ret = PTR_ERR(filp);
			filp = NULL;
			goto err_bdev;
		}
	}

	q = blk_alloc_queue(GFP_KERNEL);
	if (!q) {
		ret = -ENOMEM;
		goto err_filp;
	}
	blk_queue_make_request(q, vdblk_make_request);
	bq = bdev_get_queue(bdev);
	if (bq) {
		blk_stack_limits(&q->limits, &bq->limits, 0);
		blk_queue_flush(q, bq->flush_flags & (REQ_FLUSH | REQ_FUA));
	}
	queue_flag_set_unlocked(QUEUE_FLAG_NONROT, q);
	queue_flag_clear_unlocked(QUEUE_FLAG_ADD_RANDOM, q);

	disk = alloc_disk(1);
	if (!disk) {
		ret = -ENOMEM;
		goto err_queue;
	}

	g_vd.bdev = bdev;
	g_vd.hold_filp = filp;
	g_vd.queue = q;
	g_vd.disk = disk;
	g_vd.total_sectors = req->total_sectors;
	g_vd.nr_extents = req->nr_extents;
	g_vd.extents = exts;
	g_vd.read_only = (req->flags & 1) != 0;
	atomic_set(&g_vd.open_cnt, 0);
	q->queuedata = &g_vd;

	disk->major = g_vd.major;
	disk->first_minor = 0;
	disk->fops = &vdblk_fops;
	disk->queue = q;
	disk->private_data = &g_vd;
	disk->flags = GENHD_FL_NO_PART_SCAN;
	snprintf(disk->disk_name, sizeof(disk->disk_name), "vdblk0");
	set_capacity(disk, (sector_t)req->total_sectors);
	set_disk_ro(disk, g_vd.read_only ? 1 : 0);

	g_vd.attached = true;
	add_disk(disk);
	pr_info("vdblk0: attached %llu sectors (%u extents) -> %s\n",
		(unsigned long long)req->total_sectors, req->nr_extents, req->bdev_path);
	return 0;

err_queue:
	blk_cleanup_queue(q);
err_filp:
	if (filp)
		filp_close(filp, NULL);
err_bdev:
	blkdev_put(bdev, bmode);
err_exts:
	vfree(exts);
	return ret;
}

static int vdblk_do_detach(void)
{
	fmode_t bmode;

	if (!g_vd.attached)
		return 0;
	if (atomic_read(&g_vd.open_cnt) > 0)
		return -EBUSY;

	g_vd.attached = false;
	del_gendisk(g_vd.disk);
	blk_cleanup_queue(g_vd.queue);
	put_disk(g_vd.disk);

	bmode = FMODE_READ | (g_vd.read_only ? 0 : FMODE_WRITE);
	blkdev_put(g_vd.bdev, bmode);
	if (g_vd.hold_filp)
		filp_close(g_vd.hold_filp, NULL);
	vfree(g_vd.extents);

	g_vd.disk = NULL;
	g_vd.queue = NULL;
	g_vd.bdev = NULL;
	g_vd.hold_filp = NULL;
	g_vd.extents = NULL;
	g_vd.nr_extents = 0;
	g_vd.total_sectors = 0;
	pr_info("vdblk0: detached\n");
	return 0;
}

static long vdblk_ctl_ioctl(struct file *file, unsigned int cmd, unsigned long arg)
{
	int ret;
	(void)file;
	if (!capable(CAP_SYS_ADMIN))
		return -EPERM;

	switch (cmd) {
	case VDBLK_IOC_ATTACH: {
		struct vdblk_attach_req req;
		if (copy_from_user(&req, (const void __user *)arg, sizeof(req)))
			return -EFAULT;
		req.bdev_path[sizeof(req.bdev_path) - 1] = '\0';
		req.hold_file[sizeof(req.hold_file) - 1] = '\0';
		mutex_lock(&g_vd.lock);
		if (g_vd.attached)
			ret = -EBUSY;
		else
			ret = vdblk_do_attach(&req);
		mutex_unlock(&g_vd.lock);
		return ret;
	}
	case VDBLK_IOC_DETACH:
		mutex_lock(&g_vd.lock);
		ret = vdblk_do_detach();
		mutex_unlock(&g_vd.lock);
		return ret;
	default:
		return -ENOTTY;
	}
}

static const struct file_operations vdblk_ctl_fops = {
	.owner          = THIS_MODULE,
	.unlocked_ioctl = vdblk_ctl_ioctl,
};

static struct miscdevice vdblk_misc = {
	.minor = MISC_DYNAMIC_MINOR,
	.name  = VDBLK_CTL_NAME,
	.fops  = &vdblk_ctl_fops,
	.mode  = 0600,
};

static int __init vdblk_init(void)
{
	int ret;
	mutex_init(&g_vd.lock);
	g_vd.major = register_blkdev(0, VDBLK_NAME);
	if (g_vd.major < 0)
		return g_vd.major;
	ret = misc_register(&vdblk_misc);
	if (ret < 0) {
		unregister_blkdev(g_vd.major, VDBLK_NAME);
		return ret;
	}
	pr_info("vdblk: loaded (major %d)\n", g_vd.major);
	return 0;
}

static void __exit vdblk_exit(void)
{
	mutex_lock(&g_vd.lock);
	if (g_vd.attached && atomic_read(&g_vd.open_cnt) == 0)
		vdblk_do_detach();
	mutex_unlock(&g_vd.lock);
	misc_deregister(&vdblk_misc);
	unregister_blkdev(g_vd.major, VDBLK_NAME);
}

module_init(vdblk_init);
module_exit(vdblk_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("VeraDroid zero-cache direct block extent mapper");