/*
 * VeraDroid: ashmem on top of memfd_create.
 *
 * The Kindle kernel (5.15) has no /dev/ashmem. memfd_create gives the same thing Android needs from ashmem:
 * an anonymous, fd-passable, mmap-able shared memory region. Pin/unpin (purgeable regions) are not
 * supported; they always report "not purged", which every user in AOSP 7.1 handles.
 *
 * Original file: Copyright (C) 2008 The Android Open Source Project, Apache License 2.0.
 */
#define LOG_TAG "ashmem"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#include <cutils/ashmem.h>
#include <log/log.h>

#ifndef __NR_memfd_create
#define __NR_memfd_create 385   /* ARM EABI */
#endif
#define VD_MFD_CLOEXEC 0x0001U

/*
 * ashmem_create_region - creates a new shared memory region and returns the file
 * descriptor, or <0 on error
 *
 * `name' is an optional label (visible in /proc/pid/maps as /memfd:ashmem-<name>)
 * `size' is the size of the region, in page-aligned bytes
 */
/*
 * Kernels older than 3.17 (Kindle PW2/PW3 and friends run 3.0.35) have no memfd_create.
 * Fall back to an unlinked file on a tmpfs the host prepares (/dev/vdshm, mode 1777):
 * once unlinked it is exactly an anonymous, fd-passable, mmap-able shared region.
 */
static int vd_tmpfs_region(void)
{
    static unsigned int seq;
    char path[64];
    for (int i = 0; i < 16; i++) {
        snprintf(path, sizeof(path), "/dev/vdshm/a%d-%u-%d", getpid(), __sync_fetch_and_add(&seq, 1), i);
        int fd = TEMP_FAILURE_RETRY(open(path, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600));
        if (fd >= 0) {
            unlink(path);
            return fd;
        }
        if (errno != EEXIST) return -1;
    }
    return -1;
}

int ashmem_create_region(const char *name, size_t size)
{
    static int no_memfd;
    char buf[64];
    int fd = -1;
    snprintf(buf, sizeof(buf), "ashmem-%s", name ? name : "anon");
    if (!no_memfd) {
        fd = syscall(__NR_memfd_create, buf, VD_MFD_CLOEXEC);
        if (fd < 0 && errno == ENOSYS) no_memfd = 1;
    }
    if (fd < 0 && no_memfd) fd = vd_tmpfs_region();
    if (fd < 0) {
        return -1;
    }
    if (TEMP_FAILURE_RETRY(ftruncate(fd, size)) < 0) {
        int save_errno = errno;
        close(fd);
        errno = save_errno;
        return -1;
    }
    return fd;
}

/* Record prot in the file mode (01000 | (prot & 7)), matching vdshim.c's VD_MASK_SET contract. */
int ashmem_set_prot_region(int fd, int prot)
{
    struct stat st;
    if (fd >= 0 && TEMP_FAILURE_RETRY(fstat(fd, &st)) == 0) {
        mode_t m = (st.st_mode & 0770) | 01000 | (mode_t)(prot & 7);
        TEMP_FAILURE_RETRY(fchmod(fd, m));
    }
    return 0;
}

/* Regions are never purged, so pinning always succeeds with "not purged". */
int ashmem_pin_region(int fd, size_t offset, size_t len)
{
    (void)fd; (void)offset; (void)len;
    return ASHMEM_NOT_PURGED;
}

int ashmem_unpin_region(int fd, size_t offset, size_t len)
{
    (void)fd; (void)offset; (void)len;
    return ASHMEM_IS_UNPINNED;
}

int ashmem_get_size_region(int fd)
{
    struct stat st;
    if (TEMP_FAILURE_RETRY(fstat(fd, &st)) < 0) {
        return -1;
    }
    return (int)st.st_size;
}
