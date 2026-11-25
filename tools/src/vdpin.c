// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * vdpin: lock critical UI code files (e.g. VeraHome odex + apk, ~3.8 MB) in RAM
 * via mmap + mlock so heavy app I/O on 512 MB devices can never evict the
 * launcher / status bar / pull-down panel code pages.
 *
 * Usage: vdpin <pidfile> <file>...
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

static volatile sig_atomic_t stop_flag = 0;
static void on_sig(int s) { (void)s; stop_flag = 1; }

int main(int argc, char **argv)
{
    if (argc < 3) return 1;
    const char *pidf = argv[1];
    pid_t pid = fork();
    if (pid < 0) return 1;
    if (pid > 0) return 0;

    setsid();
    int locked = 0;
    size_t total = 0;
    for (int i = 2; i < argc; i++) {
        int fd = open(argv[i], O_RDONLY | O_CLOEXEC);
        if (fd < 0) continue;
        struct stat st;
        if (fstat(fd, &st) == 0 && st.st_size > 0 && st.st_size <= 16 * 1024 * 1024) {
            void *p = mmap(NULL, (size_t)st.st_size, PROT_READ, MAP_PRIVATE | MAP_POPULATE, fd, 0);
            if (p != MAP_FAILED) {
                if (mlock(p, (size_t)st.st_size) == 0) {
                    locked++;
                    total += (size_t)st.st_size;
                } else {
                    munmap(p, (size_t)st.st_size);
                }
            }
        }
        close(fd);
    }
    if (!locked) return 0;

    char pidf_abs[4096];
    if (pidf[0] == '/') snprintf(pidf_abs, sizeof(pidf_abs), "%s", pidf);
    else if (getcwd(pidf_abs, sizeof(pidf_abs) / 2)) {
        size_t gl = strlen(pidf_abs);
        snprintf(pidf_abs + gl, sizeof(pidf_abs) - gl, "/%s", pidf);
    } else snprintf(pidf_abs, sizeof(pidf_abs), "%s", pidf);
    FILE *pf = fopen(pidf_abs, "w");
    if (pf) { fprintf(pf, "%d\n", (int)getpid()); fclose(pf); }
    if (chdir("/") < 0) { /* best effort */ }
    int dn = open("/dev/null", O_RDWR);
    if (dn >= 0) {
        (void)dup2(dn, STDIN_FILENO);
        (void)dup2(dn, STDOUT_FILENO);
        (void)dup2(dn, STDERR_FILENO);
        if (dn > STDERR_FILENO) close(dn);
    }
    /* Protect self from LMK / OOM while holding the 3.8 MB UI code pin */
    int adj = open("/proc/self/oom_score_adj", O_WRONLY);
    if (adj >= 0) { (void)!write(adj, "-900\n", 5); close(adj); }

    signal(SIGTERM, on_sig);
    signal(SIGINT, on_sig);
    while (!stop_flag) pause();
    unlink(pidf_abs);
    return 0;
}
