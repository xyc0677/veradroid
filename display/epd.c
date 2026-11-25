// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * veradroid-epd: e-ink display bridge for VeraDroid.
 *
 * The Android system (running in a container) draws into a shared RGBA8888 buffer and tells us which rectangle
 * changed. We turn it into 8-bit gray (contrast curve, optional hardware dithering) and hand it to the MediaTek
 * hwtcon driver through FBInk (GPLv3, https://github.com/NiLuJe/FBInk), choosing a waveform per update.
 *
 * Protocol on the FIFO (one command per line):
 *     P n [A|F|D|G|R]         page n of the shared buffer is the new picture (n = 2: <dir of shm>/fb2, hwcomposer.kindle's
 *                             own page for frames with a dim layer); we diff it against what is on the
 *                             panel and refresh only the changed rectangle (mode: A auto, F full flashing GC16,
 *                             D fast DU, G GC16, R REAGL)
 *     P n y0 y1               the same, and the sender (hwcomposer.kindle) promises that only rows y0 <= y < y1 differ
 *                             from the picture it announced before: only those rows are converted and compared
 *     D x y w h [A|F|D|G|R]   same, but the caller names the damaged rectangle (no diff)
 *     REDRAW                  redraw the whole buffer (no flash), page 0 or the last page posted
 *     NOFLIP <ms>             for <ms>, large changes are not page turns for the KFB_FLASH count (VeraIME: keyboard coming up)
 *     SETTLE2 <ms>            first settle window (adaptive settle, 40; 0 = always KFB_SETTLE), see settle_first
 *     SCROLLDU <ms>           burst fast mode: DU while large frames keep coming, 16-gray pass <ms> after (0 = off)
 *     IMG <pct>               均衡: small changes that are >= <pct> % mid-gray (pictures) use GL16 instead of DU (0 = off)
 *     FLIPGAP <ms>            large frames closer together than this are one page flip for the KFB_FLASH count (600; 0 = every
 *                             large frame counts, the old behaviour)
 *     TOL <0|1>               tolerant change detection for the page-flip count / animation (see row_sig), default 1
 *     SET <gamma> <dither> <flash_every> <invert> [mode] [settle] [lightf] [darken]
 *                             display settings; lightf 0..3 / darken 0|1 (light-colour filter, see build_lut) are
 *                             optional: a sender that leaves them out (older host scripts) turns both off
 *     ROT <deg>               change the rotation live, only within the same logical size (0 <-> 180, 90 <-> 270);
 *                             full flashing redraw
 *     PACE <0|1>              1: a frame that mostly lands on a part of the panel that is still refreshing is kept back
 *                             until that refresh is done, and only the newest picture is drawn then (scrolling and
 *                             animations follow the panel's own speed instead of waiting for a quiet moment); replaces
 *                             the settle merging while it is on
 *     CLEAN <ms>              after fast (DU, 1-bit) refreshes and <ms> without a new frame, refresh those areas once
 *                             more with a 16-gray waveform (GL16, only pixels that differ change; REAGL when it is most
 *                             of the screen). 0 = off
 *     REAGL <pct>             balanced mode: frames that change at least <pct> % of the screen use REAGL instead of DU (35)
 *     BADGE <ms> <pos> <icon> <caps>|<label>[|<line 2>]   gesture badge drawn by the bridge itself (docs/gesture-hint.md);
 *                             BADGE 0 takes it down
 *     LAUNCH <ms>             an app is being opened (VeraHome, before startActivity / when the task stack changes):
 *                             for up to <ms>, frames that are one flat colour (the window manager's starting window -
 *                             the app's theme background, white or black) are not drawn; the last picture stays and
 *                             after 600 ms a small "加载中" badge goes on it. The first frame with real content (or the
 *                             end of <ms>) ends it. LAUNCH 0 ends it at once
 *     QPASS <ms>              picture quality pass: a picture area that got a 1-bit refresh, a colliding refresh or several
 *                             overlapping ones gets one REAGL (FULL) once the picture was still for <ms> (300; 0 = off),
 *                             see qpass_ms
 *     VERIFY                  test aid: log whether the newest page differs from what was sent to the panel
 *     ANIMHOLD <ms>           page-slide hold: a first frame that changes only a strip along a screen edge (a slide / cover page
 *                             animation starting) is held and merged with the frames that follow until none came for <ms>
 *                             (180; 0 = off), see anim_quiet_ms
 *     TURNHOLD <ms>           page-turn merge window (per app, KFB_TURNHOLD; 0 = off, default): any first frame that changes a
 *                             page (page_pct of the screen) is held the same way, until no frame came for <ms> (at most 1000
 *                             after the first), see turn_quiet_ms
 *     SCROLL <0|1>            veradroid-input: a finger is dragging (1, sent once the finger moved past the touch slop) /
 *                             was lifted (0). See scroll_delay_ms
 *     SCROLLDELAY <ms>        touch scroll mode: DU while the finger drags (and the fling right after), one 16-gray FULL
 *                             cleanup (REAGL) of the area drawn that way <ms> after the last scroll frame (500; 0 = SCROLL ignored)
 *     RT <prio>               SCHED_FIFO <prio> for the bridge's main thread (0 = normal), like --rt
 *     SCROLLWFM <0|1>         touch scroll waveform: 0 DU (default), 1 A2 (99 ms on PW6 when nothing overlaps; PACE keeps
 *                             frames from overlapping, so the driver switches the waveform group only once per drag)
 *     SCROLLSTYLE <1|2>       1 as above; 2 nothing is drawn while the finger drags, the newest picture goes out once (REAGL)
 *                             180 ms after the frames stop (at most 1200 ms after the lift)
 *     GCN <n>                 a full flash (once the picture is still) after <n> refreshes that are not page turns and cover
 *                             >= 3 % of the screen, so UI-heavy apps also lose their ghosts (0 = off)
 *     NEWWIN                  the app in front changed (host appprof, KFB_NEWWIN=1): the first large frame in the next 3 s
 *                             goes out as a full flashing GC16
 *     MONO <level>            DU refreshes are thresholded to pure black / white at <level> (1..254; 0 = off) with no dithering
 *     MAXDEFER <ms>           a frame kept back (small-change hold, panel busy) is drawn at the latest <ms> after it came
 *                             (1200; 0 = off); slide / page-count holds also end at their own cap while frames keep coming
 *     H <c> [x y w h]         hint for the next frame (the framework, just before it posts one): if a P / D line comes within
 *                             500 ms, it is drawn with mode <c> (F full flashing GC16, G GC16, R REAGL, D DU; A = no change),
 *                             and the rectangle (logical picture, like D) is refreshed with it as well. Several H lines merge
 *                             (F > G > R > D > A, rectangles joined). Draws nothing itself
 *     FAST <ms>               every frame DU until <ms> have passed (at most 600000; FAST 0 ends it), then one 16-gray
 *                             cleanup of what was drawn that way (the touch-scroll cleanup)
 *     FREEZE <ms>             nothing is drawn until <ms> have passed (at most 10000) or FREEZE 0 comes; then the newest
 *                             picture, once
 *     TOUCH                   veradroid-input: a finger was lifted / a bezel tap (also DIR, sent by the page-turner bridge
 *                             on a page key): an input event, for FLIPINPUT
 *     FLIPINPUT <ms>          only large changes within <ms> of an input event count as page turns for KFB_FLASH (1000; 0 = every
 *                             one counts): an animated screen or an ad that changes by itself no longer brings the flash
 *     FLIPCOUNT <0|1>         0 = no page counting at all (the host sends it while the home screen is in front)
 *     STATS                   log the refresh counters
 *     QUIT
 * NEVER send EPDC_FLAG_SCRLL_START/MID/END (0x60/0x20/0x40) to hwtcon_v2: on a real PW6 one START + MID + END sequence
 * left the driver's dispatch thread stuck for good (every later update timed out after 5 s, only a reboot cleared it). Nothing in this file sets flags other than SKIP_CFA / dithering / swipe.
 * While <ctl>/sleep exists, updates are dropped (the Kindle screensaver is on screen).
 *
 * <shm> is a file of 2 pages of RGBX8888 (created here if it does not exist).
 *
 * Usage:
 *     veradroid-epd run  <shm> <fifo> <ctl> [--w 1272] [--h 1696] [--contrast 1.3] [--dither 3]
 *                        [--anim-mode 0..5] [--anim-dir 0..3] [--anim-steps 12] [--anim-min 60] [--swipe 0|1] [--rotate 0|90|180|270]
 *                        [--vw <visible width>] [--pwrdown <ms>] [--skip-cfa 0|1] [--pace 0|1] [--clean <ms>]
 *                        [--lightf 0..3] [--darken 0|1]
 *
 * Waveforms (checked against Amazon's GPL kernel sources):
 *   MediaTek hwtcon (PW5/KT5/KT6/Scribe/PW6/Colorsoft, drivers/misc/mediatek/hwtcon_v2):
 *     DU(1) fast 2-level; GC16(2) with PARTIAL is turned by the driver into GC16_PARTIAL(10); GL16(3);
 *     REAGL = GLR16(4): the driver's own page-turn choice is GLR16 + UPDATE_MODE_FULL (hwtcon_extra_feature.c), and
 *     only FULL updates every pixel of the region with the ghost-cleaning waveform, so REAGL is always sent FULL here
 *     (it does not flash). AUTO(257) picks DU / GC16_PARTIAL / GL16 from the histograms and turns big regions
 *     (> 1/3 width and > 1/4 height) into GLR16 FULL. The swipe animation: at most 30 steps here (MTK_MAX_SWIPE: 60
 *     is the driver's limit, but beyond ~31 the dual-mode TCON runs out of LUTs and wedges), needs region width
 *     (left/right) or height (up/down) >= steps,
 *     is ignored in night mode and when the waveform file does not support it.
 *     Hardware dithering is only on/off (Y4, or Y1 for DU on PW6): --dither 1/2/3 all mean "on".
 *     It is only used for updates that hold a picture (text_no_dither): on text it puts dots along the glyph edges.
 *   --pwrdown <ms>: MXCFB_SET_PWRDOWN_DELAY (hwtcon and mxc_epdc). The driver default 0 powers the panel down after
 *     every update; a short delay saves the power-up sequence while updates come in bursts. The original value is read
 *     first and put back when we exit (the Kindle UI shares the panel). -1 = leave the driver alone (default).
 *
 * Rotation (docs/rotation.md): --w/--h are always the PANEL size. --vw is the visible width when --w is the framebuffer
 * stride (PW5 without FBInk: 1248 rows, 1236 visible); the rotation turns the picture inside the visible area. With
 * --rotate 90/270 the shared buffer holds an upright picture of the logical size H x VW (Android was started with that
 * size), with 180 it is W x H (logical columns >= VW are invisible, as at 0). Each page is turned into
 * panel-space gray while converting, so the diff, the dirty rectangles and the refreshes all stay in panel coordinates.
 * 90 = the picture is turned 90 degrees clockwise on the panel (its top is at the panel's right edge). Animation
 * directions (DIR / ANIM / --anim-dir, and the fixed modes 2..5) are given in the logical picture and turned here.
 *     veradroid-epd test                      draw a test pattern directly (no Android needed)
 *     veradroid-epd file <rgba-file> [--w W --h H]  draw a raw RGBA file
 *
 * Build (armhf, static):
 *     arm-linux-gnueabihf-gcc -O2 -static -pthread -march=armv7-a -mfpu=neon -I FBInk epd.c FBInk/Release/libfbink.a -lm -o veradroid-epd
 *     (-mfpu=neon turns on the NEON gray conversion; every supported Kindle has NEON. Without it the C loop is used.)
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <linux/fb.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/select.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include "fbink.h"
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

static int W = 1272, H = 1696;
static int fbfd = -1;
static FBInkConfig cfg;
static uint8_t lut[256];
static uint8_t dither = 3;   /* 0 off, anything else on: the MTK / EPDC hardware has one algorithm per output depth (see the header) */
static volatile sig_atomic_t quit = 0;
static uint8_t *cur_y8 = NULL, *prev_y8 = NULL;   /* what we want / what is on the panel */
static int prev_valid = 0, last_page = 0;
/* page 2 = <dir of shm>/fb2, a third picture that only hwcomposer.kindle writes (frames with a dim layer: SurfaceFlinger
   draws its own GLES veil into both framebuffer pages, see android/hwcomposer/hwc_kindle.c). Mapped when first named. */
static const uint8_t *fb2map = NULL;
static char fb2path[600];
static int fb2_ok(void)
{
    if (fb2map) return 1;
    if (!fb2path[0]) return 0;
    int fd = open(fb2path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    struct stat sb;
    void *m = MAP_FAILED;
    if (fstat(fd, &sb) == 0 && (size_t)sb.st_size >= (size_t)W * H * 4) m = mmap(NULL, (size_t)W * H * 4, PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (m == MAP_FAILED) return 0;
    fb2map = m;
    return 1;
}
static const uint8_t *page_base(const uint8_t *shm, int page)
{
    if (page == 2 && fb2_ok()) return fb2map;
    return shm + (size_t)(page & 1) * W * H * 4;
}
static double g_gamma = 1.3;
static int g_invert = 0;      /* 1: invert the picture (white on black) */
static int g_lightf = 0;      /* 浅色过滤 0 off, 1 weak, 2 medium, 3 strong: light pixels become pure white (build_lut) */
static int g_darken = 0;      /* 深色加深: 1 = darker curve for everything that is not filtered to white */
static int flash_every = 0;   /* full flashing refresh every N page flips, 0 = never by count */
static int flip_count = 0;
/* A "page flip" for the KFB_FLASH count is one burst of large changes, not one frame. Apps with a page-turn animation
   (多看: slide / cover, ~10 frames of >35 % change per turn), scrolling lists and loading pictures post several large
   frames per user action; counting each of them made the full flash come every page or two instead of every N pages
   (the owner's "多看刷新几乎一整页一闪一闪"). A large frame within flip_gap_ms of the previous large frame belongs to the same
   flip. FLIPGAP <ms> / --flip-gap changes it (0 = the old per-frame count). */
static int flip_gap_ms = 600;
static const int page_pct = 35;   /* a frame whose visible change covers this share of the screen is a page turn */
static long long last_big_ms = -100000;
/* When the count is reached, the flash is not sent at once in the middle of a burst (it would flash an in-between
   animation frame and the frames after it would be drawn over it without a flash again); the frame is held until the
   picture has been still for flash_quiet_ms (at most flash_max_ms after the flip began) and the settled picture flashes. */
static int flash_quiet_ms = 180, flash_max_ms = 1200;
static long long flash_t0 = 0;           /* when the flip that reached the count began */
/* Adaptive settle (SETTLE2 <ms>, --settle-first): the settle merging used to wait settle_ms (KFB_SETTLE, 40..120) for
   every frame that followed a refresh within 350 ms, so the second change of a quick interaction (the checkbox after the
   ripple, a key after a key) waited 120 ms more. Now, when the frames of the batch name their rows (P n y0 y1, from
   hwcomposer.kindle) and those rows are less than a fifth of the screen, the first merge window is settle_first ms; when
   another frame comes inside it, or the change is large (a scroll, an animation, a page), the full settle_ms is used.
   Measured on a test PW6 (mode 3, KFB_SETTLE 120): a small change 200 ms after a refresh, frame -> panel done 432 -> 350 ms. */
static int settle_first = 40;
/* Burst fast mode (SCROLLDU <ms>, --scroll-du; 0 = off, the default): while large frames keep coming (a scroll or drag:
   a large frame within flip_gap_ms of the previous one) they go out with DU (1-bit, ~134 ms on a PW6) instead of
   REAGL / AUTO (~205 ms), and once the picture has been still for <ms> the area drawn that way gets one 16-gray pass
   (REAGL when it is most of the screen, GL16 otherwise) - the CLEAN pass, also when CLEAN itself is off. The first large
   frame of a burst (a page turn) keeps its waveform. */
static int burst_du_ms = 0;
static int burst_dirty = 0;
static int g_burst = 0;              /* set while the frame being drawn is a continuation of a burst */
/* Touch scroll mode (SCROLL from veradroid-input, SCROLLDELAY, SCROLLSTYLE).
   Guessing a scroll from frame timing (SCROLLDU) only switches to DU after the second large frame and cannot tell a
   scroll from a page turn. veradroid-input knows: a single finger that moved past the touch slop is dragging, so it
   sends SCROLL 1 then, and SCROLL 0 when the finger goes up. While the finger drags - and while the fling that follows
   keeps sending frames (gaps < SCROLL_FLING_GAP, up to SCROLL_FLING_MAX after the lift) - every frame goes out at once
   with DU (no hold, no merging wait, paced by the panel), page flips are not counted, and the area drawn that way gets
   one 16-gray FULL refresh (REAGL: every pixel driven, no flash) scroll_delay_ms after the last scroll frame. A drag
   during which the app drew nothing (a tap that wobbled, a reader that turns its page on the lift) leaves scroll mode
   at the lift, so the page that follows keeps its normal waveform. Style 2 (per app, opt-in): nothing is drawn during
   the drag, the newest picture goes out once after it. */
static int scroll_delay_ms = 500;    /* 0 = SCROLL hints are ignored; 500 measured on a test PW6 */
static int scroll_style = 1;
static void set_rt(int prio, const char *who);
static int scroll_a2 = 0;    /* SCROLLWFM 1 / --scroll-a2 1: touch-scroll frames go out as A2 instead of DU */
static int scroll_on = 0, scroll_frames = 0, scroll_dirty = 0, g_scroll = 0, scroll_raw_y8 = 0;
static long long scroll_t_on = 0, scroll_t_off = 0, scroll_last = 0;
static unsigned long n_scroll_frames = 0, n_scroll_clean = 0, n_scroll_held = 0;
#define SCROLL_FLING_GAP 400
#define SCROLL_FLING_MAX 3000
#define SCROLL_MAX_DRAG 30000        /* a lost SCROLL 0 (input front end restarted) does not leave the mode on for good */
#define SCROLL_NOFLASH_MS 1200       /* suppress false GC16 black flashes during webview/list post-scroll settle; REAGL cleanup handles it */
static long long burst_last_ms = -100000;
static int scroll_recent(long long t)
{
    if (scroll_on) return 1;
    if (scroll_frames && ((t - scroll_last < SCROLL_NOFLASH_MS) || (t - scroll_t_off < SCROLL_NOFLASH_MS))) return 1;
    if (t - burst_last_ms < SCROLL_NOFLASH_MS) return 1;
    return 0;
}
static int gc_every = 0, gc_count = 0;   /* GCN: full flash after this many non-page refreshes (0 = off) */
static unsigned long n_gcn = 0, n_newwin = 0;
static long long newwin_until = 0;        /* NEWWIN: the first large frame before this time is a full flash */
static int mono_level = 0;           /* MONO: DU refreshes thresholded at this gray level (0 = off) */
static int max_defer_ms = 1200;      /* MAXDEFER */
static int flip_input_ms = 1000, flip_count_on = 1;   /* FLIPINPUT / FLIPCOUNT */
static long long fast_until = 0, freeze_until = 0;         /* FAST / FREEZE */
static int hint_mode = -1, hr_valid = 0, hr_x0, hr_y0, hr_x1, hr_y1;   /* H: mode (-1 = none) and rectangle for the next frame */
static long long hint_t = 0;
static int fr_valid = 0, fr_x0, fr_y0, fr_x1, fr_y1;  /* the H rectangle being applied to the frame drawn now */
static long long last_input_ms = -100000;
static long long now_ms(void);
static int scroll_active(long long t)
{
    if (fast_until) {             /* FAST: like a drag; its end is the lift */
        if (t < fast_until) return 1;
        fast_until = 0; scroll_t_off = t; scroll_last = t;
        return 0;
    }
    if (scroll_delay_ms <= 0) return 0;
    if (scroll_on) {
        if (t - scroll_t_on < SCROLL_MAX_DRAG) return 1;
        scroll_on = 0; scroll_t_off = t;
    }
    if (!scroll_frames || t - scroll_t_off >= SCROLL_FLING_MAX) return 0;
    long long ref = scroll_last > scroll_t_off ? scroll_last : scroll_t_off;
    return t - ref < SCROLL_FLING_GAP;
}
/* Waveform by content (IMG <pct>, --img-gray; 0 = off, the default): in 均衡 a small change goes out with DU, which turns a
   picture (a cover, an avatar, a photo) into a 1-bit dot pattern. A region whose sampled pixels are mid-gray (32..223)
   for at least <pct> % is a picture and gets GL16 (16 grays, only changed pixels, no flash, ~70 ms slower); text,
   icons and anti-aliased edges stay DU. */
static int img_gray_pct = 0;
/* Picture quality pass (QPASS <ms>, --qpass; 0 = off, default 300). A picture that loads in steps (微信读书 covers and
   illustrations: placeholder, then the picture, in 2-4 overlapping refreshes 70-170 ms apart, each sent while the previous
   one is still running on the panel), or that went out 1-bit (DU in 均衡 / 最快), stayed
   half drawn / ghosted on the panel until the next page-count flash (KPW5 report, 0.96): the partial waveforms only drive
   the pixels whose value changed, so nothing ever repaints the pixels the colliding updates left behind. Now every
   refresh that is not already a full-quality update (REAGL / GC16 FULL) remembers the part of it that holds a picture
   (32x32 tiles that are mostly mid-gray, img_box; an MTK hardware swipe is also remembered since it is PARTIAL, though
   hwtcon_v2 already draws it in 16-gray GLR16 so a clean single-frame swipe does not set lowq on its own). When that part
   got a 1-bit refresh, a refresh sent while an earlier one of the same pixels was still running (collision), or two or
   more refreshes that overlap without one covering the other (a picture drawn in bands / steps), it gets one REAGL (FULL:
   every pixel driven, no flash; GL16 FULL on 4-bit i.MX panels) of what is on the panel once no frame came for <ms> and
   the panel is idle. Text (no picture tiles) and clean single-frame page turns (REAGL or hardware swipe) never get it. */
static int qpass_ms = 300;
static int qp_x0, qp_y0, qp_x1, qp_y1, qp_valid = 0, qp_n = 0, qp_lowq = 0;
static long long qp_last = 0, qp_done = -100000;
static unsigned long n_qpass = 0;
/* at most one pass per qp_gap_ms: an app that animates without end (晋江's first-start guide, a loading spinner next to
   a picture) pauses for more than qpass_ms between its loops, and a pass every pause would only add refreshes */
#define QP_GAP_MS 2000
static unsigned long n_flash = 0;   /* GC16 full flashes sent, for the log */
static long long last_draw_ms = 0;   /* when we last sent something to the panel */
static int settle_ms = 0;     /* merge frames: wait until no new frame came for this long (max 500 ms) before drawing, so scrolling / animations
                                 draw only the settled picture instead of every in-between frame */
static int ui_mode = 0;       /* 0 balanced (large: REAGL, small: DU), 1 best gray (GC16 always), 2 fastest (DU always),
                                 3 driver auto (WAVEFORM_MODE_AUTO: the driver picks from the histograms, like the Kindle UI) */
/* The driver accepts 1..60 steps (MAX_SWIPE_COUNT, hwtcon_v2/hal/hwtcon_pipeline_config.h; more -> EINVAL, update lost),
   but every step takes its own waveform LUT out of 63, and PW6 / Colorsoft run the TCON in dual mode (dts dual_mode = <1>,
   two LUTs per step). Measured on the pw6 emulators (real hwtcon_v2.ko): 30 steps fine, 40 and 50 steps run out of LUTs,
   hwtcon_core_get_free_lut times out (5 s) and the driver stays wedged - every later update takes 5 s - until a reboot.
   So never more than 30. */
#define MTK_MAX_SWIPE 30
static int pwrdown_ms = -1;   /* --pwrdown / PWRDOWN: panel power-down delay to set, -1 = do not touch */
static int pwrdown_orig = 0, pwrdown_saved = 0;
static int no_reagl = 0;      /* i.MX EPDC with a 4-bit waveform file: REAGL does not exist, the driver runs GL16_FAST instead and
                                 logs an error for every update (mxc_epdc_fb.c, "REAGL waveform update specified without valid
                                 REAGL waveform file"), so ask for GL16 directly */
/* Kindle MTK 硬件滑动动画：整页变化时让控制器把新画面滑进来（和 Kindle 原生翻页同一套 P2SW/REAGL），而不是整屏闪一下。
   anim_mode: 0 关，1 跟随 DIR/手势，2 左，3 右，4 上，5 下；变化面积超过 anim_min_pct% 才动画。 */
static int anim_mode = 0;      /* default off: synchronous full-frame refresh (no directional swipe ripple) */
static int anim_dir = 2;       /* MTK_SWIPE_DIR_LEFT */
static int anim_steps = 12;
static int anim_min_pct = 60;
static int anim_next = 0;
/* SWIPE <dir> <steps> (VeraHome 方块风格的换页 / 设置单动画): one shot, its own direction and step count. It does not touch
   anim_dir / anim_steps (the user's ANIM settings for normal page turns) and it works even when ANIM is off. */
static int once_dir = -1, once_steps = 0;
/* Hardware swipe on/off (--swipe 0|1, SWIPEOK 0|1; host KFB_SWIPE). On the Kindle 2024 / KT6 (rossini) emulator 4 of 5 swipes
   (even 1-2 steps) end in "wait timeout, lut status" / "trigger pipeline fail": the update never completes, the panel keeps the
   old picture and every later partial refresh is lost until a full GC16 refresh - the screen looks frozen although Android
   still runs (field report: Kindle 2024, the panel "vanished" and nothing responded any more, forced reboot). So:
   swipe_ok = 0 drops every swipe (SWIPE one-shots and ANIM page turns, drawn as normal refreshes), and whatever the setting,
   a swipe whose completion takes longer than SWIPE_SLOW_MS (normal: 130-400 ms) or fails turns swipes off for this run and
   brings the panel back with one full refresh (swipe_recover, done by the main loop). */
static int swipe_ok = 1;
static volatile int swipe_broken = 0, swipe_recover = 0;
#define SWIPE_SLOW_MS 2500
static long long once_until = 0;    /* a SWIPE that no frame followed within 800 ms is dropped (it must not animate some later change) */
static int swipe_steps = 12;   /* step count of the swipe being sent (anim_steps, or once_steps for a SWIPE one-shot) */
static int g_rot = 0;          /* 0 / 90 / 180 / 270, see the header */
static int LW = 1272, LH = 1696; /* logical (Android) size: W x H, or H x VW for 90 / 270 */
static int VW = 0;             /* visible panel width (<= W), 0 = W */

/* MTK swipe directions 0 down 1 up 2 left 3 right, given in the logical picture -> on the panel */
static int rot_dir(int dir)
{
    static const int r90[4] = { 2, 3, 1, 0 }, r180[4] = { 1, 0, 3, 2 }, r270[4] = { 3, 2, 0, 1 };
    if (dir < 0 || dir > 3) return dir;
    switch (g_rot) {
    case 90:  return r90[dir];
    case 180: return r180[dir];
    case 270: return r270[dir];
    default:  return dir;
    }
}
/* Small first changes are held back briefly. A tap first repaints the pressed button (a tiny change) and, 100-300 ms later,
   the whole new page. Sending the tiny change at once costs twice: the panel is busy with it when the page arrives (the
   overlapping update has to wait for it), and the user sees two refreshes. So a change smaller than hold_pct of the screen
   waits up to hold_ms for a following frame; if one comes, only the newer picture is drawn. 0 = off (old behaviour). */
static int hold_ms = 70;
static int hold_pct = 3;
/* Page-slide hold (ANIMHOLD <ms>, --anim-hold; 0 = off, default 180). Readers with a slide / cover / curl page animation
   (晋江 7.1.9: ~6 frames 80 ms apart) start the turn with a strip along the left or right edge (the new page, or its shadow,
   coming in: 136x1696 at x 1120), then the full page, then sometimes the shadow leaving and the header / page number.
   The first frame after a quiet moment used to go out at once (a tap must answer quickly), so every turn was a DU strip,
   a REAGL page and one or two small refreshes: 2-8 refreshes, ~1 s (a test PW6, KFB_MODE 3). Now a first frame whose change is
   such an edge strip (nearly the full height and at most 2/5 of the width, touching the left or right edge - or the same
   turned for top / bottom) is held; every frame that follows replaces it, and the newest picture is drawn once no frame
   came for anim_quiet_ms (at most anim_max_ms after the first). Drawers and menus that slide in from an edge are merged
   the same way. Other first frames (a pressed button, a key) are not affected. */
static int anim_quiet_ms = 180, anim_max_ms = 1000;
/* Page-turn merge window (TURNHOLD <ms>, per app KFB_TURNHOLD; 0 = off). Like Onyx's per-app "animation duration"
   debounce. A reader whose cover / slide animation already changes most of the page in its first frame (番茄免费小说
   7.4.5 覆盖 on a PW6: the first frame 72 % of the screen, four more 55-85 ms apart) is not an edge strip, so each turn
   used to be two full REAGL refreshes ~400 ms apart, the first one showing the half-covered page. With TURNHOLD a
   first frame after a quiet moment that changes at least page_pct of the screen is held like a page-slide hold and
   replaced by every frame that follows; the newest picture goes out once no frame came for turn_quiet_ms (at most
   anim_max_ms after the first). Costs turn_quiet_ms on a turn without animation, so it is only set for apps that need it. */
static int turn_quiet_ms = 0;
static int slide_quiet_ms = 180;   /* the quiet window of the hold now running (anim_quiet_ms or turn_quiet_ms) */
static long long anim_t0 = 0;
static int g_lead = 0;    /* set by the main loop: the frame being shown is the first after a quiet moment */
static int edge_strip(int x0, int y0, int x1, int y1)
{
    int w = x1 - x0, h = y1 - y0;
    if (w <= 0 || h <= 0) return 0;
    if (y0 <= H / 20 && y1 >= H - H / 20 && w * 5 <= W * 2 && (x0 <= W / 50 || x1 >= W - W / 50)) return 1;
    if (x0 <= W / 20 && x1 >= W - W / 20 && h * 5 <= H * 2 && (y0 <= H / 50 || y1 >= H - H / 50)) return 1;
    return 0;
}
/* KFB_FLASH counts page turns of the content, not every large change. 0.96 report (阅读 / Legado settings, a WebDav
   account dialog with VeraIME): the keyboard showing / hiding (the bottom ~45 % of the screen, and the app relayouting
   above it) and dialogs opening / closing were counted as pages, so with a reader profile (KFB_FLASH=6) every third
   dialog or keyboard ended in a full black flash, also while typing. A page turn changes the content from near the
   top to near the bottom over most of the width, in rows that cover at least half the height; a keyboard starts
   half-way down, a dialog sits in the middle, a reader's toolbars are a band at the top and one at the bottom. While a
   HINT window is open (VeraIME sends one when the keyboard goes, VeraHome when its panel opens / closes) nothing is
   counted either. */
static long long hint_until;
static long long noflip_until = 0;   /* NOFLIP <ms>: VeraIME's keyboard is coming up */
static long long now_ms(void);
static int page_like(int x0, int y0, int x1, int y1, int rows)
{
    long long t = now_ms();
    if ((hint_until && t < hint_until) || t < noflip_until) return 0;
    return y0 <= H * 15 / 100 && y1 >= H * 85 / 100 && (long)(x1 - x0) * 4 >= (long)W * 3 && rows * 2 >= H;
}
static int pace = 0;          /* PACE: follow the panel (see the header) */
static int clean_ms = 0;      /* CLEAN: 16-gray clean-up after fast refreshes, 0 = off */
static int cl_x0, cl_y0, cl_x1, cl_y1, cl_valid = 0;   /* panel area last drawn with DU and not cleaned yet */
static long long cl_last = 0;
static int g_cleaning = 0;
/* REAGL <pct> / --reagl-min: in the balanced mode a frame whose changed area is at least this share of the screen is drawn
   with REAGL (16 grays, cleans ghosts, 205 ms on a PW6) instead of DU (2 levels, 134 ms, leaves the old picture shining
   through when it replaces text: the "ghost under the pulled-down panel"). 85 = the old rule (only near-full-screen changes);
   35 since fix/epd-flash: a page turn (35 % = page_pct) gets one 16-gray REAGL instead of 1-bit DU strips (a test PW6, 多看 at a large
   font: DU strips 412 ms tap -> panel done, REAGL 426 ms). */
static int reagl_min = 35;
/* HINT <ms> <pct>: for the next <ms>, frames that change at least <pct> % of the screen use REAGL (one shot, from VeraHome when
   its pull-down panel opens / closes: the panel covers ~60 % of the screen, under the default 85 % rule it went out as DU and the
   page underneath shone through). Page turns outside the window keep the normal rule. */
static int hint_pct = 0;
static int frame_big = 0;
static unsigned long n_sent = 0;   /* refreshes sent to the panel */
static const char *timing_flag = NULL;   /* when this file exists, wait for the panel after each refresh and log how long it took */
static int g_tlog = 0;                   /* cached once per main-loop turn / FIFO batch */
static int g_is_mtk = 0;      /* swipe animation is a MediaTek hwtcon feature; i.MX EPDC (PW2/PW3/PW4) has none */
static int g_rawmtk = 0;      /* MTK hwtcon panel FBInk does not know (e.g. Scribe with an unknown serial code): FBInk would send
                                  i.MX ioctls (dmesg "HWTCON ERR err cmd", nothing refreshes), so we refresh with the MTK ioctl ourselves */
static unsigned long n_err = 0;    /* refreshes the driver refused (logged, never silent) */
static int g_cfa = 0;         /* Colorsoft (MTK + CFA color panel, 32bpp): we send the refresh ourselves with EPDC_FLAG_SKIP_CFA */
static int last_swipe_dir = 0;
static int g_color = 0;       /* MTK color panel (Colorsoft): REAGL is GLRC16 there (GLR16 without SKIP_CFA logs an error per update) */
static int skip_cfa = 0;      /* --skip-cfa 1: experimental, off by default. On the cs-5192 emulator it removed the per-REAGL dmesg
                                 error but small refreshes took ~160 ms instead of ~60 ms (emulated MDP; real panel unknown) */
static int refresh_cfa(int x, int y, int w, int h, const FBInkConfig *c);

static int maybe_animate(FBInkConfig *c, int w, int h, int flash)
{
    int one = anim_next, od = once_dir, os = once_steps;
    anim_next = 0; once_dir = -1;
    if (flash || !g_is_mtk || !swipe_ok || swipe_broken) return 0;
    if (one && od >= 0) {
        /* SWIPE one-shot: its own direction / steps, independent of the ANIM settings */
        if (os < 1) os = 1;
        if (os > MTK_MAX_SWIPE) os = MTK_MAX_SWIPE;
        if (w < os || h < os) return 0;
        int d1 = rot_dir(od);
        if (fbink_mtk_set_swipe_data((MTK_SWIPE_DIRECTION_INDEX_T)d1, (uint8_t)os) != EXIT_SUCCESS) return 0;
        last_swipe_dir = d1; swipe_steps = os;
        c->is_animated = true;
        c->is_flashing = false;
        fprintf(stderr, "epd: swipe once dir=%d steps=%d\n", d1, os);
        return 1;
    }
    if (anim_mode == 0) return 0;
    if (anim_steps < 1) anim_steps = 1;
    if (anim_steps > MTK_MAX_SWIPE) anim_steps = MTK_MAX_SWIPE;
    int want = one || ((long)w * h > (long)W * H * anim_min_pct / 100);
    if (!want || w < anim_steps || h < anim_steps) return 0;
    swipe_steps = anim_steps;
    int dir = anim_dir;
    switch (anim_mode) {
    case 2: dir = 2; break;   /* left */
    case 3: dir = 3; break;   /* right */
    case 4: dir = 1; break;   /* up */
    case 5: dir = 0; break;   /* down */
    default: break;           /* 1: follow DIR from the touch daemon */
    }
    dir = rot_dir(dir);
    if (fbink_mtk_set_swipe_data((MTK_SWIPE_DIRECTION_INDEX_T)dir, (uint8_t)anim_steps) != EXIT_SUCCESS) return 0;
    last_swipe_dir = dir;
    c->is_animated = true;
    c->is_flashing = false;      /* FBInk sends a swipe as AUTO + PARTIAL; the driver turns it into GLR16 itself */
    fprintf(stderr, "epd: swipe mode=%d dir=%d steps=%d\n", anim_mode, dir, anim_steps);
    return 1;
}

static void on_sig(int s) { (void)s; quit = 1; }

/* 浅色过滤 thresholds, on the source luma (before the contrast curve, so a level means the same background colours at
   every contrast): weak catches near-white page tints and separators (#EBEBEB and lighter, beige #F5F0E6), medium the
   usual light grey backgrounds, dim layers and the light green "eye care" tint (#DCDCDC, #C7EDCC = luma 222 and
   lighter), strong also mid-light greys (#C8C8C8).
   Everything at or above the threshold becomes 255. 255 is a level of every output depth (Y4: 15, Y1: 1), so the
   hardware dithering adds nothing there: the filtered areas are flat white, their diffs vanish and they stop counting
   towards the "large change" that picks REAGL / the KFB_FLASH page count. */
static const int lightf_t[4] = { 256, 235, 220, 200 };
/* Text edges (2026-11-20, community "画质 + 跟随 still a little jagged"; fb captures of a test page on a test device, docs/text-sharpness.md):
   the anti-aliased edge of a glyph is a ramp of grays, and the panel keeps 16 of them. Two things in this curve cut that
   ramp off:
   - the light filter was a hard cut on every pixel: the light half of every glyph's edge (source >= threshold) became
     white too, so strokes went from dark straight to white. Now a light pixel is only filtered when none of its four
     neighbours is darker than the threshold - 24, i.e. flat light areas (page tints, separators, dim layers) still become
     pure white, the edge pixels next to a stroke keep their gray (lightf_pass, after the conversion);
   - 深色加深 multiplied the exponent on top of the contrast: 2.2 x 1.8 = 3.96 puts source 128 at 16 (black on the panel),
     so the dark half of the ramp went black as well. The combined exponent is capped at 2.5, the contrast's own maximum.
   On the test page, the share of white -> black edge steps with no gray between them: contrast 1.3 17 %, 1.3 + filter
   medium 27 % -> 17 %, 2.2 + darken + filter strong (a real user setting) 76 % -> 30 %. */
#define DARKEN_MAX_EXP 2.5
#define LIGHTF_EDGE 24
static int lf_on = 0;                 /* the light filter is applied by lightf_pass, not by the table */
static uint8_t lf_ct = 255, lf_cd = 255;   /* curve value of the threshold / of threshold - LIGHTF_EDGE (not inverted) */
static int lut_is_id = 0;             /* 1 when lut[i] == i for all i (skip redundant second-pass LUT in NEON) */

static void build_lut(double gamma)
{
    if (gamma < 1.0) gamma = 1.0;
    if (gamma > 2.5) gamma = 2.5;
    if (g_lightf < 0 || g_lightf > 3) g_lightf = 0;
    /* 深色加深: one more power on top of the contrast curve (light grey text #999 -> about #4D at contrast 1.3), capped (see
       above) */
    double e = gamma * (g_darken ? 1.8 : 1.0);
    if (e > DARKEN_MAX_EXP) e = DARKEN_MAX_EXP;
    for (int i = 0; i < 256; i++)
        lut[i] = (uint8_t)(pow(i / 255.0, e) * 255.0 + 0.5);
    int t = lightf_t[g_lightf];
    lf_on = t <= 255;
    if (lf_on) { lf_ct = lut[t]; lf_cd = lut[t - LIGHTF_EDGE]; }
    if (g_invert)
        for (int i = 0; i < 256; i++) lut[i] = (uint8_t)(255 - lut[i]);
    int id = 1;
    for (int i = 0; i < 256; i++) if (lut[i] != (uint8_t)i) { id = 0; break; }
    lut_is_id = id;
}

/* the light filter on converted gray rows y0..y1 of img (w x h, stride): a pixel at least as light as the threshold
   becomes white unless one of its four neighbours is darker than the threshold - LIGHTF_EDGE (it is part of an edge).
   In place: a pixel it turns white was not dark before either, so the order does not change the result. Pure white
   pixels are skipped, so a white page costs one compare per pixel. */
static void lightf_pass(uint8_t *img, int stride, int w, int h, int y0, int y1)
{
    if (!lf_on || w <= 0) return;
    const uint8_t m = g_invert ? 0xFF : 0;     /* v ^ m = the value before the inversion */
    const uint8_t ct = lf_ct, cd = lf_cd, white = (uint8_t)(255 ^ m);
#ifdef __ARM_NEON
    const uint8x16_t vm = vdupq_n_u8(m), vct = vdupq_n_u8(ct), vcd = vdupq_n_u8(cd), vw = vdupq_n_u8(white), v255 = vdupq_n_u8(255);
#endif
    if (y0 < 0) y0 = 0;
    if (y1 > h) y1 = h;
    for (int y = y0; y < y1; y++) {
        uint8_t *r = img + (size_t)y * stride;
        const uint8_t *up = y > 0 ? r - stride : NULL, *dn = y + 1 < h ? r + stride : NULL;
#define LF_PIX(x) do { uint8_t u_ = r[x] ^ m;                                                                      \
            if (u_ >= ct && u_ != 255 &&                                                                           \
                !(((x) > 0 && (uint8_t)(r[(x) - 1] ^ m) < cd) || ((x) + 1 < w && (uint8_t)(r[(x) + 1] ^ m) < cd) ||   \
                  (up && (uint8_t)(up[x] ^ m) < cd) || (dn && (uint8_t)(dn[x] ^ m) < cd)))                        \
                r[x] = white;                                                                                      \
        } while (0)
        int x = 0;
        LF_PIX(0);
        x = 1;
#ifdef __ARM_NEON
        /* 16 pixels at a time; the left neighbour may already be filtered, which (see above) does not change anything */
        for (; x + 17 <= w; x += 16) {
            uint8x16_t c = vld1q_u8(r + x), u = veorq_u8(c, vm);
            uint8x16_t cand = vbicq_u8(vcgeq_u8(u, vct), vceqq_u8(u, v255));
            uint64x2_t t = vreinterpretq_u64_u8(cand);
            if (!(vgetq_lane_u64(t, 0) | vgetq_lane_u64(t, 1))) continue;     /* white, or darker than the threshold */
            uint8x16_t dk = vorrq_u8(vcltq_u8(veorq_u8(vld1q_u8(r + x - 1), vm), vcd), vcltq_u8(veorq_u8(vld1q_u8(r + x + 1), vm), vcd));
            if (up) dk = vorrq_u8(dk, vcltq_u8(veorq_u8(vld1q_u8(up + x), vm), vcd));
            if (dn) dk = vorrq_u8(dk, vcltq_u8(veorq_u8(vld1q_u8(dn + x), vm), vcd));
            vst1q_u8(r + x, vbslq_u8(vbicq_u8(cand, dk), vw, c));
        }
#endif
        for (; x < w; x++) LF_PIX(x);
#undef LF_PIX
    }
}

static long long now_ms(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL;
}

/* RGBA (little endian: bytes R,G,B,A) -> Y8 through the contrast curve, composited on white */
static void rgba_to_y8_opaque(const uint8_t *src, int stride, int x, int y, int w, int h, uint8_t *dst)
{
    for (int j = 0; j < h; j++) {
        const uint8_t *p = src + (size_t)(y + j) * stride + (size_t)x * 4;
        uint8_t *o = dst + (size_t)j * w;
        int i = 0;
#ifdef __ARM_NEON
        /* 16 pixels at a time: the luma with NEON (77 + 151 + 28 = 256, so it fits 16 bits), then the contrast curve
           from the table. The whole 1272x1696 frame is converted for every frame Android posts. */
        const uint8x8_t cr = vdup_n_u8(77), cg = vdup_n_u8(151), cb = vdup_n_u8(28);
        for (; i + 16 <= w; i += 16, p += 64) {
            uint8x16x4_t v = vld4q_u8(p);
            uint16x8_t lo = vmull_u8(vget_low_u8(v.val[0]), cr), hi = vmull_u8(vget_high_u8(v.val[0]), cr);
            lo = vmlal_u8(lo, vget_low_u8(v.val[1]), cg); hi = vmlal_u8(hi, vget_high_u8(v.val[1]), cg);
            lo = vmlal_u8(lo, vget_low_u8(v.val[2]), cb); hi = vmlal_u8(hi, vget_high_u8(v.val[2]), cb);
            vst1q_u8(o + i, vcombine_u8(vshrn_n_u16(lo, 8), vshrn_n_u16(hi, 8)));
        }
        if (!lut_is_id) for (int k = 0; k < i; k++) o[k] = lut[o[k]];
#endif
        for (; i < w; i++, p += 4)
            o[i] = lut[(77u * p[0] + 151u * p[1] + 28u * p[2]) >> 8];
    }
}

/* Fast scroll path (DU / A2 1-bit): pure luma (+ optional invert) without the per-pixel scalar LUT curve or light filter.
   scroll_cleanup / the first non-scroll frame re-converts with the full LUT + light filter for the settled 16-gray pass. */
static void rgba_to_y8_fast(const uint8_t *src, int stride, int x, int y, int w, int h, uint8_t *dst)
{
    const uint8_t inv = g_invert ? 0xFF : 0;
    for (int j = 0; j < h; j++) {
        const uint8_t *p = src + (size_t)(y + j) * stride + (size_t)x * 4;
        uint8_t *o = dst + (size_t)j * w;
        int i = 0;
#ifdef __ARM_NEON
        const uint8x8_t cr = vdup_n_u8(77), cg = vdup_n_u8(151), cb = vdup_n_u8(28);
        const uint8x16_t vinv = vdupq_n_u8(inv);
        for (; i + 16 <= w; i += 16, p += 64) {
            uint8x16x4_t v = vld4q_u8(p);
            uint16x8_t lo = vmull_u8(vget_low_u8(v.val[0]), cr), hi = vmull_u8(vget_high_u8(v.val[0]), cr);
            lo = vmlal_u8(lo, vget_low_u8(v.val[1]), cg); hi = vmlal_u8(hi, vget_high_u8(v.val[1]), cg);
            lo = vmlal_u8(lo, vget_low_u8(v.val[2]), cb); hi = vmlal_u8(hi, vget_high_u8(v.val[2]), cb);
            uint8x16_t y8 = vcombine_u8(vshrn_n_u16(lo, 8), vshrn_n_u16(hi, 8));
            if (inv) y8 = veorq_u8(y8, vinv);
            vst1q_u8(o + i, y8);
        }
#endif
        for (; i < w; i++, p += 4)
            o[i] = (uint8_t)(((77u * p[0] + 151u * p[1] + 28u * p[2]) >> 8) ^ inv);
    }
}

static void rgba_to_y8(const uint8_t *src, int stride, int x, int y, int w, int h, uint8_t *dst)
{
    for (int j = 0; j < h; j++) {
        const uint8_t *p = src + (size_t)(y + j) * stride + (size_t)x * 4;
        uint8_t *o = dst + (size_t)j * w;
        for (int i = 0; i < w; i++, p += 4) {
            unsigned a = p[3];
            unsigned r = p[0], g = p[1], b = p[2];
            unsigned yy = (77 * r + 151 * g + 28 * b) >> 8;
            if (a != 255) yy = (yy * a + 255 * (255 - a)) / 255;   /* over white */
            o[i] = lut[yy];
        }
    }
}

/* logical rectangle (x, y, w, h) -> the panel rectangle it lands on */
static void rot_rect(int x, int y, int w, int h, int *px, int *py, int *pw, int *ph)
{
    switch (g_rot) {
    case 90:  *px = VW - y - h; *py = x;        *pw = h; *ph = w; break;
    case 180: *px = VW - x - w; *py = H - y - h; *pw = w; *ph = h; break;
    case 270: *px = y;         *py = H - x - w; *pw = h; *ph = w; break;
    default:  *px = x; *py = y; *pw = w; *ph = h; break;
    }
}

/* convert the logical rectangle (x, y, w, h) of an RGBA page (stride LW*4) into the panel-space gray picture dst
   (W x H, stride W), turned by g_rot. Logical (lx, ly) lands on the panel at
     90: (VW-1-ly, lx)   180: (VW-1-lx, H-1-ly)   270: (ly, H-1-lx)
   The caller keeps x + w <= VW for 180 (columns beyond are not on the panel).
   90 / 270 walk the source in 16-row bands so the scattered writes stay in the cache. */
static void rgba_to_y8_rot(const uint8_t *src, int x, int y, int w, int h, uint8_t *dst)
{
    const int stride = LW * 4;
    if (g_rot == 180) {
        for (int j = 0; j < h; j++) {
            const uint8_t *p = src + (size_t)(y + j) * stride + (size_t)x * 4;
            uint8_t *o = dst + (size_t)(H - 1 - (y + j)) * W + (VW - 1 - x);
            for (int i = 0; i < w; i++, p += 4)
                *o-- = lut[(77u * p[0] + 151u * p[1] + 28u * p[2]) >> 8];
        }
        return;
    }
    for (int j0 = 0; j0 < h; j0 += 16) {
        int j1 = j0 + 16 < h ? j0 + 16 : h;
        for (int i = 0; i < w; i++) {
            int lx = x + i;
            for (int j = j0; j < j1; j++) {
                int ly = y + j;
                const uint8_t *p = src + (size_t)ly * stride + (size_t)lx * 4;
                size_t o = g_rot == 90 ? (size_t)lx * W + (VW - 1 - ly) : (size_t)(H - 1 - lx) * W + ly;
                dst[o] = lut[(77u * p[0] + 151u * p[1] + 28u * p[2]) >> 8];
            }
        }
    }
}

/* ---- which parts of the panel are still refreshing ----
   Every refresh we send is remembered with its marker; a thread waits for the driver to report it done
   (MXCFB_WAIT_FOR_UPDATE_COMPLETE, the same call for hwtcon and mxc_epdc) and wakes the main loop through a pipe.
   The main loop uses it for PACE and CLEAN, and the log gets "panel done" lines while <ctl>/timing exists - without
   blocking the main loop (the old timing code waited after every region, which made several regions look serial). */
#define MAXBUSY 32
struct busy { uint32_t marker; int x0, y0, x1, y1; long long t0; int done, anim; };
static struct busy busyq[MAXBUSY];
static int nbusy = 0;
static pthread_mutex_t busy_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t busy_cv = PTHREAD_COND_INITIALIZER;
static int busy_pipe[2] = { -1, -1 };
static int waiter_on = 0;
static int wait_marker(uint32_t marker);
static uint32_t last_marker(void);

static void *waiter_main(void *arg)
{
    (void)arg;
    for (;;) {
        pthread_mutex_lock(&busy_mu);
        int i;
        for (;;) {
            for (i = 0; i < nbusy && busyq[i].done; i++) {}
            if (i < nbusy) break;
            pthread_cond_wait(&busy_cv, &busy_mu);
        }
        uint32_t m = busyq[i].marker;
        int m_anim = busyq[i].anim;      /* kept here: busy_add drops entries older than 3 s while we wait */
        long long m_t0 = busyq[i].t0;
        pthread_mutex_unlock(&busy_mu);
        int wr = wait_marker(m);
        long long t = now_ms();
        if (m_anim && !swipe_broken && (wr < 0 || t - m_t0 >= SWIPE_SLOW_MS)) {
            swipe_broken = 1; swipe_recover = 1;
            fprintf(stderr, "[%lld] epd: swipe did not complete (%lld ms, wait rc=%d): hardware swipe off for this run, full refresh\n", t, t - m_t0, wr);
        }
        pthread_mutex_lock(&busy_mu);
        for (i = 0; i < nbusy; i++)
            if (busyq[i].marker == m && !busyq[i].done) {
                busyq[i].done = 1;
                if (timing_flag && access(timing_flag, F_OK) == 0)
                    fprintf(stderr, "[%lld] epd: panel done after %lld ms (wait rc=%d) %dx%d+%d+%d\n", t, t - busyq[i].t0, wr,
                            busyq[i].x1 - busyq[i].x0, busyq[i].y1 - busyq[i].y0, busyq[i].x0, busyq[i].y0);
            }
        pthread_mutex_unlock(&busy_mu);
        if (busy_pipe[1] >= 0) { char b = 1; if (write(busy_pipe[1], &b, 1) < 0) {} }
    }
    return NULL;
}

static void busy_add(int x, int y, int w, int h, long long t0, int anim)
{
    if (!waiter_on) return;
    pthread_mutex_lock(&busy_mu);
    int k = 0;
    for (int i = 0; i < nbusy; i++)            /* forget finished entries, and (the driver never takes that long) very old ones */
        if (!busyq[i].done && t0 - busyq[i].t0 < 3000) busyq[k++] = busyq[i];
    nbusy = k;
    if (nbusy == MAXBUSY) { memmove(busyq, busyq + 1, sizeof(busyq[0]) * (MAXBUSY - 1)); nbusy--; }
    struct busy *b = &busyq[nbusy++];
    b->marker = last_marker(); b->x0 = x; b->y0 = y; b->x1 = x + w; b->y1 = y + h; b->t0 = t0; b->done = 0; b->anim = anim;
    pthread_cond_signal(&busy_cv);
    pthread_mutex_unlock(&busy_mu);
}

/* how many pixels of the rectangle are inside a refresh that is still running */
static long busy_overlap(int x0, int y0, int x1, int y1, long long now)
{
    long sum = 0;
    pthread_mutex_lock(&busy_mu);
    for (int i = 0; i < nbusy; i++) {
        const struct busy *b = &busyq[i];
        if (b->done || now - b->t0 >= 3000) continue;
        int ax = x0 > b->x0 ? x0 : b->x0, ay = y0 > b->y0 ? y0 : b->y0;
        int bx = x1 < b->x1 ? x1 : b->x1, by = y1 < b->y1 ? y1 : b->y1;
        if (bx > ax && by > ay) sum += (long)(bx - ax) * (by - ay);
    }
    pthread_mutex_unlock(&busy_mu);
    return sum;
}
static int busy_any(long long now)
{
    int n = 0;
    pthread_mutex_lock(&busy_mu);
    for (int i = 0; i < nbusy; i++) if (!busyq[i].done && now - busyq[i].t0 < 3000) n++;
    pthread_mutex_unlock(&busy_mu);
    return n;
}

enum { M_AUTO, M_FULL, M_DU, M_GC16, M_REAGL, M_CLEAN, M_QPASS };

/* waveform + update mode for one refresh. FBInk maps is_flashing to UPDATE_MODE_FULL. REAGL is always FULL (see the
   header: that is what the driver and the Kindle UI do, and it does not flash). WFM_AUTO stays PARTIAL so the driver
   decides; it also gets no dithering, which would hide the gray levels the driver's histogram looks at. */
static void set_wfm(FBInkConfig *c, int mode, WFM_MODE_INDEX_T auto_wfm)
{
    c->is_flashing = false;
    switch (mode) {
    case M_FULL:  c->is_flashing = true; c->wfm_mode = WFM_GC16; break;
    case M_DU:    c->wfm_mode = WFM_DU; break;
    case M_GC16:  c->wfm_mode = WFM_GC16; break;
    case M_REAGL: c->wfm_mode = WFM_REAGL; break;
    /* FULL: every pixel of the area is driven. PARTIAL only drives pixels whose value changed, and after a DU refresh the
       driver remembers the gray value it was given (Y4; only Y1 dithering stores 1-bit), so a GL16 PARTIAL of the same
       picture changed nothing: the edges DU drew in black / white stayed that way (hwtcon_pipeline_config.c:1031 / mxc_epdc
       alike). GL16 does not drive white -> white, so FULL does not flash; it takes as long as PARTIAL (205 ms on PW6). */
    case M_CLEAN: c->wfm_mode = WFM_GL16; c->is_flashing = true; break;
    case M_QPASS: c->wfm_mode = no_reagl ? WFM_GL16 : WFM_REAGL; c->is_flashing = true; break;   /* FULL: every pixel */
    default:      c->wfm_mode = auto_wfm; break;
    }
    if (c->wfm_mode == WFM_REAGL && no_reagl) c->wfm_mode = WFM_GL16;
    if (c->wfm_mode == WFM_REAGL) c->is_flashing = true;
    if (c->wfm_mode == WFM_REAGL && g_color && !g_cfa) c->wfm_mode = WFM_GLRC16;   /* still FULL, as the driver wants it */
    if (c->wfm_mode == WFM_AUTO) c->dithering_mode = HWD_PASSTHROUGH;
}
static void push_region(int x0, int y0, int x1, int y1, int mode);
static int img_box(const uint8_t *buf, int w, int h, int *bx0, int *by0, int *bx1, int *by1);
/* No hardware dithering for text (KOReader's rule too: dithering is for pictures). On a glyph edge the MDP's Y4 dithering
   scatters the edge grays into a dot pattern along the stroke, and Y1 (DU on PW6) turns the whole anti-aliased edge
   into dots; a plain cut to 16 levels (or DU's own black / white) gives a clean edge. So an update keeps the dithering
   only when it holds a picture (img_box: a block of 32x32 tiles that are mostly mid-gray). Colour panels keep their own
   handling. */
static unsigned long n_text_nodither = 0;
static void text_no_dither(FBInkConfig *c, const uint8_t *buf, int w, int h)
{
    if (c->dithering_mode == HWD_PASSTHROUGH || g_color) return;
    int a, b, d, e;
    if (w >= 64 && h >= 64 && img_box(buf, w, h, &a, &b, &d, &e) > 0) return;
    c->dithering_mode = HWD_PASSTHROUGH;
    n_text_nodither++;
}

static int push(const uint8_t *rgba, int x, int y, int w, int h, int mode)
{
    if (g_rot) {
        /* rotated: clip in the logical picture, convert into the panel-space picture, send the panel rectangle */
        if (x < 0) { w += x; x = 0; }
        if (y < 0) { h += y; y = 0; }
        if (x + w > LW) w = LW - x;
        if (y + h > LH) h = LH - y;
        if (g_rot == 180 && x + w > VW) w = VW - x;
        if (w <= 0 || h <= 0 || !cur_y8) return 0;
        rgba_to_y8_rot(rgba, x, y, w, h, cur_y8);
        int px, py, pw, ph;
        rot_rect(x, y, w, h, &px, &py, &pw, &ph);
        lightf_pass(cur_y8, W, W, H, py, py + ph);
        push_region(px, py, px + pw, py + ph, mode);
        return 0;
    }
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > W) w = W - x;
    if (y + h > H) h = H - y;
    if (w <= 0 || h <= 0) return 0;

    uint8_t *buf = malloc((size_t)w * h);
    if (!buf) return -1;
    rgba_to_y8(rgba, W * 4, x, y, w, h, buf);
    lightf_pass(buf, w, w, h, 0, h);

    FBInkConfig c = cfg;
    c.dithering_mode = dither;
    c.ignore_alpha = true;
    c.no_refresh = false;
    c.is_flashing = false;
    int large = (long)w * h > (long)W * H * 4 / 10;
    int flash = mode == M_FULL;
    set_wfm(&c, mode, large ? WFM_REAGL : WFM_DU);
    text_no_dither(&c, buf, w, h);
    int anim = maybe_animate(&c, w, h, flash);
    long long t0 = now_ms();
    if (g_cfa || g_rawmtk) c.no_refresh = true;
    int rc = fbink_print_raw_data(fbfd, buf, w, h, (size_t)w * h, (short)x, (short)y, &c);
    if ((g_cfa || g_rawmtk) && rc >= 0) rc = refresh_cfa(x, y, w, h, &c);
    fprintf(stderr, "epd: %dx%d+%d+%d mode=%d wfm=%d full=%d anim=%d -> %d (%lld ms)\n", w, h, x, y, mode, c.wfm_mode, c.is_flashing, anim, rc, now_ms() - t0);
    free(buf);
    return rc;
}

/* MXCFB_SET/GET_PWRDOWN_DELAY: _IOW/_IOR('F', 0x30/0x31, int32_t) in both hwtcon_ioctl_cmd.h and mxcfb.h */
#define VD_SET_PWRDOWN_DELAY _IOW('F', 0x30, int32_t)
#define VD_GET_PWRDOWN_DELAY _IOR('F', 0x31, int32_t)
static void set_pwrdown(int ms)
{
    if (fbfd < 0) return;
    if (!pwrdown_saved) {
        int32_t o = 0;
        if (ioctl(fbfd, VD_GET_PWRDOWN_DELAY, &o) < 0) { fprintf(stderr, "epd: GET_PWRDOWN_DELAY: %s\n", strerror(errno)); return; }
        pwrdown_orig = o; pwrdown_saved = 1;
    }
    int32_t v = ms < 0 ? pwrdown_orig : ms;
    int rc = ioctl(fbfd, VD_SET_PWRDOWN_DELAY, &v);
    fprintf(stderr, "epd: power-down delay %d ms (driver had %d) -> %d\n", (int)v, pwrdown_orig, rc);
}
static void restore_pwrdown(void)
{
    if (pwrdown_saved && fbfd >= 0) { int32_t v = pwrdown_orig; ioctl(fbfd, VD_SET_PWRDOWN_DELAY, &v); }
}

static int open_fb(void)
{
    memset(&cfg, 0, sizeof(cfg));
    cfg.is_quiet = true;
    fbfd = fbink_open();
    if (fbfd < 0) { fprintf(stderr, "epd: cannot open framebuffer\n"); return -1; }
    if (fbink_init(fbfd, &cfg) < 0) { fprintf(stderr, "epd: fbink_init failed\n"); return -1; }
    FBInkState st;
    fbink_get_state(&cfg, &st);
    g_is_mtk = st.is_mtk;
    g_cfa = skip_cfa && st.is_mtk && st.has_color_panel && st.bpp == 32;
    g_color = st.is_mtk && st.has_color_panel;
    if (!st.is_mtk) {
        struct fb_fix_screeninfo fix;
        memset(&fix, 0, sizeof(fix));
        if (ioctl(fbfd, FBIOGET_FSCREENINFO, &fix) == 0 && strncmp(fix.id, "hwtcon", 6) == 0) {
            g_rawmtk = 1; g_is_mtk = 1;
            fprintf(stderr, "epd: fb id '%.16s' is MTK hwtcon but FBInk does not know this device: refreshing with the MTK ioctl ourselves\n", fix.id);
        }
    }
    if (g_cfa) fprintf(stderr, "epd: color panel at 32bpp: refreshing with SKIP_CFA ourselves\n");
    if (!g_is_mtk) {
        /* MXCFB_GET_WAVEFORM_TYPE _IOR('F', 0x39, u32): WAVEFORM_TYPE_4BIT 1 / 5BIT 2 (include/linux/mxcfb.h of the 3.x / 4.x
           Kindle kernels; REAGL needs the 5-bit file). Kernels without it (or legacy einkfb) keep REAGL. */
        uint32_t wt = 0;
        if (ioctl(fbfd, _IOR('F', 0x39, uint32_t), &wt) == 0 && wt == 1) no_reagl = 1;
        fprintf(stderr, "epd: waveform type %u%s\n", wt, no_reagl ? " (4-bit: REAGL -> GL16)" : "");
    }
    fprintf(stderr, "epd: %s (%s/%s) %ux%u %ubpp mtk=%d\n", st.device_name, st.device_codename, st.device_platform,
            st.screen_width, st.screen_height, st.bpp, st.is_mtk);
    fprintf(stderr, "epd: anim_mode=%d dir=%d steps=%d min=%d%% swipe=%d\n", anim_mode, anim_dir, anim_steps, anim_min_pct, swipe_ok);
    if (g_rot) fprintf(stderr, "epd: rotate %d: logical %dx%d on panel %dx%d (visible width %d)\n", g_rot, LW, LH, W, H, VW);
    return 0;
}

static void fill_test(uint8_t *img)
{
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            uint8_t v;
            if (y < 200)              v = (uint8_t)(x * 255 / (W - 1));                 /* gray ramp */
            else if (y < 700)         v = (((x / 64) + ((y - 200) / 64)) & 1) ? 0 : 255; /* checkerboard */
            else if (y < 1000)        v = (uint8_t)(((x * 16 / W) * 255) / 15);          /* 16 gray steps */
            else                      v = ((x + y) % 40 < 3) ? 0 : 255;                  /* diagonal lines */
            if (x < 6 || y < 6 || x >= W - 6 || y >= H - 6) v = 0;                       /* border */
            uint8_t *p = img + ((size_t)y * W + x) * 4;
            p[0] = p[1] = p[2] = v; p[3] = 255;
        }
}


/* send a gray region that already sits in a contiguous buffer */
/* Colorsoft: FBInk's MTK refresh never sets EPDC_FLAG_SKIP_CFA, so on the 32bpp color panel every refresh of our gray
   picture goes through the CFA color pipeline, and every REAGL logs "GLR16 used without SKIP_CFA, GLRC16 should be used for
   color content" (hwtcon_v2/hwtcon_core.c hwtcon_core_use_cfa_color_and_regal). Our picture is gray by construction, so
   FBInk only writes the pixels (no_refresh) and we send MXCFB_SEND_UPDATE ourselves with SKIP_CFA. The struct is
   mxcfb_update_data from include/linux/hwtcon_ioctl_cmd.h (96 bytes, the size is part of the ioctl number). */
struct vd_rect { uint32_t top, left, width, height; };
struct vd_mtk_update {
    struct vd_rect region;
    uint32_t waveform_mode, update_mode, update_marker;
    int temp;
    unsigned int flags;
    int dither_mode, quant_bit;
    struct { uint32_t phys_addr, width, height; struct vd_rect r; } alt;
    struct { uint32_t direction, steps; } swipe;
    uint32_t hist_bw, hist_gray, ts_pxp, ts_epdc;
};
struct vd_marker { uint32_t marker, collision; };
#define VD_SEND_UPDATE_MTK   _IOW('F', 0x2E, struct vd_mtk_update)
#define VD_WAIT_COMPLETE_MTK _IOWR('F', 0x2F, struct vd_marker)
static uint32_t cfa_marker = 0x56440000;
static int refresh_cfa(int x, int y, int w, int h, const FBInkConfig *c)
{
    struct vd_mtk_update u;
    memset(&u, 0, sizeof(u));
    u.region.top = (uint32_t)y; u.region.left = (uint32_t)x; u.region.width = (uint32_t)w; u.region.height = (uint32_t)h;
    switch (c->wfm_mode) {
    case WFM_DU:    u.waveform_mode = 1; break;
    case WFM_A2:    u.waveform_mode = 6; break;     /* hwtcon A2 */
    case WFM_GC16:  u.waveform_mode = 2; break;
    case WFM_GL16:  u.waveform_mode = 3; break;
    case WFM_REAGL: u.waveform_mode = 4; break;     /* GLR16: gray REAGL, fine with SKIP_CFA */
    default:        u.waveform_mode = 257; break;   /* AUTO */
    }
    u.update_mode = c->is_flashing ? 1 : 0;
    u.update_marker = ++cfa_marker;
    u.temp = 0x1000;                                 /* TEMP_USE_AMBIENT */
    u.flags = g_cfa ? 0x10 : 0;                      /* EPDC_FLAG_SKIP_CFA, only on the color panel */
    if (c->dithering_mode != HWD_PASSTHROUGH && u.waveform_mode != 257)
        u.flags |= (g_cfa && u.waveform_mode == 1) ? 0x2000 : 0x4000;   /* Y1 for DU on Bellatrix4, Y4 otherwise, as FBInk does */
    if (c->is_animated) {
        int ok = (last_swipe_dir <= 1) ? h >= swipe_steps : w >= swipe_steps;
        if (ok) {
            u.flags |= 0x10000;                      /* EPDC_FLAG_ENABLE_SWIPE; the driver switches to REGAL GLR16 itself */
            u.swipe.direction = (uint32_t)last_swipe_dir; u.swipe.steps = (uint32_t)swipe_steps;
            u.waveform_mode = 257; u.update_mode = 0;
        }
    }
    u.hist_bw = u.waveform_mode == 4 ? 4 : 1;
    u.hist_gray = u.waveform_mode == 4 ? 4 : 2;
    if (ioctl(fbfd, VD_SEND_UPDATE_MTK, &u) < 0) {
        int e = errno;
        n_err++;
        if (n_err <= 20 || n_err % 100 == 0)
            fprintf(stderr, "epd: refresh error #%lu: SEND_UPDATE_MTK %dx%d+%d+%d wfm=%u flags=0x%x: %s\n", n_err, w, h, x, y,
                    u.waveform_mode, u.flags, strerror(e));
        return -1;
    }
    return 0;
}
static uint32_t last_marker(void) { return (g_cfa || g_rawmtk) ? cfa_marker : fbink_get_last_marker(); }
static int wait_marker(uint32_t marker)
{
    if (!g_cfa && !g_rawmtk) return fbink_wait_for_complete(fbfd, marker);
    struct vd_marker m = { marker, 0 };
    return ioctl(fbfd, VD_WAIT_COMPLETE_MTK, &m) < 0 ? -errno : 0;
}

/* share (%) of mid-gray pixels, every 4th pixel of every 4th row */
static int gray_share(const uint8_t *buf, int w, int h)
{
    long n = 0, g = 0;
    for (int j = 0; j < h; j += 4)
        for (int i = j & 3; i < w; i += 4) { uint8_t v = buf[(size_t)j * w + i]; n++; if (v >= 32 && v <= 223) g++; }
    return n ? (int)(g * 100 / n) : 0;
}

/* the bounding box (relative to buf) of the 32x32 tiles that are a picture: at least half of the sampled pixels (every 2nd
   pixel of every 2nd row) mid-gray. Anti-aliased text has far less (CJK at 1272x1696: 5-20 % per tile). Returns the
   number of picture tiles; fewer than 4 (a 64x64 icon) do not count, nor tiles scattered thinly over a large box. */
#define QP_TILE 32
static int img_box(const uint8_t *buf, int w, int h, int *bx0, int *by0, int *bx1, int *by1)
{
    int n = 0, x0 = w, y0 = h, x1 = 0, y1 = 0;
    for (int ty = 0; ty + QP_TILE / 2 <= h; ty += QP_TILE)
        for (int tx = 0; tx + QP_TILE / 2 <= w; tx += QP_TILE) {
            int ex = tx + QP_TILE < w ? tx + QP_TILE : w, ey = ty + QP_TILE < h ? ty + QP_TILE : h, s = 0, g = 0;
            for (int j = ty; j < ey; j += 2) {
                const uint8_t *r = buf + (size_t)j * w;
                for (int i = tx + (j & 2 ? 1 : 0); i < ex; i += 2) { s++; if (r[i] >= 40 && r[i] <= 215) g++; }
            }
            if (s && g * 2 >= s) {
                n++;
                if (tx < x0) x0 = tx;
                if (ty < y0) y0 = ty;
                if (ex > x1) x1 = ex;
                if (ey > y1) y1 = ey;
            }
        }
    if (n < 4) return 0;
    /* a picture is a solid block of such tiles; gray text (晋江: anti-aliased #555 strokes) makes a few scattered ones
       over a whole page. At least a quarter of the tiles in the box must be picture tiles. */
    long bt = (long)((x1 - x0 + QP_TILE - 1) / QP_TILE) * ((y1 - y0 + QP_TILE - 1) / QP_TILE);
    if ((long)n * 4 < bt) return 0;
    *bx0 = x0; *by0 = y0; *bx1 = x1; *by1 = y1;
    return n;
}
/* remember a refresh of the panel rectangle (x, y, w, h) from buf for the quality pass; full = it was a full-quality update */
static void qp_note(const uint8_t *buf, int x, int y, int w, int h, int full, int lowq, long long t,
                    int ib_done, int ib_nt, int a0, int b0, int a1, int b1)
{
    if (qpass_ms <= 0 || g_cleaning) return;
    if (full) {      /* a REAGL / GC16 FULL that covers the remembered picture leaves nothing to do */
        if (qp_valid && x <= qp_x0 && y <= qp_y0 && x + w >= qp_x1 && y + h >= qp_y1) { qp_valid = 0; qp_n = 0; qp_lowq = 0; }
        return;
    }
    int nt;
    /* a refresh that covers the whole remembered area replaced what was there: an animation frame with a gray shadow,
       a placeholder, a picture that is now gone. Only what this refresh brings counts from here on. */
    if (qp_valid && x <= qp_x0 && y <= qp_y0 && x + w >= qp_x1 && y + h >= qp_y1) { qp_valid = 0; qp_n = 0; qp_lowq = 0; }
    if (w < QP_TILE * 2 || h < QP_TILE * 2) return;
    nt = ib_done ? ib_nt : img_box(buf, w, h, &a0, &b0, &a1, &b1);
    if (!nt) return;
    a0 += x; a1 += x; b0 += y; b1 += y;
    if (g_tlog)
        fprintf(stderr, "[%lld] epd: picture %dx%d+%d+%d (%d tiles)%s\n", t, a1 - a0, b1 - b0, a0, b0, nt, lowq ? " low quality" : "");
    if (!qp_valid) { qp_x0 = a0; qp_y0 = b0; qp_x1 = a1; qp_y1 = b1; qp_valid = 1; qp_n = 1; qp_lowq = lowq; }
    else {
        if (a0 < qp_x1 && a1 > qp_x0 && b0 < qp_y1 && b1 > qp_y0) qp_n++;   /* overlaps a picture refreshed before: a picture loading in steps */
        if (a0 < qp_x0) qp_x0 = a0;
        if (b0 < qp_y0) qp_y0 = b0;
        if (a1 > qp_x1) qp_x1 = a1;
        if (b1 > qp_y1) qp_y1 = b1;
        qp_lowq |= lowq;
    }
    qp_last = t;
}
static int qp_due(void) { return qpass_ms > 0 && qp_valid && (qp_lowq || qp_n >= 2); }
static long long qp_at(void) { long long d = qp_last + qpass_ms; return d > qp_done + QP_GAP_MS ? d : qp_done + QP_GAP_MS; }

static int push_y8(const uint8_t *buf, int x, int y, int w, int h, int mode)
{
    FBInkConfig c = cfg;
    c.dithering_mode = dither;
    c.ignore_alpha = true;
    c.no_refresh = false;
    c.is_flashing = false;
    /* the slow, pretty waveform only for changes that cover nearly the whole screen (a page turn); everything else
       uses the fast one, otherwise a tap that touches the header and the list queues several slow refreshes */
    int large = frame_big || (long)w * h > (long)W * H * 85 / 100;
    int flash = mode == M_FULL;
    WFM_MODE_INDEX_T aw = ui_mode == 1 ? WFM_GC16 : ui_mode == 2 ? WFM_DU : ui_mode == 3 ? WFM_AUTO : (large ? WFM_REAGL : WFM_DU);
    if (g_scroll && scroll_a2 && mode == M_AUTO && !g_color) aw = WFM_A2;      /* touch scroll, A2 locked for the drag */
    else if ((g_burst || g_scroll) && mode == M_AUTO && ui_mode != 2) aw = WFM_DU;   /* burst / touch scroll: DU now, 16-gray pass later */
    else if (img_gray_pct > 0 && mode == M_AUTO && ui_mode == 0 && aw == WFM_DU && w >= 48 && h >= 48 && !g_cleaning &&
             gray_share(buf, w, h) >= img_gray_pct) aw = WFM_GL16;         /* a picture: 16 grays instead of a 1-bit dot pattern */
    set_wfm(&c, mode, aw);
    /* Compute img_box() once and reuse for both text_no_dither and qp_note */
    int ib_done = 0, ib_nt = 0, ib_x0 = 0, ib_y0 = 0, ib_x1 = 0, ib_y1 = 0;
    if (c.dithering_mode != HWD_PASSTHROUGH && !g_color) {
        if (w >= QP_TILE * 2 && h >= QP_TILE * 2) {
            ib_nt = img_box(buf, w, h, &ib_x0, &ib_y0, &ib_x1, &ib_y1);
            ib_done = 1;
        }
        if (ib_nt <= 0) {
            c.dithering_mode = HWD_PASSTHROUGH;
            n_text_nodither++;
        }
    }
    int anim = (g_cleaning || g_scroll) ? 0 : maybe_animate(&c, w, h, flash);      /* the clean-up pass / a scroll is never animated */
    long long t0 = now_ms();
    if (g_cfa || g_rawmtk) c.no_refresh = true;
    static uint8_t *mono_buf = NULL;
    static size_t mono_cap = 0;
    uint8_t *mono = NULL;     /* MONO: a DU refresh as clean two-tone (reuses persistent buffer to avoid per-frame mmap/page-faults) */
    if (mono_level > 0 && (c.wfm_mode == WFM_DU || c.wfm_mode == WFM_A2) && !g_cleaning && !g_color) {
        size_t need = (size_t)w * h;
        if (need > mono_cap) {
            uint8_t *nb = realloc(mono_buf, need);
            if (nb) { mono_buf = nb; mono_cap = need; }
        }
        if (mono_buf && mono_cap >= need) {
            mono = mono_buf;
            for (size_t i = 0; i < need; i++) mono[i] = buf[i] >= mono_level ? 255 : 0;
            c.dithering_mode = HWD_PASSTHROUGH;
        }
    }
    int rc = fbink_print_raw_data(fbfd, mono ? mono : buf, w, h, (size_t)w * h, (short)x, (short)y, &c);
    if ((g_cfa || g_rawmtk) && rc >= 0) rc = refresh_cfa(x, y, w, h, &c);
    /* flash=1 only for the GC16 full refresh that really flashes (REAGL is also sent FULL but does not flash) */
    int fl = c.is_flashing && c.wfm_mode == WFM_GC16;
    if (fl && rc >= 0) n_flash++;
    fprintf(stderr, "[%lld] epd: %dx%d+%d+%d mode=%d wfm=%d full=%d flash=%d anim=%d -> %d (%lld ms)%s\n", t0, w, h, x, y, mode, c.wfm_mode,
            c.is_flashing, fl, anim, rc, now_ms() - t0, fl ? " FLASH" : "");
    if (rc >= 0) {
        n_sent++;
        /* sent while an earlier refresh of the same pixels is still running on the panel: the driver merges the two
           (collision) and the partial waveforms leave pixels between the two pictures */
        int coll = !g_cleaning && qpass_ms > 0 && busy_overlap(x, y, x + w, y + h, t0) > 0;
        busy_add(x, y, w, h, t0, c.is_animated);
        {   /* quality pass bookkeeping: REAGL (sent FULL) and the GC16 FULL flash are full quality; GL16 FULL is full quality
               only where it stands in for REAGL (4-bit i.MX). 1-bit and collisions count as low quality; an MTK hardware swipe
               already runs as 16-gray GLR16, so on its own (ui_mode != 2, no collision) it does not force a second refresh of
               a clean single-frame illustrated page, while !full still tracks multi-step / colliding loads. */
            int fullq = c.is_flashing && (c.wfm_mode == WFM_GC16 || c.wfm_mode == WFM_REAGL || c.wfm_mode == WFM_GLRC16 ||
                                          c.wfm_mode == WFM_GL16);
            int onebit = (c.wfm_mode == WFM_DU || c.wfm_mode == WFM_A2) && (!c.is_animated || ui_mode == 2 || mono != NULL);
            int lowq = onebit || (c.is_animated && ui_mode == 2) || coll;   /* AUTO picks a 16-gray waveform for gray content itself */
            qp_note(buf, x, y, w, h, fullq && !c.is_animated, lowq, t0, ib_done, ib_nt, ib_x0, ib_y0, ib_x1, ib_y1);
        }
        if ((c.wfm_mode == WFM_DU || c.wfm_mode == WFM_A2) && mode != M_CLEAN) {          /* remember what was drawn 1-bit, for CLEAN */
            if (!cl_valid) { cl_x0 = x; cl_y0 = y; cl_x1 = x + w; cl_y1 = y + h; cl_valid = 1; }
            else {
                if (x < cl_x0) cl_x0 = x;
                if (y < cl_y0) cl_y0 = y;
                if (x + w > cl_x1) cl_x1 = x + w;
                if (y + h > cl_y1) cl_y1 = y + h;
            }
            cl_last = t0;
        } else if (cl_valid && x <= cl_x0 && y <= cl_y0 && x + w >= cl_x1 && y + h >= cl_y1) cl_valid = 0;   /* a gray refresh covered it */
    }
    return rc;
}

/* copy a rectangle of the wanted picture, send it, remember it as what is on the panel */
static void push_region(int x0, int y0, int x1, int y1, int mode)
{
    x0 &= ~7; x1 = (x1 + 7) & ~7;
    if (x1 > W) x1 = W;
    int w = x1 - x0, h = y1 - y0;
    if (w <= 0 || h <= 0) return;
    /* FBInk refuses a region less than one byte tall ("bogus empty region"), and a 1-row diff is not worth a special case */
    if (h < 8) {
        if (y0 + 8 <= H) y1 = y0 + 8;
        else { y0 = H > 8 ? H - 8 : 0; y1 = H; }
        h = y1 - y0;
    }
    static uint8_t *reg_buf = NULL;
    static size_t reg_cap = 0;
    const uint8_t *buf = cur_y8 + (size_t)y0 * W;      /* full-width regions are contiguous already */
    if (w != W) {
        size_t need = (size_t)w * h;
        if (need > reg_cap) {
            uint8_t *nb = realloc(reg_buf, need);
            if (!nb) return;
            reg_buf = nb; reg_cap = need;
        }
        for (int j = 0; j < h; j++)
            memcpy(reg_buf + (size_t)j * w, cur_y8 + (size_t)(y0 + j) * W + x0, (size_t)w);
        buf = reg_buf;
    }
    if (push_y8(buf, x0, y0, w, h, mode) >= 0) {
        for (int j = 0; j < h; j++)
            memcpy(prev_y8 + (size_t)(y0 + j) * W + x0, buf + (size_t)j * w, (size_t)w);
        prev_valid = 1;
    }
}

#define MAXCL 6
#define CLGAP 48   /* changed rows closer than this belong to the same refresh */

/* Tolerant change detection (TOL <0|1>, --tolerant, default on). What is sent to the panel is still every changed
   pixel; only the decisions "is this a page flip" (the KFB_FLASH count) and "animate it" look at changes the panel can
   actually show and that are not a fade (row_sig). A gray dim layer fading in over a page, anti-aliased glyph edges
   shifting a few values, a light background turning white are then no longer a "large change" and do not bring the
   next full flash closer; text that really moved still is. */
static int g_tolerant = 1;
#define SIG_PX 2       /* visible changes (in the sampled pixels, every 4th) a row needs */
#define SIG_MOVE 128  /* a pixel that changed by more than this is content that moved / appeared, not a fade (half the
                         range: with 浅色过滤 on, a dim layer over a light card can jump from white to ~200 in one go) */
/* 1 = the row has a visible change (>= SIG_PX pixels moved by more than one gray level) that is not a fade.
   Fade = every visible change goes the same way (all darker or all lighter) and none is larger than SIG_MOVE:
   a dim layer / scrim fading in or out over a page (8 % darker: white -> 235, #999 -> 141, black stays). Text that
   scrolls or a new page changes pixels both ways (or black <-> white) and still counts.
   A pixel that changed by more than SIG_MOVE ends the scan at once (text scrolled / a new page), so rows with real
   content changes cost a few pixels; only rows without one are read to the end, and only every 4th pixel of them
   (phase = row & 3, so neighbouring rows look at different columns): a fade covers whole areas, a real change has
   strokes in many rows. */
static int row_sig(const uint8_t *a, const uint8_t *b, int x0, int x1, int phase)
{
    int dark = 0, light = 0;
    for (int i = x0 + ((phase - x0) & 3); i < x1; i += 4) {
        int d = (int)a[i] - (int)b[i];
        if (d > 16) { if (d > SIG_MOVE) return 1; light++; }
        else if (d < -16) { if (d < -SIG_MOVE) return 1; dark++; }
    }
    if (dark + light < SIG_PX) return 0;
    return dark && light;      /* both ways: something moved; one way only: a fade */
}

/* convert page n, diff against the panel, refresh what changed - as several small rectangles when the changes are far
   apart (clock in the header + something at the bottom), not as one big one that would make the panel redraw the middle too */
/* returns 0 = drawn (or nothing to draw), 1 = small change, hold it a moment, 2 = PACE: the panel is still busy there,
   3 = page-count flash due (hold until still), 4 = edge strip after a quiet moment (page-slide hold) */
static int req_y0 = -1, req_y1 = 0;      /* rows the sender says changed (P n y0 y1); -1 = unknown, look at everything */
static int pend_y0 = -1, pend_y1 = 0;    /* rows converted for a frame that was kept back: still to be compared */
static int need_full = 1;                /* a frame was dropped (screensaver) or we just started: the next one must look at every row */
static int show_page_rows(const uint8_t *shm, int page, int mode, int force_full, int may_hold, int may_pace, int ly0, int ly1, int py0, int py1);
static int show_page(const uint8_t *shm, int page, int mode, int force_full, int may_hold, int may_pace)
{
    int ly0 = 0, ly1 = LH;
    if (req_y0 >= 0 && req_y1 > req_y0 && prev_valid && !force_full && !need_full) {
        ly0 = req_y0; ly1 = req_y1;
        if (pend_y0 >= 0) { if (pend_y0 < ly0) ly0 = pend_y0; if (pend_y1 > ly1) ly1 = pend_y1; }
    }
    if (fr_valid) { if (fr_y0 < ly0) ly0 = fr_y0; if (fr_y1 > ly1) ly1 = fr_y1; }
    if (ly0 < 0) ly0 = 0;
    if (ly1 > LH) ly1 = LH;
    if (ly1 < ly0) ly1 = ly0;
    /* light filter: whether a light pixel stays gray depends on the rows above and below it, so the rows next to the
       changed ones are converted (and compared) again */
    if (lf_on && ly1 > ly0) { if (ly0 > 0) ly0--; if (ly1 < LH) ly1++; }
    int py0 = ly0, py1 = ly1;
    if (g_rot) {
        if (ly1 <= ly0) { py0 = 0; py1 = 0; }
        else if (g_rot == 180) { py0 = H - ly1; py1 = H - ly0; }
        else { py0 = 0; py1 = H; }   /* 90 / 270: logical rows map to panel columns across all H panel rows */
    }
    int r = show_page_rows(shm, page, mode, force_full, may_hold, may_pace, ly0, ly1, py0, py1);
    g_scroll = 0;
    need_full = 0;
    if (r) { pend_y0 = ly0; pend_y1 = ly1; } else pend_y0 = -1;
    return r;
}
static int show_page_rows(const uint8_t *shm, int page, int mode, int force_full, int may_hold, int may_pace, int ly0, int ly1, int py0, int py1)
{
    long long tc0 = now_ms();
    last_page = page;
    int fast_scroll = (mode == M_AUTO && scroll_active(tc0) && !force_full && prev_valid && !g_rot);
    if (!fast_scroll && scroll_raw_y8) {
        /* first non-scroll frame after raw scroll: re-convert whole frame with full LUT + lightf */
        scroll_raw_y8 = 0;
        ly0 = 0; ly1 = LH; py0 = 0; py1 = H;
    }
    if (g_rot) {
        if (ly1 > ly0) {
            rgba_to_y8_rot(page_base(shm, page), 0, ly0, g_rot == 180 ? VW : LW, ly1 - ly0, cur_y8);
            lightf_pass(cur_y8, W, W, H, py0, py1);
        }
    } else if (py1 > py0) {
        if (fast_scroll) {
            rgba_to_y8_fast(page_base(shm, page), W * 4, 0, py0, W, py1 - py0, cur_y8 + (size_t)py0 * W);
            scroll_raw_y8 = 1;
        } else {
            rgba_to_y8_opaque(page_base(shm, page), W * 4, 0, py0, W, py1 - py0, cur_y8 + (size_t)py0 * W);
            lightf_pass(cur_y8, W, W, H, py0, py1);
        }
    }
    if (!prev_valid || force_full) {
        if (mode == M_FULL) flip_count = 0;
        push_region(0, 0, W, H, mode);
        return 0;
    }
    int cy0[MAXCL + 1], cy1[MAXCL + 1], cx0[MAXCL + 1], cx1[MAXCL + 1], nc = 0;
    int sy0[MAXCL + 1], sy1[MAXCL + 1], sx0[MAXCL + 1], sx1[MAXCL + 1], ns = 0;   /* the same, rows with visible changes only */
    struct timespec td0, td1;
    clock_gettime(CLOCK_MONOTONIC, &td0);
    for (int y = py0; y < py1; y++) {
        const uint8_t *a = cur_y8 + (size_t)y * W, *b = prev_y8 + (size_t)y * W;
        if (!memcmp(a, b, W)) continue;
        int xa = 0, xb = W - 1;
        while (xa + 4 <= W) {
            uint32_t va, vb;
            memcpy(&va, a + xa, sizeof(va));
            memcpy(&vb, b + xa, sizeof(vb));
            if (va != vb) break;
            xa += 4;
        }
        while (xa < W && a[xa] == b[xa]) xa++;
        while (xb >= xa + 4) {
            uint32_t va, vb;
            memcpy(&va, a + xb - 3, sizeof(va));
            memcpy(&vb, b + xb - 3, sizeof(vb));
            if (va != vb) break;
            xb -= 4;
        }
        while (xb > xa && a[xb] == b[xb]) xb--;
        if (g_tolerant && !fast_scroll && row_sig(a, b, xa, xb + 1, y)) {
            if (ns > 0 && y - sy1[ns - 1] <= CLGAP) {
                sy1[ns - 1] = y + 1;
                if (xa < sx0[ns - 1]) sx0[ns - 1] = xa;
                if (xb + 1 > sx1[ns - 1]) sx1[ns - 1] = xb + 1;
            } else if (ns < MAXCL) {
                sy0[ns] = y; sy1[ns] = y + 1; sx0[ns] = xa; sx1[ns] = xb + 1; ns++;
            } else {
                sy1[ns - 1] = y + 1;
                if (xa < sx0[ns - 1]) sx0[ns - 1] = xa;
                if (xb + 1 > sx1[ns - 1]) sx1[ns - 1] = xb + 1;
            }
        }
        if (nc > 0 && y - cy1[nc - 1] <= CLGAP) {
            cy1[nc - 1] = y + 1;
            if (xa < cx0[nc - 1]) cx0[nc - 1] = xa;
            if (xb + 1 > cx1[nc - 1]) cx1[nc - 1] = xb + 1;
        } else if (nc < MAXCL) {
            cy0[nc] = y; cy1[nc] = y + 1; cx0[nc] = xa; cx1[nc] = xb + 1; nc++;
        } else {   /* too many pieces: fold the rest into the last one */
            cy1[nc - 1] = y + 1;
            if (xa < cx0[nc - 1]) cx0[nc - 1] = xa;
            if (xb + 1 > cx1[nc - 1]) cx1[nc - 1] = xb + 1;
        }
    }
    clock_gettime(CLOCK_MONOTONIC, &td1);
    if (fr_valid) {           /* H rectangle: refreshed with the frame, whether its pixels changed or not */
        int x0 = fr_x0 < 0 ? 0 : fr_x0, y0 = fr_y0 < 0 ? 0 : fr_y0, x1 = fr_x1 > W ? W : fr_x1, y1 = fr_y1 > H ? H : fr_y1;
        if (x1 > x0 && y1 > y0) {
            if (nc < MAXCL) { cx0[nc] = x0; cy0[nc] = y0; cx1[nc] = x1; cy1[nc] = y1; nc++; }
            else {
                if (x0 < cx0[nc - 1]) cx0[nc - 1] = x0;
                if (y0 < cy0[nc - 1]) cy0[nc - 1] = y0;
                if (x1 > cx1[nc - 1]) cx1[nc - 1] = x1;
                if (y1 > cy1[nc - 1]) cy1[nc - 1] = y1;
            }
        }
    }
    long total = 0, flip_total = 0;
    for (int i = 0; i < nc; i++) total += (long)(cx1[i] - cx0[i]) * (cy1[i] - cy0[i]);
    if (g_tolerant && !fast_scroll) for (int i = 0; i < ns; i++) flip_total += (long)(sx1[i] - sx0[i]) * (sy1[i] - sy0[i]);
    else flip_total = total;
    /* the box around the visible change, for page_like() */
    int vbx0 = W, vby0 = H, vbx1 = 0, vby1 = 0, vrows = 0;
    for (int i = 0; i < (g_tolerant ? ns : nc); i++) {
        int a0 = g_tolerant ? sx0[i] : cx0[i], a1 = g_tolerant ? sx1[i] : cx1[i], b0 = g_tolerant ? sy0[i] : cy0[i], b1 = g_tolerant ? sy1[i] : cy1[i];
        if (a0 < vbx0) vbx0 = a0;
        if (a1 > vbx1) vbx1 = a1;
        if (b0 < vby0) vby0 = b0;
        if (b1 > vby1) vby1 = b1;
        vrows += b1 - b0;
    }
    if (g_tlog)
        fprintf(stderr, "[%lld] epd: frame page=%d rows=%d-%d changed=%d area=%ld%% visible=%ld%% diff=%ld us (%lld ms)\n", now_ms(), page, py0, py1, nc,
                total * 100 / ((long)W * H), flip_total * 100 / ((long)W * H),
                (td1.tv_sec - td0.tv_sec) * 1000000L + (td1.tv_nsec - td0.tv_nsec) / 1000, now_ms() - tc0);
    if (nc == 0) return 0;    /* nothing changed */
    g_scroll = mode == M_AUTO && scroll_active(now_ms());
    if (newwin_until && mode == M_AUTO && !g_scroll) {    /* NEWWIN: the new app's first page gets a full flash */
        if (now_ms() >= newwin_until) newwin_until = 0;
        else if (flip_total * 100 >= (long)W * H * page_pct) {
            newwin_until = 0; flip_count = 0; gc_count = 0; n_newwin++;
            if (g_tlog) fprintf(stderr, "[%lld] epd: new window: full flash\n", now_ms());
            push_region(0, 0, W, H, M_FULL);
            return 0;
        }
    }
    if (may_hold && g_lead && (anim_quiet_ms > 0 || turn_quiet_ms > 0) && mode == M_AUTO && !g_scroll) {    /* page-slide hold, see anim_quiet_ms */
        int bx0 = cx0[0], by0 = cy0[0], bx1 = cx1[0], by1 = cy1[0];
        for (int i = 1; i < nc; i++) {
            if (cx0[i] < bx0) bx0 = cx0[i];
            if (cy0[i] < by0) by0 = cy0[i];
            if (cx1[i] > bx1) bx1 = cx1[i];
            if (cy1[i] > by1) by1 = cy1[i];
        }
        if (anim_quiet_ms > 0 && edge_strip(bx0, by0, bx1, by1)) { slide_quiet_ms = anim_quiet_ms; return 4; }
        /* page-turn merge window, see turn_quiet_ms */
        if (turn_quiet_ms > 0 && flip_total * 100 >= (long)W * H * page_pct) { slide_quiet_ms = turn_quiet_ms; return 4; }
    }
    if ((pace || ((g_scroll || g_burst) && waiter_on)) && may_pace && mode != M_FULL) {
        long ov = 0;
        long long nowt = now_ms();
        for (int i = 0; i < nc; i++) ov += busy_overlap(cx0[i], cy0[i], cx1[i], cy1[i], nowt);
        if (ov * 2 >= total) return 2;
    }
    /* a small change: let the caller wait a moment for the frame that usually follows (prev_y8 is untouched, so the next
       call diffs against what is really on the panel) */
    if (may_hold && hold_ms > 0 && mode == M_AUTO && !g_scroll && total < (long)W * H * hold_pct / 100) return 1;
    /* 整页变化：交给 MTK 控制器做硬件滑动动画（和 Kindle 原生翻页同一套 P2SW/REAGL），
       而不是拆成几块硬刷。SWIPE 命令可以强制下一次整页变化动画。 */
    if (once_dir >= 0 && now_ms() > once_until) { once_dir = -1; anim_next = 0; }
    /* "full refresh every N pages": count only big changes (a page turn), not every small update, and a burst of them
       (an animated page turn, a scroll) only once. The animation path counts too, otherwise it would never flash. */
    g_burst = !g_scroll && burst_du_ms > 0 && mode == M_AUTO && flip_total * 100 >= (long)W * H * page_pct && now_ms() - last_big_ms < flip_gap_ms;
    if (g_burst) { burst_last_ms = now_ms(); if (flash_every <= 0) last_big_ms = burst_last_ms; }
    int post_scroll = g_scroll || g_burst || scroll_recent(now_ms());
    int flipped = 0;
    if (mode == M_FULL) { flip_count = 0; gc_count = 0; }
    else if (post_scroll) { if (flip_total * 100 >= (long)W * H * page_pct) last_big_ms = now_ms(); }   /* scroll / post-scroll settle never triggers black flash */
    else if (flash_every > 0 && flip_total > (long)W * H * page_pct / 100 &&
             (!flip_count_on || (flip_input_ms > 0 && now_ms() - last_input_ms >= flip_input_ms))) {
        last_big_ms = now_ms();       /* not a page, but still a large frame: SCROLLDU's burst detection needs it */
        if (g_tlog)
            fprintf(stderr, "[%lld] epd: flip not counted (%s)\n", now_ms(), flip_count_on ? "no input before it" : "FLIPCOUNT 0");
    }
    else if (flash_every > 0 && flip_total > (long)W * H * page_pct / 100 && !page_like(vbx0, vby0, vbx1, vby1, vrows)) {
        if (g_tlog)
            fprintf(stderr, "[%lld] epd: flip not counted (%dx%d+%d+%d, rows %d: not a page%s)\n", now_ms(), vbx1 - vbx0, vby1 - vby0, vbx0, vby0, vrows,
                    (hint_until && now_ms() < hint_until) || now_ms() < noflip_until ? ", keyboard / panel hint" : "");
    }
    else if (flash_every > 0 && flip_total > (long)W * H * page_pct / 100) {
        long long t = now_ms();
        int newflip = flip_gap_ms <= 0 || t - last_big_ms >= flip_gap_ms;
        last_big_ms = t;
        flipped = 1;
        if (newflip && ++flip_count >= flash_every) { flip_count = 0; gc_count = 0; g_burst = 0; return 3; }   /* the caller flashes the settled picture */
        if (g_tlog)
            fprintf(stderr, "[%lld] epd: flip %s %d/%d\n", t, newflip ? "new" : "same", flip_count, flash_every);
    }
    /* GCN: refreshes that are not page turns, counted per drawn (already merged) frame, also get a full flash now and then */
    if (gc_every > 0 && mode == M_AUTO && !post_scroll && !flipped && total * 100 >= (long)W * H * 3 && ++gc_count >= gc_every) {
        gc_count = 0; flip_count = 0; g_burst = 0; n_gcn++;
        if (g_tlog) fprintf(stderr, "[%lld] epd: GCN %d reached: flash when still\n", now_ms(), gc_every);
        return 3;
    }
    if (g_is_mtk && mode != M_FULL && !g_scroll && (anim_next || (anim_mode && flip_total > (long)W * H * anim_min_pct / 100))) {
        /* 整页变化交给硬件滑动动画；到 KFB_FLASH 页数时上面已经改成停下来以后整屏闪刷 */
        anim_next = 1;
        push_region(0, 0, W, H, M_AUTO);
        return 0;
    }
    {
        int rm = reagl_min;
        if (hint_until && now_ms() < hint_until) { if (hint_pct < rm) rm = hint_pct; }
        else hint_until = 0;
        frame_big = total * 100 >= (long)W * H * rm;
        if (rm != reagl_min && g_tlog)
            fprintf(stderr, "[%lld] epd: hint reagl-min %d%% (changed %ld%%)\n", now_ms(), rm, total * 100 / ((long)W * H));
    }
    /* A page turn is one refresh of the page, not a stack of text bands. With wide line spacing (多看: ~95 px between
       lines, more than CLGAP) a turn is found as up to MAXCL full-width strips ~70 px tall, and each strip was sent on its
       own: in 自动 (driver AUTO) the driver only turns a region into the non-flashing GLR16 page-turn waveform when it is
       > 1/3 wide and > 1/4 tall (docs/official-source-review.md), so every strip of gray text went out as GC16_PARTIAL and
       flashed - the whole page blinked strip by strip on every turn; in 灰度最准 every strip was GC16_PARTIAL anyway, and in
       均衡 (when the frame reached the REAGL share) six REAGL refreshes instead of one. So when the visible change of the
       frame is a page turn (page_pct of the screen), its strips go out as one rectangle, and in 灰度最准 / 自动 with REAGL
       (GLR16 FULL: 16 grays, cleans ghosts, does not flash - what the Kindle reader itself sends for a turn). 最快 keeps DU. */
    /* During scroll or continuous burst (e.g. webview fling), always merge multiple dirty bands into one atomic
       bounding box so the page moves as one solid sheet without staggered multi-strip wave tearing. */
    if (mode == M_AUTO && nc > 1 &&
        (g_scroll || g_burst || (ui_mode != 2 && (frame_big || ((ui_mode == 1 || ui_mode == 3) && flip_total * 100 >= (long)W * H * page_pct))))) {
        int n0 = nc;
        for (int i = 1; i < nc; i++) {
            if (cx0[i] < cx0[0]) cx0[0] = cx0[i];
            if (cy0[i] < cy0[0]) cy0[0] = cy0[i];
            if (cx1[i] > cx1[0]) cx1[0] = cx1[i];
            if (cy1[i] > cy1[0]) cy1[0] = cy1[i];
        }
        nc = 1;
        if ((ui_mode == 1 || ui_mode == 3) && !g_burst && !g_scroll) mode = M_REAGL;
        if (g_tlog)
            fprintf(stderr, "[%lld] epd: %s: %d strips -> one %dx%d refresh%s\n", now_ms(),
                    (g_scroll || g_burst) ? "scroll merge" : "page turn",
                    n0, cx1[0] - cx0[0], cy1[0] - cy0[0],
                    mode == M_REAGL ? " (REAGL)" : "");
    } else if (mode == M_AUTO && !g_burst && !g_scroll && (ui_mode == 1 || ui_mode == 3) && nc == 1 && flip_total * 100 >= (long)W * H * page_pct) {
        mode = M_REAGL;     /* one region already: still a page turn, same non-flashing waveform */
    }
    if (g_burst) {
        burst_dirty = 1;
        if (g_tlog) fprintf(stderr, "[%lld] epd: burst: DU, 16-gray pass when still\n", now_ms());
    }
    if (g_scroll) {
        scroll_frames++; n_scroll_frames++; scroll_last = now_ms(); scroll_dirty = 1;
        if (g_tlog) fprintf(stderr, "[%lld] epd: scroll frame %d%s\n", now_ms(), scroll_frames, scroll_on ? "" : " (fling)");
    }
    for (int i = 0; i < nc; i++) push_region(cx0[i], cy0[i], cx1[i], cy1[i], mode);
    frame_big = 0; g_burst = 0; g_scroll = 0;
    return 0;
}

/* ---- gesture badge (BADGE command, docs/gesture-hint.md) ------------------------------------------------------------
   A small "方块" box (white, black double frame, offset shadow, line icon, small English caps over a bold Chinese label,
   optional second line) that the host shows for a gesture. Drawn here, by the bridge, so it shows even when Android or
   the app in front hangs. 1-bit pixels, sent with DU (fast, no flash); the panel under it is not recorded in prev_y8,
   and when the time is up the rectangle is drawn again from the current Android picture (GL16, only changed pixels).
   Android frames that land while it is up are drawn as usual and the badge is put back on top right after them.
       BADGE <ms> <pos> <icon> <caps>|<label>[|<line 2>]     pos: t b l r tl tr bl br (logical picture), ms 100..10000
       BADGE 0                                              take it down now
   Glyphs and icons come from badge_font.h (tools/gen-badge-font.py); characters without a glyph are skipped. */
#include "badge_font.h"
#define BG_TRANSP 0x7f     /* logical badge pixel that shows the picture underneath (the notches next to the shadow) */
static uint8_t *bg_log = NULL;                 /* the badge in the logical picture: bg_lw x bg_lh, 0 / 255 / BG_TRANSP */
static int bg_lw, bg_lh, bg_lx, bg_ly;         /* size and logical position */
static int bg_on = 0, bg_req = 0;              /* shown (or due) / must be (re)drawn at the next loop turn */
static long long bg_until = 0;
static unsigned long bg_sent = 0;              /* n_sent right after our last draw: anything sent since may have covered it */
static int bg_px0, bg_py0, bg_px1, bg_py1, bg_drawn = 0;   /* panel rectangle last drawn */

static const struct bg_glyph *bg_find(const struct bg_font *f, uint32_t cp)
{
    int lo = 0, hi = f->n - 1;
    while (lo <= hi) {
        int m = (lo + hi) / 2;
        if (f->g[m].cp == cp) return &f->g[m];
        if (f->g[m].cp < cp) lo = m + 1; else hi = m - 1;
    }
    return NULL;
}
static uint32_t bg_utf8(const char **s)
{
    const unsigned char *p = (const unsigned char *)*s;
    uint32_t c = *p++;
    if (c >= 0xf0 && p[0] && p[1] && p[2]) { c = ((c & 7) << 18) | ((p[0] & 63u) << 12) | ((p[1] & 63u) << 6) | (p[2] & 63u); p += 3; }
    else if (c >= 0xe0 && p[0] && p[1]) { c = ((c & 15) << 12) | ((p[0] & 63u) << 6) | (p[1] & 63u); p += 2; }
    else if (c >= 0xc0 && p[0]) { c = ((c & 31) << 6) | (p[0] & 63u); p += 1; }
    *s = (const char *)p;
    return c;
}
static int bg_text_w(const struct bg_font *f, const char *s)
{
    int w = 0;
    while (*s) { const struct bg_glyph *g = bg_find(f, bg_utf8(&s)); if (g) w += g->adv; }
    return w;
}
static void bg_text(const struct bg_font *f, const char *s, int x, int y, int maxx)
{
    while (*s) {
        const struct bg_glyph *g = bg_find(f, bg_utf8(&s));
        if (!g) continue;
        if (x + g->w > maxx) break;
        int rb = (g->w + 7) / 8;
        for (int j = 0; j < f->h; j++)
            for (int i = 0; i < g->w; i++)
                if (f->bits[g->off + (size_t)j * rb + i / 8] & (0x80 >> (i & 7))) bg_log[(size_t)(y + j) * bg_lw + x + i] = 0;
        x += g->adv;
    }
}
static void bg_fill(int x0, int y0, int x1, int y1, uint8_t v)
{
    for (int y = y0; y < y1; y++) memset(bg_log + (size_t)y * bg_lw + x0, v, (size_t)(x1 - x0));
}
/* lay the badge out in the logical picture; text = "caps|label|line 2" */
static void bg_build(const char *pos, const char *icon, char *text)
{
    char *caps = text, *label = strchr(text, '|'), *sub = NULL;
    if (label) { *label++ = 0; sub = strchr(label, '|'); if (sub) *sub++ = 0; } else { label = caps; caps = ""; }
    const int FR = 3, GAP = 4, IN = 2, PAD = 20, SH = 8, ICG = 18;   /* outer frame, gap, inner frame, padding, shadow, icon gap */
    int vlw = g_rot % 180 ? H : VW, vlh = g_rot % 180 ? VW : H;      /* visible logical size */
    int tw = bg_text_w(&bg_label, label), cw = bg_text_w(&bg_cap, caps), sw = sub ? bg_text_w(&bg_sub, sub) : 0;
    int colw = tw > cw ? tw : cw;
    int toph = (caps[0] ? bg_cap.h + 2 : 0) + bg_label.h;
    if (toph < BG_ICON) toph = BG_ICON;
    int innerw = BG_ICON + ICG + colw;
    if (sw > innerw) innerw = sw;
    int innerh = toph + (sub ? 10 + bg_sub.h : 0);
    int edge = FR + GAP + IN + PAD;
    int bw = innerw + 2 * edge, bh = innerh + 2 * edge;
    if (bw + SH > vlw - 32) bw = vlw - 32 - SH;
    bg_lw = bw + SH; bg_lh = bh + SH;
    free(bg_log);
    bg_log = malloc((size_t)bg_lw * bg_lh);
    if (!bg_log) { bg_on = 0; return; }
    memset(bg_log, BG_TRANSP, (size_t)bg_lw * bg_lh);
    bg_fill(SH, SH, bg_lw, bg_lh, 0);                     /* shadow */
    bg_fill(0, 0, bw, bh, 0);                             /* outer frame */
    bg_fill(FR, FR, bw - FR, bh - FR, 255);
    bg_fill(FR + GAP, FR + GAP, bw - FR - GAP, bh - FR - GAP, 0);   /* inner frame */
    bg_fill(FR + GAP + IN, FR + GAP + IN, bw - FR - GAP - IN, bh - FR - GAP - IN, 255);
    const uint8_t *ib = NULL;
    for (size_t k = 0; k < sizeof(bg_icons) / sizeof(bg_icons[0]); k++) if (!strcmp(bg_icons[k].name, icon)) ib = bg_icons[k].bits;
    int ix = edge, iy = edge + (toph - BG_ICON) / 2, rb = (BG_ICON + 7) / 8;
    if (ib)
        for (int j = 0; j < BG_ICON; j++)
            for (int i = 0; i < BG_ICON; i++)
                if (ib[j * rb + i / 8] & (0x80 >> (i & 7))) bg_log[(size_t)(iy + j) * bg_lw + ix + i] = 0;
    int tx = ix + (ib ? BG_ICON + ICG : 0), ty = edge + (toph - ((caps[0] ? bg_cap.h + 2 : 0) + bg_label.h)) / 2;
    if (caps[0]) { bg_text(&bg_cap, caps, tx, ty, bw - edge); ty += bg_cap.h + 2; }
    bg_text(&bg_label, label, tx, ty, bw - edge);
    if (sub) bg_text(&bg_sub, sub, edge, edge + toph + 10, bw - edge);
    /* near the edge / corner the gesture started from, clear of the status bar and of a finger held in a corner */
    const int M = 28, TOPY = 96, CORNER = 200;
    int cx = (vlw - bg_lw) / 2, cy = (vlh - bg_lh) / 2;
    if (!strcmp(pos, "b")) { bg_lx = cx; bg_ly = vlh - bg_lh - TOPY; }
    else if (!strcmp(pos, "l")) { bg_lx = M; bg_ly = cy; }
    else if (!strcmp(pos, "r")) { bg_lx = vlw - bg_lw - M; bg_ly = cy; }
    else if (!strcmp(pos, "tl")) { bg_lx = M; bg_ly = CORNER; }
    else if (!strcmp(pos, "tr")) { bg_lx = vlw - bg_lw - M; bg_ly = CORNER; }
    else if (!strcmp(pos, "bl")) { bg_lx = M; bg_ly = vlh - bg_lh - CORNER; }
    else if (!strcmp(pos, "br")) { bg_lx = vlw - bg_lw - M; bg_ly = vlh - bg_lh - CORNER; }
    else { bg_lx = cx; bg_ly = TOPY; }
    if (bg_lx < 0) bg_lx = 0;
    if (bg_ly < 0) bg_ly = 0;
}
/* put the panel rectangle back to the Android picture (16 gray levels, only the pixels that differ change) */
static void bg_restore(void)
{
    if (!bg_drawn) return;
    bg_drawn = 0;
    g_cleaning = 1;
    push_region(bg_px0, bg_py0, bg_px1, bg_py1, M_CLEAN);
    g_cleaning = 0;
}
/* map the logical badge onto the panel (rotation, inversion, the picture under the transparent pixels) and send it */
static void bg_draw(void)
{
    int px, py, pw, ph;
    rot_rect(bg_lx, bg_ly, bg_lw, bg_lh, &px, &py, &pw, &ph);
    int x0 = px & ~7, x1 = (px + pw + 7) & ~7, y0 = py, y1 = py + ph;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 > W) x1 = W;
    if (y1 > H) y1 = H;
    if (x1 <= x0 || y1 - y0 < 8) return;
    if (bg_drawn && (bg_px0 < x0 || bg_py0 < y0 || bg_px1 > x1 || bg_py1 > y1)) bg_restore();   /* the old one sticks out */
    int w = x1 - x0, h = y1 - y0;
    uint8_t *buf = malloc((size_t)w * h);
    if (!buf) return;
    for (int j = 0; j < h; j++) memcpy(buf + (size_t)j * w, cur_y8 + (size_t)(y0 + j) * W + x0, (size_t)w);
    for (int j = 0; j < bg_lh; j++)
        for (int i = 0; i < bg_lw; i++) {
            uint8_t v = bg_log[(size_t)j * bg_lw + i];
            if (v == BG_TRANSP) continue;
            int lx = bg_lx + i, ly = bg_ly + j, qx, qy;
            switch (g_rot) {
            case 90:  qx = VW - 1 - ly; qy = lx; break;
            case 180: qx = VW - 1 - lx; qy = H - 1 - ly; break;
            case 270: qx = ly; qy = H - 1 - lx; break;
            default:  qx = lx; qy = ly; break;
            }
            if (qx < x0 || qx >= x1 || qy < y0 || qy >= y1) continue;
            buf[(size_t)(qy - y0) * w + (qx - x0)] = g_invert ? (uint8_t)(255 - v) : v;
        }
    FBInkConfig c = cfg;
    c.dithering_mode = HWD_PASSTHROUGH;     /* already 1-bit */
    c.ignore_alpha = true;
    c.no_refresh = false;
    set_wfm(&c, M_DU, WFM_DU);
    long long t0 = now_ms();
    if (g_cfa || g_rawmtk) c.no_refresh = true;
    int rc = fbink_print_raw_data(fbfd, buf, w, h, (size_t)w * h, (short)x0, (short)y0, &c);
    if ((g_cfa || g_rawmtk) && rc >= 0) rc = refresh_cfa(x0, y0, w, h, &c);
    free(buf);
    fprintf(stderr, "[%lld] epd: badge %dx%d+%d+%d -> %d (%lld ms)\n", t0, w, h, x0, y0, rc, now_ms() - t0);
    if (rc < 0) return;
    n_sent++;
    busy_add(x0, y0, w, h, t0, 0);
    bg_sent = n_sent;
    bg_px0 = x0; bg_py0 = y0; bg_px1 = x1; bg_py1 = y1; bg_drawn = 1;
}
/* BADGE line (after "BADGE") */
static void bg_command(const char *args)
{
    int ms = 0, n = 0;
    char pos[8] = "", icon[24] = "";
    if (sscanf(args, "%d%n", &ms, &n) < 1) return;
    if (ms <= 0) { if (bg_on) { bg_on = 0; bg_restore(); } return; }
    int n2 = 0;
    if (sscanf(args + n, " %7s %23s %n", pos, icon, &n2) < 2 || !n2) return;
    char text[512];
    snprintf(text, sizeof(text), "%s", args + n + n2);
    if (ms < 100) ms = 100;
    if (ms > 10000) ms = 10000;
    bg_build(pos, icon, text);
    if (!bg_log) return;
    bg_on = 1; bg_req = 1; bg_until = now_ms() + ms;
}
/* once per loop turn, before waiting: draw a new badge, put it back on top of refreshes sent since, take it down when
   its time is up. Not while the screensaver / boot splash owns the panel. */
static void bg_tick(const char *sleepf)
{
    if (!bg_on) return;
    if (access(sleepf, F_OK) == 0 || !prev_valid) { bg_on = 0; bg_drawn = 0; return; }   /* the next frame redraws everything */
    if (now_ms() >= bg_until) { bg_on = 0; bg_restore(); return; }
    if (bg_req || n_sent != bg_sent) { bg_req = 0; bg_draw(); }
}
/* ---- end of gesture badge ---- */

/* ---- app launch: keep the last picture over the starting window (LAUNCH) ---- */
static long long launch_until = 0, launch_t0 = 0, launch_sent = 0;
static int launch_rows = 0;     /* page_blank: share of sampled rows with content, per mille (for the timing log) */
static int launch_badged = 0;
static unsigned long n_launch_held = 0;
/* one flat colour, apart from a narrow band (a status bar / the navigation bar drawn into the same buffer): sample every
   4th row and column; a row counts as content when some sample differs from the most common value by more than 24.
   A real screen - even a sparse login page - has content on far more than 6 % of the rows. */
static int page_blank(const uint8_t *shm, int page)
{
    if (g_rot) return 0;
    const uint8_t *img = page_base(shm, page);
    int hist[16] = {0};
    for (int y = 0; y < H; y += 64)
        for (int x = 0; x < W; x += 64) { const uint8_t *p = img + ((size_t)y * W + x) * 4; hist[((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8) >> 4]++; }
    int bi = 0;
    for (int i = 1; i < 16; i++) if (hist[i] > hist[bi]) bi = i;
    int dom = bi * 16 + 8, rows = 0, crow = 0;
    for (int y = 0; y < H; y += 4) {
        rows++;
        const uint8_t *r = img + (size_t)y * W * 4;
        for (int x = 0; x < W; x += 4) {
            const uint8_t *p = r + (size_t)x * 4;
            int v = (p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8;
            if (v - dom > 24 || dom - v > 24) { crow++; break; }
        }
    }
    launch_rows = rows ? crow * 1000 / rows : 0;
    return crow * 100 < rows * 6;
}
/* the hold is over: take the badge down by making its rectangle differ from the next picture (that frame then covers it
   in the same refresh, no extra update) */
static void launch_end(const char *why)
{
    if (!launch_until) return;
    long long held = launch_t0 ? now_ms() - launch_t0 : 0;
    launch_until = 0;
    if (bg_drawn && prev_y8)
        for (int y = bg_py0; y < bg_py1; y++)
            for (int x = bg_px0; x < bg_px1; x++) prev_y8[(size_t)y * W + x] = (uint8_t)~cur_y8[(size_t)y * W + x];
    if (launch_badged) { bg_on = 0; bg_drawn = 0; }
    if (launch_t0) fprintf(stderr, "[%lld] epd: launch hold ended (%s) after %lld ms, badge %d\n", now_ms(), why, held, launch_badged);
    launch_t0 = 0; launch_badged = 0;
}
static void launch_badge(void)
{
    char a[] = "5000 t app LOADING|加载中";
    launch_badged = 1;
    bg_command(a);
}

/* the quality pass (see qpass_ms): one full-quality refresh of the remembered picture area, from what is on the panel
   (prev_y8: a newer frame that is still held back must not be drawn by it) */
static void qp_run(long long now)
{
    int x0 = qp_x0 & ~7, y0 = qp_y0, x1 = (qp_x1 + 7) & ~7, y1 = qp_y1;
    if (x1 > W) x1 = W;
    if (y1 > H) y1 = H;
    int w = x1 - x0, h = y1 - y0, n = qp_n, lq = qp_lowq;
    qp_valid = 0; qp_n = 0; qp_lowq = 0;
    if (w <= 0 || h <= 0 || !prev_valid) return;
    uint8_t *tmp = malloc((size_t)w * h);
    if (!tmp) return;
    for (int j = 0; j < h; j++) memcpy(tmp + (size_t)j * w, prev_y8 + (size_t)(y0 + j) * W + x0, (size_t)w);
    fprintf(stderr, "[%lld] epd: quality pass %dx%d+%d+%d (%d refreshes%s, still %lld ms)\n", now, w, h, x0, y0, n,
            lq ? ", low quality" : "", now - qp_last);
    g_cleaning = 1;
    if (push_y8(tmp, x0, y0, w, h, M_QPASS) >= 0) n_qpass++;
    qp_done = now;
    g_cleaning = 0;
    free(tmp);
}

/* VERIFY: convert the whole newest page and compare it with what we think is on the panel (test aid: a frame whose change
   was missed shows up as differing rows) */
static void verify_page(const uint8_t *shm)
{
    if (g_rot || !prev_valid) { fprintf(stderr, "epd: verify: n/a\n"); return; }
    uint8_t *t = malloc((size_t)W * H);
    if (!t) return;
    rgba_to_y8_opaque(page_base(shm, last_page), W * 4, 0, 0, W, H, t);
    lightf_pass(t, W, W, H, 0, H);
    long px = 0; int r0 = -1, r1 = -1, x0 = W, x1 = 0;
    for (int y = 0; y < H; y++) {
        const uint8_t *a = t + (size_t)y * W, *b = prev_y8 + (size_t)y * W;
        if (!memcmp(a, b, W)) continue;
        if (r0 < 0) r0 = y;
        r1 = y + 1;
        for (int x = 0; x < W; x++) if (a[x] != b[x]) { px++; if (x < x0) x0 = x; if (x + 1 > x1) x1 = x + 1; }
    }
    if (bg_on || bg_drawn) fprintf(stderr, "epd: verify: (gesture badge up)\n");
    if (r0 < 0) fprintf(stderr, "[%lld] epd: verify page=%d: panel picture matches\n", now_ms(), last_page);
    else fprintf(stderr, "[%lld] epd: verify page=%d: %ld px differ in %dx%d+%d+%d\n", now_ms(), last_page, px, x1 - x0, r1 - r0, x0, r0);
    free(t);
}

static int cmd_run(const char *shm, const char *fifo, const char *ctl)
{
    int sfd = open(shm, O_RDWR | O_CREAT, 0666);
    if (sfd < 0) { perror("epd: open shm"); return 1; }
    fchmod(sfd, 0666);
    if (ftruncate(sfd, (off_t)W * H * 4 * 2) < 0) perror("epd: ftruncate shm");
    cur_y8 = malloc((size_t)W * H);
    prev_y8 = malloc((size_t)W * H);
    if (!cur_y8 || !prev_y8) return 1;
    if (g_rot) memset(cur_y8, 0xff, (size_t)W * H);   /* rotated: columns >= VW are never written */
    uint8_t *fbmem = mmap(NULL, (size_t)W * H * 4 * 2, PROT_READ, MAP_SHARED, sfd, 0);
    if (fbmem == MAP_FAILED) { perror("epd: mmap shm"); return 1; }
    mkfifo(fifo, 0666);
    chmod(fifo, 0666);
    int ffd = open(fifo, O_RDWR | O_NONBLOCK);   /* O_RDWR: never see EOF when writers come and go */
    if (ffd < 0) { perror("epd: open fifo"); return 1; }
    char sleepf[512];
    snprintf(sleepf, sizeof(sleepf), "%s/sleep", ctl);
    {   /* started while Android is already running (the bridge was restarted): hwcomposer.kindle notes the page that holds
           the current picture in <dir of shm>/page, so a REDRAW before the next frame shows the right one */
        char pf[600];
        snprintf(pf, sizeof(pf), "%s", shm);
        char *sl = strrchr(pf, '/');
        if (sl && (size_t)(sl - pf) < sizeof(pf) - 8) {
            strcpy(sl, "/page");
            FILE *f = fopen(pf, "r");
            int v;
            if (f) { if (fscanf(f, "%d", &v) == 1) last_page = v == 2 ? 2 : v & 1; fclose(f); }
            strcpy(sl, "/fb2");
            snprintf(fb2path, sizeof(fb2path), "%s", pf);
            /* tell hwcomposer.kindle that this bridge shows page 2: without <dir of shm>/fb2.ok it leaves frames with a dim
               layer to GLES (a bridge that read "P 2" as page 0 showed none of the app's later frames, see hwc_kindle.c) */
            strcpy(sl, "/fb2.ok");
            int okfd = open(pf, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
            if (okfd >= 0) close(okfd);
        }
    }

    static char tflag[560];
    snprintf(tflag, sizeof(tflag), "%s/timing", ctl);
    timing_flag = tflag;

    char acc[4096];
    size_t alen = 0;
    /* a frame that is not drawn yet: a small change waiting for a possible follow-up frame (held_why 1, until held_until),
       or a frame kept back because the panel is still refreshing that area (held_why 2, until the refresh is reported done;
       held_until is only a safety net) */
    int held_page = -1, held_mode = M_AUTO, held_why = 0;
    long long held_until = 0, last_frame_ms = 0, held_t0 = 0;
    if (pipe2(busy_pipe, O_NONBLOCK | O_CLOEXEC) == 0) {
        pthread_t th;
        if (pthread_create(&th, NULL, waiter_main, NULL) == 0) waiter_on = 1;
    }
    int pfd = waiter_on ? busy_pipe[0] : -1;
    while (!quit) {
        bg_tick(sleepf);    /* gesture badge (BADGE) */
        fd_set rf;
        FD_ZERO(&rf);
        FD_SET(ffd, &rf);
        if (pfd >= 0) FD_SET(pfd, &rf);
        int maxfd = ffd > pfd ? ffd : pfd;
        /* no timeout while idle: sleep until the Android side posts something, a refresh finishes or a signal arrives.
           A deadline only while a frame is held back or a clean-up is due. */
        long long deadline = -1, nowt = now_ms();
        if (held_page >= 0) deadline = held_until;
        else if ((clean_ms > 0 || burst_dirty) && cl_valid) {
            long long d = (cl_last > last_frame_ms ? cl_last : last_frame_ms) + (clean_ms > 0 ? clean_ms : burst_du_ms);
            deadline = d > nowt ? d : nowt + (busy_any(nowt) ? 1000 : 0);      /* due but the panel is busy: the pipe wakes us */
        }
        if (fast_until > nowt && (deadline < 0 || fast_until < deadline)) deadline = fast_until;   /* FAST ends: cleanup timing */
        if (held_page < 0 && scroll_dirty && cl_valid && !scroll_active(nowt)) {   /* touch scroll cleanup */
            long long d = (scroll_last > scroll_t_off ? scroll_last : scroll_t_off);
            if (last_frame_ms > d) d = last_frame_ms;
            d += scroll_delay_ms;
            if (d < nowt) d = busy_any(nowt) ? nowt + 1000 : nowt;
            if (deadline < 0 || d < deadline) deadline = d;
        } else if (held_page < 0 && scroll_frames && scroll_delay_ms > 0 && !scroll_on) {   /* fling window: when it ends */
            long long d = (scroll_last > scroll_t_off ? scroll_last : scroll_t_off) + SCROLL_FLING_GAP;
            if (d > nowt && (deadline < 0 || d < deadline)) deadline = d;
        }
        if (held_page < 0 && qp_due() && !scroll_dirty && !((clean_ms > 0 || burst_dirty) && cl_valid)) {      /* quality pass (a pending scroll/clean pass covers it) */
            long long d = (qp_last > last_frame_ms ? qp_last : last_frame_ms) + qpass_ms;
            if (d < qp_at()) d = qp_at();
            if (d < nowt) d = busy_any(nowt) ? nowt + 1000 : nowt;
            if (deadline < 0 || d < deadline) deadline = d;
        }
        if (bg_on && (deadline < 0 || bg_until < deadline)) deadline = bg_until;   /* gesture badge: take it down on time */
        int sr;
        if (deadline >= 0) {
            long long left = deadline - nowt;
            struct timeval tvh = {(long)((left > 0 ? left : 0) / 1000), (long)(((left > 0 ? left : 0) % 1000) * 1000)};
            sr = select(maxfd + 1, &rf, NULL, NULL, &tvh);
        } else sr = select(maxfd + 1, &rf, NULL, NULL, NULL);
        if (sr < 0) { if (errno != EINTR) usleep(1000); continue; }
        g_tlog = timing_flag && access(timing_flag, F_OK) == 0;
        int panel_event = 0;
        if (sr > 0 && pfd >= 0 && FD_ISSET(pfd, &rf)) { char junk[64]; while (read(pfd, junk, sizeof(junk)) > 0) {} panel_event = 1; }
        if (panel_event && swipe_recover && access(sleepf, F_OK) != 0) {   /* a swipe hung the panel: one full refresh of the current picture */
            swipe_recover = 0; held_page = -1; prev_valid = 0; cl_valid = 0; req_y0 = -1; once_dir = -1; anim_next = 0;
            show_page(fbmem, last_page, M_FULL, 1, 0, 0);
            continue;
        }
        if (sr == 0 || (panel_event && !FD_ISSET(ffd, &rf))) {
            nowt = now_ms();
            if (access(sleepf, F_OK) == 0) { held_page = -1; cl_valid = 0; burst_dirty = 0; need_full = 1; pend_y0 = -1; qp_valid = 0; scroll_dirty = 0; scroll_frames = 0; scroll_on = 0; continue; }
            if (held_page >= 0) {
                int expired = nowt >= held_until;
                if (held_why == 4) {   /* launch hold: the badge after 600 ms, then wait for content / the end of LAUNCH */
                    if (launch_until && nowt < launch_until) {
                        if (!launch_badged) launch_badge();
                        held_until = launch_until;
                        continue;
                    }
                    launch_end("timeout");
                }
                if ((held_why == 1 || held_why == 3 || held_why == 5 || held_why == 6) && !expired) continue;   /* a hold only ends by its deadline or a new frame */
                int hp = held_page, hm = held_mode;
                held_page = -1;
                last_draw_ms = nowt;
                if (held_why == 3) {    /* KFB_FLASH count reached and the picture is still now: one full flash of it */
                    if (g_tlog)
                        fprintf(stderr, "[%lld] epd: page-count flash after %lld ms\n", nowt, nowt - flash_t0);
                    req_y0 = -1;
                    show_page(fbmem, hp, M_FULL, 1, 0, 0);
                    continue;
                }
                if (held_why == 6) {    /* SCROLLSTYLE 2: the drag is over and the frames stopped: the newest picture, once */
                    if (g_tlog)
                        fprintf(stderr, "[%lld] epd: scroll style 2: drawing after %lld ms (%lu frames held)\n", nowt, nowt - scroll_t_off, n_scroll_held);
                    req_y0 = -1; pend_y0 = -1; need_full = 1;
                    noflip_until = nowt + 1;     /* the end of a scroll is not a page turn for KFB_FLASH */
                    scroll_frames = 0; scroll_dirty = 0;
                    show_page(fbmem, hp, hm == M_FULL ? M_FULL : M_REAGL, hm == M_FULL, 0, 0);   /* a FULL asked for meanwhile stays FULL */
                    continue;
                }
                if (held_why == 5) {    /* page-slide hold: the frames stopped, draw the newest picture once */
                    if (g_tlog)
                        fprintf(stderr, "[%lld] epd: slide hold: drawing after %lld ms\n", nowt, nowt - anim_t0);
                    req_y0 = -1; pend_y0 = -1; g_lead = 0;
                    if (show_page(fbmem, hp, hm, 0, 0, 0) == 3) {
                        held_page = hp; held_mode = M_FULL; held_why = 3; flash_t0 = nowt; held_until = nowt + flash_quiet_ms;
                    }
                    continue;
                }
                /* held small change: nothing followed, draw it. Paced frame: the panel reported a refresh done, try again
                   (it stays back if another refresh still covers it); after the safety deadline draw it anyway. */
                if (pend_y0 >= 0) { req_y0 = pend_y0; req_y1 = pend_y1; } else req_y0 = -1;
                if (show_page(fbmem, hp, hm, 0, 0, held_why == 2 && !expired) == 2) {
                    held_page = hp; held_mode = hm; held_why = 2;   /* keep the first deadline */
                }
            } else if (scroll_dirty && !scroll_active(nowt) && !cl_valid) {
                scroll_dirty = 0; scroll_frames = 0;       /* covered by a gray refresh since: nothing to clean */
            } else if (scroll_dirty && cl_valid && !scroll_active(nowt) && !busy_any(nowt) &&
                       nowt - (scroll_last > scroll_t_off ? scroll_last : scroll_t_off) >= scroll_delay_ms && nowt - last_frame_ms >= scroll_delay_ms) {
                /* touch scroll over and the picture still: one FULL 16-gray refresh of what was drawn 1-bit */
                int x0 = cl_x0, y0 = cl_y0, x1 = cl_x1, y1 = cl_y1;
                int big = (long)(x1 - x0) * (y1 - y0) > (long)W * H * 6 / 10;
                cl_valid = 0; burst_dirty = 0; scroll_dirty = 0; scroll_frames = 0; n_scroll_clean++;
                if (scroll_raw_y8) {
                    scroll_raw_y8 = 0;
                    if (!g_rot) {
                        rgba_to_y8_opaque(page_base(fbmem, last_page), W * 4, 0, 0, W, H, cur_y8);
                        lightf_pass(cur_y8, W, W, H, 0, H);
                    }
                }
                if (g_tlog) fprintf(stderr, "[%lld] epd: scroll cleanup %dx%d+%d+%d\n", nowt, x1 - x0, y1 - y0, x0, y0);
                g_cleaning = 1;
                if (big) push_region(0, 0, W, H, M_QPASS); else push_region(x0, y0, x1, y1, M_QPASS);
                g_cleaning = 0;
                if (qp_valid && (big || (qp_x0 >= x0 && qp_y0 >= y0 && qp_x1 <= x1 && qp_y1 <= y1))) { qp_valid = 0; qp_n = 0; qp_lowq = 0; qp_done = nowt; }
            } else if (scroll_frames && !scroll_on && !scroll_dirty && !scroll_active(nowt)) {
                scroll_frames = 0;
            } else if (qp_due() && !scroll_dirty && nowt >= qp_at() && nowt - last_frame_ms >= qpass_ms && !busy_any(nowt) &&
                       !((clean_ms > 0 || burst_dirty) && cl_valid)) {
                qp_run(nowt);
            } else if ((clean_ms > 0 || burst_dirty) && cl_valid && nowt - cl_last >= (clean_ms > 0 ? clean_ms : burst_du_ms) &&
                       nowt - last_frame_ms >= (clean_ms > 0 ? clean_ms : burst_du_ms) && !busy_any(nowt)) {
                /* the picture has been still for a while: one 16-gray pass over what was drawn 1-bit */
                int x0 = cl_x0, y0 = cl_y0, x1 = cl_x1, y1 = cl_y1;
                cl_valid = 0; burst_dirty = 0;
                if (g_tlog) fprintf(stderr, "[%lld] epd: 16-gray pass %dx%d+%d+%d\n", nowt, x1 - x0, y1 - y0, x0, y0);
                /* a picture inside it that is due for the quality pass: the same refresh, sent FULL (one refresh, not two) */
                int big = (long)(x1 - x0) * (y1 - y0) > (long)W * H * 6 / 10;
                int qin = qp_due() && qp_x0 >= x0 && qp_y0 >= y0 && qp_x1 <= x1 && qp_y1 <= y1;
                g_cleaning = 1;
                if (big) push_region(0, 0, W, H, M_REAGL);
                else push_region(x0, y0, x1, y1, qin ? M_QPASS : M_CLEAN);
                g_cleaning = 0;
                if (big || qin) { qp_valid = 0; qp_n = 0; qp_lowq = 0; qp_done = nowt; }
            }
            continue;
        }
        if (alen >= sizeof(acc) - 1) {
            fprintf(stderr, "epd: fifo line too long (%zu bytes), discarding\n", alen);
            alen = 0;
        }
        ssize_t n = read(ffd, acc + alen, sizeof(acc) - alen - 1);
        if (n <= 0) continue;
        alen += (size_t)n;
        acc[alen] = 0;

        /* coalesce every complete line into one bounding box, strongest mode wins */
        int have = 0, bx0 = 0, by0 = 0, bx1 = 0, by1 = 0, mode = M_AUTO, redraw = 0, full = 0, page = -1;
        int ry0 = LH, ry1 = 0, rall = 0;      /* rows named by "P n y0 y1" lines in this batch; rall: a line without rows */
        long long tstart = 0;
        int merged = 0;      /* the merge loop already waited once in this batch */
        for (;;) {
        char *line = acc, *nl;
        int tlog = g_tlog;
        while ((nl = strchr(line, '\n'))) {
            *nl = 0;
            int x, y, w, h;
            char m = 'A';
            if (!strcmp(line, "QUIT")) quit = 1;
            else if (!strcmp(line, "REDRAW")) redraw = 1;
            else if (!strcmp(line, "FULL")) full = 1;
            else if (!strcmp(line, "VERIFY")) verify_page(fbmem);
            else if (!strncmp(line, "QPASS", 5)) { int v; if (sscanf(line + 5, "%d", &v) == 1 && v >= 0 && v <= 5000) { qpass_ms = v; if (!v) qp_valid = 0; } }
            else if (!strncmp(line, "ROT ", 4)) {
                int r = atoi(line + 4);
                if ((r == 0 || r == 90 || r == 180 || r == 270) && r % 180 == g_rot % 180 && r != g_rot) {
                    fprintf(stderr, "epd: rotate %d -> %d\n", g_rot, r);
                    g_rot = r;
                    full = 1;
                }
            }
            else if (!strncmp(line, "SET ", 4)) {
                /* SET <gamma> <dither> <flash_every> <invert> [mode] [settle] [lightf] [darken]: display settings from the
                   desktop, applied at once. lightf / darken missing = off (host scripts from before the filter). */
                double gm; int dt, fe, iv, um = 0, st = settle_ms, lf = 0, dk = 0;
                if (sscanf(line + 4, "%lf %d %d %d %d %d %d %d", &gm, &dt, &fe, &iv, &um, &st, &lf, &dk) >= 4) {
                    /* Only what changes the picture itself (contrast curve, dither, invert, light filter, darken) needs the
                       whole screen redrawn. Switching apps sends SET with the app's own refresh mode / full-flash interval
                       (host appprof.sh): redrawing then put an extra full-screen update on top of the app's first frame. */
                    int lut_changed = gm != g_gamma || (uint8_t)dt != dither || (iv != 0) != g_invert ||
                                      ((lf >= 0 && lf <= 3) ? lf : 0) != g_lightf || (dk == 1) != g_darken;
                    settle_ms = st < 0 ? 0 : st > 500 ? 500 : st;
                    g_gamma = gm; dither = (uint8_t)dt; flash_every = fe < 0 ? 0 : fe; g_invert = iv != 0;
                    ui_mode = (um >= 0 && um <= 3) ? um : 0;
                    g_lightf = (lf >= 0 && lf <= 3) ? lf : 0; g_darken = dk == 1;
                    if (lut_changed) build_lut(g_gamma);
                    fprintf(stderr, "epd: set contrast=%.2f dither=%d flash=%d invert=%d mode=%d settle=%d lightf=%d darken=%d%s\n",
                            g_gamma, dither, flash_every, g_invert, ui_mode, settle_ms, g_lightf, g_darken, lut_changed ? "" : " (no redraw)");
                    if (lut_changed) redraw = 1;
                }
            }
            else if (!strcmp(line, "TOUCH")) last_input_ms = now_ms();
            else if (line[0] == 'H' && line[1] == ' ') {
                char c = 'A'; int hx, hy, hw, hh;
                long long t = now_ms();
                if (hint_mode >= 0 && t - hint_t > 500) { hint_mode = -1; hr_valid = 0; }   /* an old hint nobody used */
                if (sscanf(line + 2, " %c", &c) == 1) {
                    static const char order[] = "ADRGF";          /* priority, lowest first */
                    const char *pc = strchr(order, c), *po = NULL;
                    if (pc) {
                        int cur = hint_mode == M_FULL ? 'F' : hint_mode == M_GC16 ? 'G' : hint_mode == M_REAGL ? 'R' : hint_mode == M_DU ? 'D' : 'A';
                        po = strchr(order, cur);
                        if (hint_mode < 0 || pc > po)
                            hint_mode = c == 'F' ? M_FULL : c == 'G' ? M_GC16 : c == 'R' ? M_REAGL : c == 'D' ? M_DU : M_AUTO;
                        hint_t = t;
                    }
                    if (sscanf(line + 2, " %*c %d %d %d %d", &hx, &hy, &hw, &hh) == 4 && hw > 0 && hh > 0) {
                        if (!hr_valid) { hr_x0 = hx; hr_y0 = hy; hr_x1 = hx + hw; hr_y1 = hy + hh; hr_valid = 1; }
                        else {
                            if (hx < hr_x0) hr_x0 = hx;
                            if (hy < hr_y0) hr_y0 = hy;
                            if (hx + hw > hr_x1) hr_x1 = hx + hw;
                            if (hy + hh > hr_y1) hr_y1 = hy + hh;
                        }
                        if (hint_mode < 0) hint_mode = M_AUTO;
                        hint_t = t;
                    }
                }
            }
            else if (!strncmp(line, "FAST", 4)) {
                long v; if (sscanf(line + 4, "%ld", &v) == 1 && v >= 0) {
                    long long t = now_ms();
                    if (v > 600000) v = 600000;
                    if (v) { if (!fast_until && !scroll_on) scroll_frames = 0; fast_until = t + v; }
                    else if (fast_until) fast_until = t;          /* ends at once: scroll_active() turns it into a lift */
                    if (tlog) fprintf(stderr, "[%lld] epd: fast mode %ld ms\n", t, v);
                }
            }
            else if (!strncmp(line, "FREEZE", 6)) {
                long v; if (sscanf(line + 6, "%ld", &v) == 1 && v >= 0) {
                    long long t = now_ms();
                    if (v > 10000) v = 10000;
                    freeze_until = v ? t + v : 0;
                    if (held_why == 6 && held_page >= 0) held_until = v ? freeze_until : t;
                    if (tlog) fprintf(stderr, "[%lld] epd: freeze %ld ms\n", t, v);
                }
            }
            else if (!strncmp(line, "FLIPINPUT", 9)) { int v; if (sscanf(line + 9, "%d", &v) == 1 && v >= 0 && v <= 10000) flip_input_ms = v; }
            else if (!strncmp(line, "FLIPCOUNT", 9)) { int v; if (sscanf(line + 9, "%d", &v) == 1) { flip_count_on = v != 0; if (!flip_count_on) flip_count = 0; } }
            else if (!strncmp(line, "DIR", 3)) {
                last_input_ms = now_ms();     /* a swipe / tap (veradroid-input) or a page key (vdbtkeys) */
                /* DIR <dir>: only the direction; the step count comes from ANIM/--anim-steps.
                   Older vinput binaries sent "DIR x 12" and silently overrode the 6-step setting. */
                int d = anim_dir;
                if (sscanf(line + 3, "%d", &d) >= 1 && d >= 0 && d <= 3) anim_dir = d;
            }
            else if (!strncmp(line, "ANIMHOLD", 8)) {   /* ANIMHOLD <ms>: page-slide hold quiet window (0 = off); checked before ANIM */
                int v;
                if (sscanf(line + 8, "%d", &v) == 1 && v >= 0 && v <= 500) { anim_quiet_ms = v; if (!v && held_why == 5 && held_page >= 0) held_until = now_ms(); }
            }
            else if (!strncmp(line, "SWIPEOK", 7)) {    /* SWIPEOK <0|1>: hardware swipe allowed (KFB_SWIPE); an automatic switch-off stays */
                int v;
                if (sscanf(line + 7, "%d", &v) == 1) { swipe_ok = v != 0; if (!swipe_ok) { once_dir = -1; anim_next = 0; } }
            }
            else if (!strncmp(line, "ANIM", 4)) {
                /* ANIM <0|1> [dir] [steps] [min_pct]: enable/disable the MTK hardware page-turn animation */
                int a = anim_mode, d = anim_dir, s = anim_steps, mp = anim_min_pct;
                if (sscanf(line + 4, "%d %d %d %d", &a, &d, &s, &mp) >= 1) {
                    anim_mode = (a >= 0 && a <= 5) ? a : 1;
                    if (d >= 0 && d <= 3) anim_dir = d;
                    if (s > 0) anim_steps = s > MTK_MAX_SWIPE ? MTK_MAX_SWIPE : s;
                    if (mp > 0 && mp <= 100) anim_min_pct = mp;
                }
            }
            else if (!strncmp(line, "PWRDOWN", 7)) {   /* PWRDOWN <ms>: panel power-down delay, -1 = back to the original */
                int v;
                if (sscanf(line + 7, "%d", &v) == 1 && v >= -1 && v <= 5000) set_pwrdown(v);
            }
            else if (!strncmp(line, "BADGE ", 6)) bg_command(line + 6);
            else if (!strncmp(line, "LAUNCH", 6)) {
                int v;
                if (sscanf(line + 6, "%d", &v) == 1 && v >= 0 && v <= 10000) {
                    if (v == 0) launch_end("LAUNCH 0");
                    else if (!g_rot) { if (!launch_until) { launch_t0 = 0; launch_badged = 0; } launch_sent = now_ms(); launch_until = launch_sent + v; }
                }
            }   /* gesture badge, see bg_tick */
            else if (!strncmp(line, "HINT", 4)) {
                int ms, pc;
                if (sscanf(line + 4, "%d %d", &ms, &pc) == 2 && ms > 0 && ms <= 3000 && pc >= 1 && pc <= 100) {
                    hint_until = now_ms() + ms; hint_pct = pc;
                    if (tlog) fprintf(stderr, "[%lld] epd: hint %d ms, reagl-min %d%%\n", now_ms(), ms, pc);
                }
            }
            else if (!strncmp(line, "SCROLLDELAY", 11)) { int v; if (sscanf(line + 11, "%d", &v) == 1 && v >= 0 && v <= 5000) { scroll_delay_ms = v; if (!v) { scroll_on = 0; scroll_frames = 0; } } }
            else if (!strncmp(line, "RT ", 3)) { int v; if (sscanf(line + 3, "%d", &v) == 1) set_rt(v, "epd"); }   /* RT <prio>: live, 0 = normal (the waiter thread keeps its policy) */
            else if (!strncmp(line, "SCROLLWFM", 9)) { int v; if (sscanf(line + 9, "%d", &v) == 1) scroll_a2 = v == 1; }
            else if (!strncmp(line, "SCROLLSTYLE", 11)) { int v; if (sscanf(line + 11, "%d", &v) == 1 && (v == 1 || v == 2)) scroll_style = v; }
            else if (!strncmp(line, "SCROLL ", 7)) {
                int v = atoi(line + 7);
                long long t = now_ms();
                if (scroll_delay_ms <= 0) { /* off */ }
                else if (v && !scroll_on) { scroll_on = 1; scroll_t_on = t; scroll_frames = 0; if (tlog) fprintf(stderr, "[%lld] epd: scroll begin\n", t); }
                else if (!v && scroll_on) {
                    scroll_on = 0; scroll_t_off = t;
                    if (tlog) fprintf(stderr, "[%lld] epd: scroll end after %lld ms, %d frames\n", t, t - scroll_t_on, scroll_frames);
                    if (held_why == 6 && held_page >= 0) held_until = t + 180;
                }
            }
            else if (!strncmp(line, "GCN", 3)) { int v; if (sscanf(line + 3, "%d", &v) == 1 && v >= 0 && v <= 1000) { gc_every = v; gc_count = 0; } }
            else if (!strcmp(line, "NEWWIN")) newwin_until = now_ms() + 3000;
            else if (!strncmp(line, "MONO", 4)) { int v; if (sscanf(line + 4, "%d", &v) == 1 && v >= 0 && v <= 254) mono_level = v; }
            else if (!strncmp(line, "MAXDEFER", 8)) { int v; if (sscanf(line + 8, "%d", &v) == 1 && v >= 0 && v <= 10000) max_defer_ms = v; }
            else if (!strcmp(line, "STATS"))
                fprintf(stderr, "[%lld] epd: stats sent=%lu flash=%lu qpass=%lu scroll_frames=%lu scroll_clean=%lu scroll_held=%lu gcn=%lu newwin=%lu text_nodither=%lu\n",
                        now_ms(), n_sent, n_flash, n_qpass, n_scroll_frames, n_scroll_clean, n_scroll_held, n_gcn, n_newwin, n_text_nodither);
            else if (!strncmp(line, "NOFLIP", 6)) { int v; if (sscanf(line + 6, "%d", &v) == 1 && v >= 0 && v <= 5000) noflip_until = now_ms() + v; }
            else if (!strncmp(line, "SETTLE2", 7)) { int v; if (sscanf(line + 7, "%d", &v) == 1 && v >= 0 && v <= 400) settle_first = v; }
            else if (!strncmp(line, "SCROLLDU", 8)) { int v; if (sscanf(line + 8, "%d", &v) == 1 && v >= 0 && v <= 5000) { burst_du_ms = waiter_on ? v : 0; if (!v) burst_dirty = 0; } }
            else if (!strncmp(line, "IMG", 3)) { int v; if (sscanf(line + 3, "%d", &v) == 1 && v >= 0 && v <= 100) img_gray_pct = v; }
            else if (!strncmp(line, "FLIPGAP", 7)) { int v; if (sscanf(line + 7, "%d", &v) == 1 && v >= 0 && v <= 5000) flip_gap_ms = v; }   /* FLIPGAP <ms>: see flip_gap_ms */
            else if (!strncmp(line, "TOL", 3)) { int v; if (sscanf(line + 3, "%d", &v) == 1) g_tolerant = v != 0; }   /* TOL <0|1>: tolerant change detection */
            else if (!strncmp(line, "REAGL", 5)) { int v; if (sscanf(line + 5, "%d", &v) == 1 && v >= 1 && v <= 100) reagl_min = v; }
            else if (!strncmp(line, "PACE", 4)) { int v; if (sscanf(line + 4, "%d", &v) == 1) pace = v != 0 && waiter_on; }
            else if (!strncmp(line, "CLEAN", 5)) {
                int v;
                if (sscanf(line + 5, "%d", &v) == 1 && v >= 0 && v <= 5000) { clean_ms = waiter_on ? v : 0; if (!clean_ms) cl_valid = 0; }
            }
            else if (!strncmp(line, "TURNHOLD", 8)) {   /* TURNHOLD <ms>: page-turn merge window (0 = off), see turn_quiet_ms */
                int v;
                if (sscanf(line + 8, "%d", &v) == 1 && v >= 0 && v <= 1000) {
                    turn_quiet_ms = v;
                    if (!v && held_why == 5 && held_page >= 0 && slide_quiet_ms != anim_quiet_ms) held_until = now_ms();
                    if (tlog) fprintf(stderr, "[%lld] epd: page-turn merge window %d ms\n", now_ms(), v);
                }
            }
            else if (!strncmp(line, "HOLD", 4)) {   /* HOLD <ms>: how long a small change waits for a following frame (0 = off) */
                int v;
                if (sscanf(line + 4, "%d", &v) == 1 && v >= 0 && v <= 300) hold_ms = v;
            }
            else if (!strncmp(line, "SWIPE", 5)) {
                /* SWIPE <dir> <steps>: animate the next change (one shot) with this direction (0 down, 1 up, 2 left, 3 right,
                   in the logical picture) and step count; the ANIM settings are left alone */
                int d = -1, s = 3;
                if (swipe_ok && !swipe_broken && sscanf(line + 5, "%d %d", &d, &s) >= 1 && d >= 0 && d <= 3) {
                    once_dir = d;
                    once_steps = s < 1 ? 1 : s > MTK_MAX_SWIPE ? MTK_MAX_SWIPE : s;
                    once_until = now_ms() + 800;
                    anim_next = 1;
                }
            }
            else if (sscanf(line, "P %d %c", &x, &m) >= 1) {
                page = x == 2 ? (fb2_ok() ? 2 : last_page) : x & 1;
                if (tlog) fprintf(stderr, "[%lld] epd: got %s\n", now_ms(), line);
                { int a, b; if (sscanf(line, "P %*d %d %d", &a, &b) == 2 && b > a) { if (a < ry0) ry0 = a; if (b > ry1) ry1 = b; } else rall = 1; }
                int mm = (sscanf(line, "P %*d %c", &m) == 1) ? (m == 'F' ? M_FULL : m == 'D' ? M_DU : m == 'G' ? M_GC16 : m == 'R' ? M_REAGL : M_AUTO) : M_AUTO;
                if (mm == M_FULL || mode == M_AUTO) mode = mm;
            }
            else if (sscanf(line, "D %d %d %d %d %c", &x, &y, &w, &h, &m) >= 4) {
                if (!have) { bx0 = x; by0 = y; bx1 = x + w; by1 = y + h; have = 1; }
                else {
                    if (x < bx0) bx0 = x;
                    if (y < by0) by0 = y;
                    if (x + w > bx1) bx1 = x + w;
                    if (y + h > by1) by1 = y + h;
                }
                int mm = m == 'F' ? M_FULL : m == 'D' ? M_DU : m == 'G' ? M_GC16 : m == 'R' ? M_REAGL : M_AUTO;
                if (mm == M_FULL || mode == M_AUTO) mode = mm;
            }
            line = nl + 1;
        }
        alen = strlen(line);
        memmove(acc, line, alen);
        /* leading edge: after a quiet moment the first frame goes out at once (a tap or a button press must answer quickly);
           only frames that follow closely (scrolling, animations) are merged */
        if (!(have || page >= 0) || settle_ms <= 0 || pace || redraw || full || quit || now_ms() - last_draw_ms > 350 ||
            scroll_active(now_ms())) break;
        {
            long long nowt = now_ms();
            if (!tstart) tstart = nowt;
            if (nowt - tstart >= (settle_ms > 400 ? settle_ms : 400)) break;
            fd_set rf2;
            FD_ZERO(&rf2);
            FD_SET(ffd, &rf2);
            /* adaptive settle: a short first window unless frames are already coming in a run */
            int sw = settle_ms;
            /* short only when the sender named the rows and they are a small band (a button, a checkbox, a line of text):
               a scroll or an animation changes most rows and keeps the full window, also when its frames come slowly */
            if (settle_first > 0 && settle_first < settle_ms && !merged && !rall && ry1 > ry0 && ry1 - ry0 < H / 5) sw = settle_first;
            merged = 1;
            struct timeval tv2 = {0, sw * 1000};
            if (select(ffd + 1, &rf2, NULL, NULL, &tv2) <= 0) break;
            if (alen >= sizeof(acc) - 1) {
                fprintf(stderr, "epd: fifo line too long (%zu bytes), discarding\n", alen);
                alen = 0;
            }
            ssize_t n2 = read(ffd, acc + alen, sizeof(acc) - alen - 1);
            if (n2 <= 0) break;
            alen += (size_t)n2;
            acc[alen] = 0;
        }
        }

        if (access(sleepf, F_OK) == 0) {   /* screensaver / boot splash: drop, but remember which page is the newest */
            if (page >= 0) last_page = page;
            held_page = -1; cl_valid = 0; burst_dirty = 0; need_full = 1; pend_y0 = -1; qp_valid = 0; scroll_dirty = 0; scroll_frames = 0; scroll_on = 0;
            continue;
        }
        /* last_draw_ms arms the settle merging for the frames that follow closely. It used to be set for every frame, also
           for the many frames that change nothing on screen (apps post those all the time): a real change that came within
           350 ms of such a frame then waited settle_ms (measured in WeRead: page turns +120..250 ms). Now only frames that
           really put something on the panel count. */
        unsigned long sent0 = n_sent;
        long long tframe = now_ms();
        /* H: the hint applies to this frame when it is fresh (500 ms) */
        fr_valid = 0;
        if (hint_mode >= 0 && (page >= 0 || have)) {
            if (tframe - hint_t <= 500) {
                if (hint_mode != M_AUTO && mode != M_FULL) mode = hint_mode;
                if (hr_valid && !g_rot) {
                    if (have) {
                        if (hr_x0 < bx0) bx0 = hr_x0;
                        if (hr_y0 < by0) by0 = hr_y0;
                        if (hr_x1 > bx1) bx1 = hr_x1;
                        if (hr_y1 > by1) by1 = hr_y1;
                    } else { fr_valid = 1; fr_x0 = hr_x0; fr_y0 = hr_y0; fr_x1 = hr_x1; fr_y1 = hr_y1; }
                }
                if (g_tlog) fprintf(stderr, "[%lld] epd: hint: mode %d%s\n", tframe, hint_mode, hr_valid ? " + rectangle" : "");
            }
            hint_mode = -1; hr_valid = 0;
        } else if (hint_mode >= 0 && tframe - hint_t > 500) { hint_mode = -1; hr_valid = 0; }
        if (page >= 0 || have) last_frame_ms = tframe;
        if (full || redraw || have) last_draw_ms = tframe;
        if (full) { if (page >= 0) last_page = page; held_page = -1; prev_valid = 0; cl_valid = 0; req_y0 = -1; show_page(fbmem, last_page, M_FULL, 1, 0, 0); }
        else if (redraw) { if (page >= 0) last_page = page; held_page = -1; prev_valid = 0; cl_valid = 0; req_y0 = -1; show_page(fbmem, last_page, M_REAGL, 1, 0, 0); }
        else if (page >= 0) {
            /* a frame that arrives while a small change is held is drawn at once, whatever its size: it already waited.
               A frame that arrives while another one waits for the panel replaces it (newest picture wins) and keeps
               that one's safety deadline. */
            long long nowt2 = now_ms();
            if (launch_until) {
                int blank = nowt2 < launch_until && mode != M_FULL && prev_valid && page_blank(fbmem, page);
                if (g_tlog) fprintf(stderr, "[%lld] epd: launch frame: content rows %d.%d%% -> %s\n", nowt2, launch_rows / 10, launch_rows % 10, blank ? "blank" : "content");
                /* content in the first moments after LAUNCH, before any flat frame, is still the old picture being
                   redrawn (the icon that was pressed, the previous app's last frame): keep waiting for the app */
                if (!blank && !launch_t0 && nowt2 < launch_until && nowt2 - launch_sent < 1500) goto launch_show;
                if (blank) {
                    if (!launch_t0) { launch_t0 = nowt2; fprintf(stderr, "[%lld] epd: launch hold: blank frame kept off the panel\n", nowt2); }
                    n_launch_held++;
                    need_full = 1;      /* the rows the next frame names are relative to this blank frame, not to the panel */
                    held_page = page; held_mode = mode; held_why = 4;
                    held_until = launch_badged ? launch_until : launch_t0 + 600 < launch_until ? launch_t0 + 600 : launch_until;
                    last_page = page;
                    continue;
                }
                launch_end(nowt2 >= launch_until ? "timeout" : "content");
                if (held_why == 4) held_page = -1;
            }
            launch_show:;
            int frz = freeze_until && nowt2 < freeze_until;
            if (freeze_until && !frz) freeze_until = 0;
            if (frz || (scroll_style == 2 && scroll_delay_ms > 0 && mode != M_FULL && (scroll_on || (held_page >= 0 && held_why == 6)))) {
                /* SCROLLSTYLE 2: nothing reaches the panel during the drag; the newest picture waits for the end */
                if (held_page < 0 || held_why != 6) { n_scroll_held = 0; held_mode = M_AUTO; }
                held_page = page; if (mode == M_FULL) held_mode = M_FULL; held_why = 6; last_page = page; need_full = 1; n_scroll_held++;
                if (frz) held_until = freeze_until;
                else if (scroll_on) held_until = nowt2 + SCROLL_MAX_DRAG;
                else { held_until = nowt2 + 180; if (held_until > scroll_t_off + 1200) held_until = scroll_t_off + 1200; }
                continue;
            }
            if (held_why == 6) held_page = -1;
            /* a hold that frames keep renewing (an endless animation) is drawn at its cap: the timeout branch only runs
               when no frame comes */
            int overdue = held_page >= 0 && nowt2 >= held_until && (held_why == 5 || held_why == 3);
            if (!overdue && held_page >= 0 && max_defer_ms > 0 && (held_why == 1 || held_why == 2) && nowt2 - held_t0 >= max_defer_ms) overdue = 1;
            if (overdue && g_tlog) fprintf(stderr, "[%lld] epd: hold %d overdue after %lld ms: drawing\n", nowt2, held_why, nowt2 - held_t0);
            if (overdue && held_why == 3) {
                held_page = -1; req_y0 = -1; pend_y0 = -1;
                show_page(fbmem, page, M_FULL, 1, 0, 0);
                last_draw_ms = tframe;
                continue;
            }
            if (overdue) { held_page = -1; req_y0 = -1; pend_y0 = -1; need_full = 1; g_lead = 0; show_page(fbmem, page, mode, 0, 0, 0); last_draw_ms = tframe; continue; }
            if (held_page >= 0 && held_why == 5 && mode != M_FULL) {
                /* page-slide hold: this newer picture replaces the held one, the quiet window starts again */
                held_page = page;
                held_until = nowt2 + slide_quiet_ms;
                if (held_until > anim_t0 + anim_max_ms) held_until = anim_t0 + anim_max_ms;
                continue;
            }
            if (held_why == 5) held_page = -1;   /* a FULL request ends the slide hold */
            int was_hold = held_page >= 0 && held_why == 1, was_paced = held_page >= 0 && held_why == 2;
            if (held_page >= 0 && held_why == 3 && mode != M_FULL) {
                /* a page-count flash is waiting for the picture to settle: this newer picture replaces it, the wait
                   starts again (up to flash_max_ms after the flip began); nothing is drawn in between */
                held_page = page;
                held_until = nowt2 + flash_quiet_ms;
                if (held_until > flash_t0 + flash_max_ms) held_until = flash_t0 + flash_max_ms;
                continue;
            }
            held_page = -1;
            if (rall || ry1 <= ry0) req_y0 = -1; else { req_y0 = ry0; req_y1 = ry1; }
            g_lead = nowt2 - last_draw_ms > 350;
            int r = show_page(fbmem, page, mode, mode == M_FULL, !was_hold && !was_paced, !(was_paced && nowt2 >= held_until));
            g_lead = 0;
            if (r && !was_hold && !was_paced) held_t0 = nowt2;
            if (r == 4) { held_page = page; held_mode = mode; held_why = 5; anim_t0 = nowt2; held_until = nowt2 + slide_quiet_ms; }
            if (r == 1) { held_page = page; held_mode = mode; held_why = 1; held_until = nowt2 + hold_ms; }
            if (r == 3) { held_page = page; held_mode = M_FULL; held_why = 3; flash_t0 = nowt2; held_until = nowt2 + flash_quiet_ms; }
            if (r && g_tlog)
                fprintf(stderr, "[%lld] epd: kept back (%s)\n", now_ms(), r == 1 ? "small change" : r == 3 ? "page-count flash, waiting for the picture to settle" : r == 4 ? "edge strip: page-slide hold" : "panel busy");
            if (r == 2) { held_page = page; held_mode = mode; held_why = 2; if (!was_paced) held_until = nowt2 + 700; }
            if (n_sent != sent0) last_draw_ms = tframe;
        }
        else if (have) push(fbmem, bx0, by0, bx1 - bx0, by1 - by0, mode);
    }
    return 0;
}


/* paint <shm> <fifo> <page> [text-free pattern]: write a test picture into the shared buffer and post it (pipeline test) */
static int cmd_paint(const char *shm, const char *fifo, int page, int variant)
{
    int sfd = open(shm, O_RDWR | O_CREAT, 0666);
    if (sfd < 0) { perror("epd: open shm"); return 1; }
    if (ftruncate(sfd, (off_t)W * H * 4 * 2) < 0) perror("epd: ftruncate");
    uint8_t *m = mmap(NULL, (size_t)W * H * 4 * 2, PROT_READ | PROT_WRITE, MAP_SHARED, sfd, 0);
    if (m == MAP_FAILED) { perror("epd: mmap"); return 1; }
    uint8_t *img = m + (size_t)(page & 1) * W * H * 4;
    fill_test(img);                       /* RGBA from the generator; the bridge ignores the 4th byte */
    if (variant) {                        /* second picture: invert a block so the diff has something small to find */
        for (int y = 1300; y < 1500; y++)
            for (int x = 300; x < 700; x++) {
                uint8_t *p = img + ((size_t)y * W + x) * 4;
                p[0] = p[1] = p[2] = 0;
            }
    }
    int ffd = open(fifo, O_WRONLY | O_NONBLOCK);
    if (ffd < 0) { perror("epd: open fifo (is 'run' active?)"); return 1; }
    char msg[32];
    int n = snprintf(msg, sizeof(msg), "P %d\n", page & 1);
    if (write(ffd, msg, n) < 0) perror("epd: write fifo");
    close(ffd);
    return 0;
}

#ifndef SCHED_RESET_ON_FORK
#define SCHED_RESET_ON_FORK 0x40000000
#endif

/* --rt <prio>: SCHED_FIFO at this priority (1..20), SCHED_RESET_ON_FORK so the programs we start do not inherit it.
   On two cores the app drawing a scroll keeps both busy and this program waited 4-12 ms per frame in the run queue
  . Failure (no CAP_SYS_NICE, RT budget 0) is only logged. 0 = back to normal scheduling. */
static void set_rt(int prio, const char *who)
{
    static int cur = 0;           /* 0 = normal scheduling; a later call with 0 goes back to it */
    if (prio < 0) prio = 0;
    if (prio > 20) prio = 20;
    if (prio == cur) return;
    struct sched_param sp = { .sched_priority = prio };
    if (sched_setscheduler(0, (prio ? SCHED_FIFO : SCHED_OTHER) | SCHED_RESET_ON_FORK, &sp) < 0) { fprintf(stderr, "%s: SCHED_FIFO %d: %s\n", who, prio, strerror(errno)); return; }
    cur = prio;
    fprintf(stderr, "%s: %s %d\n", who, prio ? "SCHED_FIFO" : "SCHED_OTHER", prio);
}

int main(int argc, char **argv)
{
    double gamma = 1.3;
    int rt_prio = 0;
    const char *args[8];
    int na = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--w") && i + 1 < argc) W = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--h") && i + 1 < argc) H = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--contrast") && i + 1 < argc) gamma = atof(argv[++i]);
        else if (!strcmp(argv[i], "--dither") && i + 1 < argc) dither = (uint8_t)atoi(argv[++i]);
        else if (!strcmp(argv[i], "--flash") && i + 1 < argc) flash_every = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--mode") && i + 1 < argc) ui_mode = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--settle") && i + 1 < argc) settle_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--hold") && i + 1 < argc) hold_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--anim-hold") && i + 1 < argc) anim_quiet_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--pace") && i + 1 < argc) pace = atoi(argv[++i]) != 0;
        else if (!strcmp(argv[i], "--clean") && i + 1 < argc) clean_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--scroll-delay") && i + 1 < argc) scroll_delay_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--scroll-style") && i + 1 < argc) scroll_style = atoi(argv[++i]) == 2 ? 2 : 1;
        else if (!strcmp(argv[i], "--gcn") && i + 1 < argc) gc_every = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--mono") && i + 1 < argc) mono_level = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--max-defer") && i + 1 < argc) max_defer_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--flip-input") && i + 1 < argc) flip_input_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--reagl-min") && i + 1 < argc) { reagl_min = atoi(argv[++i]); if (reagl_min < 1 || reagl_min > 100) reagl_min = 35; }
        else if (!strcmp(argv[i], "--anim") && i + 1 < argc) anim_mode = atoi(argv[++i]) ? 1 : 0;
        else if (!strcmp(argv[i], "--anim-mode") && i + 1 < argc) anim_mode = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--anim-dir") && i + 1 < argc) anim_dir = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--anim-steps") && i + 1 < argc) anim_steps = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--pwrdown") && i + 1 < argc) pwrdown_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--skip-cfa") && i + 1 < argc) skip_cfa = atoi(argv[++i]) != 0;
        else if (!strcmp(argv[i], "--anim-min") && i + 1 < argc) anim_min_pct = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--swipe") && i + 1 < argc) swipe_ok = atoi(argv[++i]) != 0;
        else if (!strcmp(argv[i], "--invert") && i + 1 < argc) g_invert = atoi(argv[++i]) != 0;
        else if (!strcmp(argv[i], "--settle-first") && i + 1 < argc) settle_first = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--scroll-du") && i + 1 < argc) burst_du_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--img-gray") && i + 1 < argc) img_gray_pct = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--qpass") && i + 1 < argc) qpass_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--flip-gap") && i + 1 < argc) flip_gap_ms = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--tolerant") && i + 1 < argc) g_tolerant = atoi(argv[++i]) != 0;
        else if (!strcmp(argv[i], "--lightf") && i + 1 < argc) g_lightf = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--darken") && i + 1 < argc) g_darken = atoi(argv[++i]) == 1;
        else if (!strcmp(argv[i], "--rotate") && i + 1 < argc) g_rot = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--vw") && i + 1 < argc) VW = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--rt") && i + 1 < argc) rt_prio = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--scroll-a2") && i + 1 < argc) scroll_a2 = atoi(argv[++i]) == 1;
        else if (na < 8) args[na++] = argv[i];
    }
    if (na < 1) { fprintf(stderr, "usage: %s run|test|file ...\n", argv[0]); return 2; }
    if (g_rot != 90 && g_rot != 180 && g_rot != 270) g_rot = 0;
    if (strcmp(args[0], "run")) g_rot = 0;     /* test / file / paint draw panel-space pictures */
    if (VW <= 0 || VW > W) VW = W;
    LW = (g_rot == 90 || g_rot == 270) ? H : W;
    LH = (g_rot == 90 || g_rot == 270) ? VW : H;
    if (!strcmp(args[0], "paint") && na >= 4) return cmd_paint(args[1], args[2], atoi(args[3]), na >= 5);
    signal(SIGINT, on_sig);
    signal(SIGTERM, on_sig);
    g_gamma = gamma;
    build_lut(gamma);
    if (anim_steps < 1) anim_steps = 1;
    if (anim_steps > MTK_MAX_SWIPE) anim_steps = MTK_MAX_SWIPE;
    if (open_fb() < 0) return 1;
    if (pwrdown_ms >= 0) set_pwrdown(pwrdown_ms);

    int rc = 0;
    if (!strcmp(args[0], "run") && na >= 4) { set_rt(rt_prio, "epd"); rc = cmd_run(args[1], args[2], args[3]); }
    else if (!strcmp(args[0], "test")) {
        uint8_t *img = malloc((size_t)W * H * 4);
        fill_test(img);
        rc = push(img, 0, 0, W, H, M_FULL) < 0;
        sleep(2);
        /* a small partial update with the fast waveform */
        for (int i = 0; i < 4; i++) {
            for (int y = 1100; y < 1300; y++)
                for (int x = 100; x < 500; x++) {
                    uint8_t *p = img + ((size_t)y * W + x) * 4;
                    p[0] = p[1] = p[2] = (i & 1) ? 255 : 0;
                }
            push(img, 100, 1100, 400, 200, M_DU);
            sleep(1);
        }
        free(img);
    } else if (!strcmp(args[0], "file") && na >= 2) {
        FILE *f = fopen(args[1], "rb");
        if (!f) { perror("epd: open file"); return 1; }
        uint8_t *img = malloc((size_t)W * H * 4);
        size_t got = fread(img, 1, (size_t)W * H * 4, f);
        fclose(f);
        fprintf(stderr, "epd: read %zu bytes\n", got);
        rc = push(img, 0, 0, W, H, M_FULL) < 0;
        free(img);
    } else { fprintf(stderr, "bad arguments\n"); rc = 2; }
    restore_pwrdown();
    fbink_close(fbfd);
    return rc;
}
