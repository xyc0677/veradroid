// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * vdblkctl: attach/detach a FAT32-hosted ext4 image file to /dev/vdblk0
 * via FIBMAP + loop backing-device offset translation, verifying first/last
 * blocks bit-for-bit before exposing the block device.
 *
 * Usage:
 *   vdblkctl --attach <image-file>    -> prints /dev/vdblk0 on stdout (rc 0)
 *   vdblkctl --detach [<image-file>]  -> detaches /dev/vdblk0 and drops stale vfat/loop cache
 */
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/fs.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#define VDBLK_MAX_EXT    4096
#define VDBLK_IOC_MAGIC  0xB7

struct vdblk_extent {
    uint64_t log_sec;
    uint64_t phys_sec;
    uint64_t len_sec;
};

struct vdblk_attach_req {
    char bdev_path[128];
    char hold_file[256];
    uint64_t total_sectors;
    uint32_t nr_extents;
    uint32_t flags;
    uint64_t extents_ptr;
};

#define VDBLK_IOC_ATTACH _IOW(VDBLK_IOC_MAGIC, 1, struct vdblk_attach_req)
#define VDBLK_IOC_DETACH _IO(VDBLK_IOC_MAGIC, 2)

static int read_first_line(const char *path, char *buf, size_t sz)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    ssize_t n = read(fd, buf, sz - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = '\0';
    buf[strcspn(buf, "\r\n")] = '\0';
    return 0;
}

static void drop_cache_path(const char *path)
{
    if (!path || !path[0]) return;
    int fd = open(path, O_RDONLY);
    if (fd < 0) return;
    (void)posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);
}

/* Find mount device for real_img, and resolve loopN -> raw bdev + byte offset */
static int resolve_backing(const char *real_img, char *mount_dev, size_t md_sz,
                           char *raw_bdev, size_t rb_sz, uint64_t *byte_off)
{
    FILE *f = fopen("/proc/mounts", "r");
    if (!f) return -1;
    char dev[256], mp[256], fstype[64];
    size_t best_len = 0;
    mount_dev[0] = '\0';
    char line[1024];
    while (fgets(line, sizeof(line), f)) {
        if (sscanf(line, "%255s %255s %63s", dev, mp, fstype) != 3) continue;
        size_t ml = strlen(mp);
        int match = (strcmp(mp, "/") == 0) ||
                    (strncmp(real_img, mp, ml) == 0 && (real_img[ml] == '/' || real_img[ml] == '\0'));
        if (match && ml >= best_len) {
            best_len = ml;
            snprintf(mount_dev, md_sz, "%s", dev);
        }
    }
    fclose(f);
    if (!mount_dev[0]) return -1;

    /* Check if mount_dev is a loop device (/dev/loopN or /dev/loop/N) */
    const char *base = strrchr(mount_dev, '/');
    base = base ? base + 1 : mount_dev;
    int loop_num = -1;
    if (strncmp(mount_dev, "/dev/loop/", 10) == 0) loop_num = atoi(mount_dev + 10);
    else if (strncmp(base, "loop", 4) == 0 && base[4] >= '0' && base[4] <= '9') loop_num = atoi(base + 4);

    if (loop_num >= 0) {
        char p_bf[128], p_off[128], off_str[64];
        snprintf(p_bf, sizeof(p_bf), "/sys/block/loop%d/loop/backing_file", loop_num);
        snprintf(p_off, sizeof(p_off), "/sys/block/loop%d/loop/offset", loop_num);
        if (read_first_line(p_bf, raw_bdev, rb_sz) < 0) return -1;
        if (read_first_line(p_off, off_str, sizeof(off_str)) < 0) *byte_off = 0;
        else *byte_off = strtoull(off_str, NULL, 10);
        struct stat st;
        if (stat(raw_bdev, &st) < 0 || !S_ISBLK(st.st_mode)) return -1;
        return 0;
    }

    struct stat st;
    if (stat(mount_dev, &st) == 0 && S_ISBLK(st.st_mode)) {
        snprintf(raw_bdev, rb_sz, "%s", mount_dev);
        *byte_off = 0;
        return 0;
    }
    return -1;
}

