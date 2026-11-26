#include <stdio.h>
/* FBInk stand-in for the native veradroid-epd test (tests/epd-test.sh): no panel, every refresh is logged by epd.c
   itself; fbink_wait_for_complete sleeps like a PW6 panel (DU 135 ms, the rest 205 ms) so PACE / busy logic runs. */
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include "fbink.h"
static uint32_t last_marker = 0;
static int last_slow = 0;
/* STUB_SWIPE_HANG=1: an animated (swipe) refresh takes 3 s to complete, like the KT6 / rossini emulator ("wait timeout, lut status") */
static uint32_t hang_marker = 0;
int fbink_open(void) { return open("/dev/null", O_RDWR); }
int fbink_close(int fd) { return close(fd); }
int fbink_init(int fd, const FBInkConfig *restrict c) { (void)fd; (void)c; return 0; }
void fbink_get_state(const FBInkConfig *restrict c, FBInkState *restrict s)
{
    (void)c; memset(s, 0, sizeof(*s));
    s->is_mtk = 1; s->screen_width = 1272; s->screen_height = 1696;
    if (getenv("STUB_COLOR")) { s->has_color_panel = 1; s->bpp = 32; }
    else s->bpp = 8;
}
int fbink_print_raw_data(int fd, const unsigned char *restrict d, const int w, const int h, const size_t len, short x, short y,
                         const FBInkConfig *restrict c)
{
    (void)fd; (void)x; (void)y;
    last_marker++; last_slow = c->wfm_mode != WFM_DU && c->wfm_mode != WFM_A2;
    if (c->is_animated && getenv("STUB_SWIPE_HANG")) hang_marker = last_marker;
    if (c->is_animated) fprintf(stderr, "stub: swipe refresh\n");
    int bpp = (w > 0 && h > 0) ? (int)(len / ((size_t)w * (size_t)h)) : 0;
    if (bpp == 4) {
        fprintf(stderr, "stub: wfm=%d dither=%d full=%d bpp=4 px0=%u,%u,%u,%u\n", (int)c->wfm_mode, (int)c->dithering_mode, (int)c->is_flashing,
                (unsigned)d[0], (unsigned)d[1], (unsigned)d[2], (unsigned)d[3]);
    } else {
        fprintf(stderr, "stub: wfm=%d dither=%d full=%d\n", (int)c->wfm_mode, (int)c->dithering_mode, (int)c->is_flashing);   /* text dithering / CLEAN checks */
    }
    return 0;
}
int fbink_wait_for_complete(int fd, uint32_t m) { (void)fd; if (hang_marker && m == hang_marker) { usleep(3000000); return 0; } usleep(last_slow ? 205000 : 135000); return 0; }
uint32_t fbink_get_last_marker(void) { return last_marker; }
int fbink_mtk_set_swipe_data(MTK_SWIPE_DIRECTION_INDEX_T d, uint8_t s) { (void)d; (void)s; return 0; }
