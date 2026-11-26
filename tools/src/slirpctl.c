// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * slirpctl: send one JSON request to a slirp4netns API socket and print the reply.
 * Usage: slirpctl <socket-path> '<json>'
 * Used by the host scripts to open / close port forwards (add_hostfwd / remove_hostfwd) so that devices on the Wi-Fi
 * network can reach a server running inside the Android container. Written for VeraDroid; GPL-compatible, MIT-style.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

int main(int argc, char **argv)
{
    if (argc < 3) { fprintf(stderr, "usage: %s <socket> <json>\n", argv[0]); return 2; }
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { perror("socket"); return 1; }
    struct timeval tv = { 5, 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    struct sockaddr_un a;
    memset(&a, 0, sizeof(a));
    a.sun_family = AF_UNIX;
    strncpy(a.sun_path, argv[1], sizeof(a.sun_path) - 1);
    if (connect(fd, (struct sockaddr *)&a, sizeof(a)) < 0) { perror("connect"); return 1; }
    size_t n = strlen(argv[2]);
    if (write(fd, argv[2], n) != (ssize_t)n) { perror("write"); return 1; }
    shutdown(fd, SHUT_WR);
    char buf[4096];
    ssize_t r;
    while ((r = read(fd, buf, sizeof(buf))) > 0) fwrite(buf, 1, (size_t)r, stdout);
    if (r < 0) { perror("read"); close(fd); return 1; }
    putchar('\n');
    close(fd);
    return 0;
}