static int ensure_ctl_node(void)
{
    if (access("/dev/vdblk-ctl", F_OK) == 0) return 0;
    char devstr[64];
    if (read_first_line("/sys/class/misc/vdblk-ctl/dev", devstr, sizeof(devstr)) < 0) return -1;
    unsigned int ma = 0, mi = 0;
    if (sscanf(devstr, "%u:%u", &ma, &mi) != 2) return -1;
    (void)unlink("/dev/vdblk-ctl");
    return mknod("/dev/vdblk-ctl", S_IFCHR | 0600, makedev(ma, mi));
}

static int ensure_blk_node(void)
{
    for (int i = 0; i < 20; i++) {
        struct stat st;
        if (stat("/dev/vdblk0", &st) == 0 && S_ISBLK(st.st_mode)) return 0;
        char devstr[64];
        if (read_first_line("/sys/block/vdblk0/dev", devstr, sizeof(devstr)) == 0) {
            unsigned int ma = 0, mi = 0;
            if (sscanf(devstr, "%u:%u", &ma, &mi) == 2) {
                (void)unlink("/dev/vdblk0");
                if (mknod("/dev/vdblk0", S_IFBLK | 0600, makedev(ma, mi)) == 0) return 0;
            }
        }
        usleep(20000);
    }
    return -1;
}

static int fibmap_one(int fd, uint64_t blk, unsigned int *out_phys)
{
    if (blk > 0xffffffffULL) return -1;
    unsigned int v = (unsigned int)blk;
    if (ioctl(fd, FIBMAP, &v) < 0 || v == 0) return -1;
    *out_phys = v;
    return 0;
}

static int append_extent(struct vdblk_extent *ext, uint32_t *nr,
                         uint64_t ls, uint64_t ps, uint64_t len)
{
    if (*nr > 0) {
        struct vdblk_extent *prev = &ext[*nr - 1];
        if (prev->log_sec + prev->len_sec == ls && prev->phys_sec + prev->len_sec == ps) {
            prev->len_sec += len;
            return 0;
        }
    }
    if (*nr >= VDBLK_MAX_EXT) return -1;
    ext[(*nr)++] = (struct vdblk_extent){ ls, ps, len };
    return 0;
}

static uint64_t detect_fat_cluster_bytes(int rfd, uint64_t byte_off)
{
    unsigned char bpb[512];
    if (pread(rfd, bpb, sizeof(bpb), (off_t)byte_off) != (ssize_t)sizeof(bpb)) return 0;
    if (bpb[510] != 0x55 || bpb[511] != 0xaa) return 0;
    if (bpb[0] != 0xeb && bpb[0] != 0xe9) return 0;
    uint32_t bps = (uint32_t)bpb[11] | ((uint32_t)bpb[12] << 8);
    uint32_t spc = (uint32_t)bpb[13];
    if (bps < 512 || bps > 4096 || (bps & (bps - 1)) != 0) return 0;
    if (spc < 1 || spc > 128 || (spc & (spc - 1)) != 0) return 0;
    if (memcmp(bpb + 54, "FAT", 3) != 0 && memcmp(bpb + 82, "FAT32", 5) != 0) return 0;
    uint64_t cb = (uint64_t)bps * (uint64_t)spc;
    return cb <= 65536ULL ? cb : 0;
}

static int do_attach(const char *img_arg)
{
    char real_img[PATH_MAX];
    if (!realpath(img_arg, real_img)) { perror("realpath"); return 1; }
    if (ensure_ctl_node() < 0) return 2;

    char mount_dev[256], raw_bdev[128];
    uint64_t loop_off = 0;
    if (resolve_backing(real_img, mount_dev, sizeof(mount_dev), raw_bdev, sizeof(raw_bdev), &loop_off) < 0) {
        fprintf(stderr, "vdblkctl: cannot resolve backing block device for %s\n", real_img);
        return 2;
    }
    if ((loop_off & 511ULL) != 0) {
        fprintf(stderr, "vdblkctl: unaligned loop offset %llu\n", (unsigned long long)loop_off);
        return 2;
    }

    int fd = open(real_img, O_RDWR | O_LARGEFILE);
    if (fd < 0) { perror("open image"); return 1; }
    struct stat st;
    if (fstat(fd, &st) < 0 || st.st_size <= 0 || (st.st_size & 4095LL) != 0) {
        fprintf(stderr, "vdblkctl: invalid image size\n");
        close(fd);
        return 2;
    }
    int bsz = 0;
    if (ioctl(fd, FIGETBSZ, &bsz) < 0 || bsz < 512 || (bsz & 511) != 0) {
        perror("FIGETBSZ");
        close(fd);
        return 2;
    }

    /* Flush dirty vfat/loop pages before reading raw eMMC sectors */
    (void)fsync(fd);
    sync();
    (void)posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    if (strcmp(mount_dev, raw_bdev) != 0) drop_cache_path(mount_dev);

    int rfd = open(raw_bdev, O_RDONLY | O_LARGEFILE);
    if (rfd < 0) { perror("open raw_bdev"); close(fd); return 1; }
    uint64_t nblocks = (uint64_t)st.st_size / (uint64_t)bsz;
    uint64_t spb = (uint64_t)bsz / 512ULL;
    uint64_t base_sec = loop_off / 512ULL;
    /* Bound FIBMAP stride strictly to one verified FAT cluster (or 1 block when unknown) so probes never cross clusters */
    uint64_t cbytes = detect_fat_cluster_bytes(rfd, loop_off);
    uint64_t stride = (cbytes >= (uint64_t)bsz && (cbytes % (uint64_t)bsz) == 0) ? (cbytes / (uint64_t)bsz) : 1ULL;

    struct vdblk_extent *ext = calloc(VDBLK_MAX_EXT, sizeof(*ext));
    if (!ext) { close(rfd); close(fd); return 1; }
    uint32_t next = 0;

    uint64_t b = 0;
    while (b < nblocks) {
        unsigned int p0 = 0, p_end = 0;
        if (fibmap_one(fd, b, &p0) < 0) {
            fprintf(stderr, "vdblkctl: FIBMAP failed at block %llu\n", (unsigned long long)b);
            close(rfd); free(ext); close(fd); return 2;
        }
        if (b + stride <= nblocks && stride > 1 &&
            fibmap_one(fd, b + stride - 1, &p_end) == 0 &&
            p_end == p0 + (unsigned int)(stride - 1)) {
            if (append_extent(ext, &next, b * spb, base_sec + (uint64_t)p0 * spb, stride * spb) < 0) {
                fprintf(stderr, "vdblkctl: image too fragmented (>%d extents)\n", VDBLK_MAX_EXT);
                close(rfd); free(ext); close(fd); return 2;
            }
            b += stride;
        } else {
            if (append_extent(ext, &next, b * spb, base_sec + (uint64_t)p0 * spb, spb) < 0) {
                fprintf(stderr, "vdblkctl: image too fragmented (>%d extents)\n", VDBLK_MAX_EXT);
                close(rfd); free(ext); close(fd); return 2;
            }
            b++;
        }
    }

    /* Bit-for-bit verification of first & last 4KB blocks + ext4 superblock magic (0xef53) */
    (void)posix_fadvise(rfd, (off_t)(ext[0].phys_sec * 512ULL), 4096, POSIX_FADV_DONTNEED);
    unsigned char fbuf[4096], rbuf[4096];
    if (pread(fd, fbuf, 4096, 0) != 4096 ||
        pread(rfd, rbuf, 4096, (off_t)(ext[0].phys_sec * 512ULL)) != 4096 ||
        memcmp(fbuf, rbuf, 4096) != 0 ||
        rbuf[1080] != 0x53 || rbuf[1081] != 0xef) {
        fprintf(stderr, "vdblkctl: header/superblock verification failed!\n");
        close(rfd); free(ext); close(fd); return 3;
    }
    uint64_t last_pg_off = (uint64_t)st.st_size - 4096ULL;
    uint64_t last_ls = last_pg_off / 512ULL;
    const struct vdblk_extent *le = &ext[next - 1];
    uint64_t last_ps = le->phys_sec + (last_ls - le->log_sec);
    (void)posix_fadvise(rfd, (off_t)(last_ps * 512ULL), 4096, POSIX_FADV_DONTNEED);
    if (pread(fd, fbuf, 4096, (off_t)last_pg_off) != 4096 ||
        pread(rfd, rbuf, 4096, (off_t)(last_ps * 512ULL)) != 4096 ||
        memcmp(fbuf, rbuf, 4096) != 0) {
        fprintf(stderr, "vdblkctl: tail block verification failed!\n");
        close(rfd); free(ext); close(fd); return 3;
    }
    close(rfd);
    (void)posix_fadvise(fd, 0, 0, POSIX_FADV_DONTNEED);
    close(fd);

    int cfd = open("/dev/vdblk-ctl", O_RDWR);
    if (cfd < 0) { perror("open /dev/vdblk-ctl"); free(ext); return 2; }
    struct vdblk_attach_req req;
    memset(&req, 0, sizeof(req));
    snprintf(req.bdev_path, sizeof(req.bdev_path), "%s", raw_bdev);
    if (strlen(real_img) >= sizeof(req.hold_file)) { close(cfd); free(ext); return 2; }
    memcpy(req.hold_file, real_img, strlen(real_img) + 1);
    req.total_sectors = (uint64_t)st.st_size / 512ULL;
    req.nr_extents = next;
    req.flags = 0;
    req.extents_ptr = (uint64_t)(uintptr_t)ext;

    if (ioctl(cfd, VDBLK_IOC_ATTACH, &req) < 0) {
        perror("VDBLK_IOC_ATTACH");
        close(cfd); free(ext); return 1;
    }
    close(cfd);
    free(ext);

    if (ensure_blk_node() < 0) {
        fprintf(stderr, "vdblkctl: /dev/vdblk0 did not appear\n");
        return 1;
    }
    puts("/dev/vdblk0");
    return 0;
}

static int do_detach(const char *img_arg)
{
    if (ensure_ctl_node() < 0) return 0;
    int cfd = open("/dev/vdblk-ctl", O_RDWR);
    if (cfd < 0) return 0;
    if (ioctl(cfd, VDBLK_IOC_DETACH) < 0) {
        perror("VDBLK_IOC_DETACH");
        close(cfd);
        return 1;
    }
    close(cfd);
    (void)unlink("/dev/vdblk0");
    if (img_arg && img_arg[0]) {
        char real_img[PATH_MAX], mount_dev[256], raw_bdev[128];
        uint64_t off = 0;
        if (realpath(img_arg, real_img)) {
            drop_cache_path(real_img);
            if (resolve_backing(real_img, mount_dev, sizeof(mount_dev), raw_bdev, sizeof(raw_bdev), &off) == 0 &&
                strcmp(mount_dev, raw_bdev) != 0) {
                drop_cache_path(mount_dev);
            }
        }
    }
    return 0;
}

int main(int argc, char **argv)
{
    if (argc >= 3 && strcmp(argv[1], "--attach") == 0) return do_attach(argv[2]);
    if (argc >= 2 && strcmp(argv[1], "--detach") == 0) return do_detach(argc >= 3 ? argv[2] : NULL);
    fprintf(stderr, "usage: %s --attach <img_file> | --detach [img_file]\n", argv[0]);
    return 1;
}