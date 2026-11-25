// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * hwcomposer.kindle: a software hardware-composer for VeraDroid's virtual e-ink framebuffer.
 *
 * Implements HWC_DEVICE_API_VERSION_1_5 so SurfaceFlinger passes per-layer surfaceDamage regions
 * (skipping unchanged frames and rows without touching pixel memory) and planeAlpha directly.
 * With /dev/kfb/y8.ok, layers are converted in a single NEON pass straight into 8-bit luma pages
 * (/dev/kfb/y8) and the unused 16.2 MB /dev/kfb/fb tmpfs backing pages are punched out via ftruncate.
 */
#define _GNU_SOURCE
#define LOG_TAG "hwc_kindle"
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#ifndef FALLOC_FL_KEEP_SIZE
#define FALLOC_FL_KEEP_SIZE 0x01
#endif
#ifndef FALLOC_FL_PUNCH_HOLE
#define FALLOC_FL_PUNCH_HOLE 0x02
#endif

#if defined(__ARM_NEON__) || defined(__ARM_NEON)
#include <arm_neon.h>
#define VD_HAS_NEON 1
#endif
#include <cutils/log.h>
#include <hardware/hardware.h>
#include <hardware/hwcomposer.h>

#ifndef KFB_DIR
#define KFB_DIR "/dev/kfb"            /* tests/hwc-test.sh builds the module for the host with another directory */
#endif
#define KFB_FILE KFB_DIR "/fb"
#define KFB_INFO KFB_DIR "/info"
#define KFB_FIFO KFB_DIR "/epd.fifo"
#define KFB_OFF  KFB_DIR "/hwc.off"
#define KFB_GLES KFB_DIR "/hwc.gles"
#define KFB_HZ   KFB_DIR "/hz"
#define KFB_PAGE KFB_DIR "/page"
#define KFB_ONEPAGE KFB_DIR "/onepage"
#define KFB_DIM  KFB_DIR "/hwc.dim"
#define KFB_FB2  KFB_DIR "/fb2"
#define KFB_FB2OK KFB_DIR "/fb2.ok"
#define KFB_Y8   KFB_DIR "/y8"
#define KFB_Y8OK KFB_DIR "/y8.ok"
#define DIM_DEFAULT 51

/* the default gralloc's private_handle_t (gralloc_priv.h), C view */
struct vd_handle {
    native_handle_t nh;
    int fd;
    int magic;
    int flags;
    int size;
    int offset;
    uint64_t base __attribute__((aligned(8)));
    int pid;
};
#define VD_MAGIC 0x3141592
#define VD_FLAG_FB 1

struct layer_sig {
    uintptr_t handle;
    hwc_rect_t df, sc;
    uint32_t vis_hash;
    uint32_t transform;
    int32_t blending;
    uint8_t planeAlpha;
    uint8_t bpp;
    uint16_t stride;
};

static uint32_t region_sig_hash(const hwc_region_t *r)
{
    if (!r || !r->rects || r->numRects == 0) return 0;
    uint32_t h = (uint32_t)r->numRects * 2654435761u;
    for (size_t i = 0; i < r->numRects; i++) {
        h = (h ^ (uint32_t)r->rects[i].left) * 16777619u;
        h = (h ^ (uint32_t)r->rects[i].top) * 16777619u;
        h = (h ^ (uint32_t)r->rects[i].right) * 16777619u;
        h = (h ^ (uint32_t)r->rects[i].bottom) * 16777619u;
    }
    return h;
}

struct ctx {
    hwc_composer_device_1_t dev;
    const hwc_procs_t *procs;
    int w, h;
    int fb_fd;                /* kept open to release tmpfs backing pages when Y8 owns the screen */
    int fb_trimmed;           /* 1 while /dev/kfb/fb physical tmpfs pages have been punched out */
    uint8_t *fb;              /* 2 pages of RGBX */
    uint8_t *fb2;             /* page 2: /dev/kfb/fb2, ours alone (frames with a dim layer, see hwc_set) */
    int fb2_failed;
    int fb2_ok;               /* fb2.ok: the bridge shows page 2 (checked once a second) */
    unsigned long n_leave2;   /* frames that moved the picture from page 2 back to page 0 / 1 */
    size_t page_bytes;
    int fifo;
    int last_page;            /* the page veradroid-epd was told about last */
    int ovl_since_gles;       /* an overlay frame was drawn since the last GLES frame: the pages differ */
    int overlay;              /* decision of the last prepare() */
    int force_gles;
    int dim_cap;              /* hwc.dim: the dim alpha we draw; < 0 = dim frames go to GLES */
    int page_noted;
    int lost;                 /* an announcement to the bridge was not delivered */
    time_t force_checked;
    long period_ns;
    pthread_t vthread;
    pthread_mutex_t lock;
    pthread_cond_t cond;
    int vsync_on;
    int onepage;              /* /dev/kfb/onepage: both framebuffer pages are page 0 */
    unsigned long n_ovl, n_gles, n_sync, n_same;
    uint32_t *rowbuf;         /* one composed row */
    uint8_t *dirty_y;         /* per-row surfaceDamage bitmap */
    int prev_valid;
    size_t prev_cnt;
    int prev_dim_cap;
    struct layer_sig prev_sig[32];
    unsigned long n_skip_frames, n_skip_rows;
    long last_us, max_us;
    unsigned long long sum_us;     /* all overlay frames: average cost in the dump */
    unsigned long n_rows_direct, n_rows_blend;
    unsigned long n_dim;      /* overlay frames with a dim layer */
    uint8_t *y8;              /* 3 pages of 8-bit luma (/dev/kfb/y8: pages 0/1, plus page 2 for dim frames) */
    size_t y8_page_bytes;     /* w * h */
    int y8_failed;
    int y8_ok;                /* y8.ok: bridge reads /dev/kfb/y8 via "Y <page> [y0 y1]" */
    int y8_inited;            /* first Y8 frame must announce all rows even if pixels are all 0 */
    int gles_since_y8;        /* a GLES frame went out to /dev/kfb/fb since our last Y8 frame */
    int y8_since_gles;        /* a Y8 frame went out since our last GLES frame */
    int gles_cooldown;        /* Y8 frames to wait after GLES before punching /dev/kfb/fb */
};

static long long now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void notify_cmd(struct ctx *c, char cmd, int page, int y0, int y1)
{
    c->last_page = page;
    int note = (cmd == 'Y') ? (8 + page) : page;
    if (note != c->page_noted) {          /* for a bridge that is started later: which page holds the picture */
        int pfd = open(KFB_PAGE, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
        if (pfd >= 0) {
            char nb[12];
            int nl = snprintf(nb, sizeof(nb), "%d", note);
            if (write(pfd, nb, nl) == nl) c->page_noted = note;
            close(pfd);
        }
    }
    if (c->fifo < 0) {
        c->fifo = open(KFB_FIFO, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (c->fifo < 0) { c->lost = 1; return; }      /* the bridge is not running (yet): drop the update */
    }
    char msg[40];
    int n = (y1 > y0 && !c->lost) ? snprintf(msg, sizeof(msg), "%c %d %d %d\n", cmd, page, y0, y1)
                                  : snprintf(msg, sizeof(msg), "%c %d\n", cmd, page);
    if (write(c->fifo, msg, n) != n) {
        c->lost = 1;
        if (errno != EAGAIN) { close(c->fifo); c->fifo = -1; }
    } else c->lost = 0;
}
static void notify(struct ctx *c, int page, int y0, int y1) { notify_cmd(c, 'P', page, y0, y1); }

static size_t page_round(size_t v) { return (v + 4095) & ~(size_t)4095; }

static uint8_t *page_ptr(struct ctx *c, int page) { return page == 2 ? c->fb2 : c->fb + (size_t)(page & 1) * c->page_bytes; }
static uint8_t *y8_page_ptr(struct ctx *c, int page)
{
    int k = (page == 2) ? 2 : (page & 1);
    return c->y8 + (size_t)k * c->y8_page_bytes;
}

static void fb_trim_pages(struct ctx *c)
{
    if (c->gles_cooldown > 0) { c->gles_cooldown--; return; }
    if (c->fb_trimmed || c->fb_fd < 0 || !c->y8_inited) return;
    if (fallocate(c->fb_fd, FALLOC_FL_PUNCH_HOLE | FALLOC_FL_KEEP_SIZE, 0, (off_t)(c->page_bytes * 2)) == 0)
        c->fb_trimmed = 1;
}

static int y8_map(struct ctx *c)
{
    if (c->y8) return 1;
    if (c->y8_failed || !c->y8_page_bytes) return 0;
    int fd = open(KFB_Y8, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    struct stat sb;
    size_t need = c->y8_page_bytes * 3;
    if (fd >= 0) (void)fchmod(fd, 0666);
    if (fd >= 0 && fstat(fd, &sb) == 0 &&
        ((size_t)sb.st_size >= need || ftruncate(fd, (off_t)need) == 0)) {
        void *m = mmap(NULL, need, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (m != MAP_FAILED) c->y8 = m;
    }
    if (fd >= 0) close(fd);
    if (!c->y8) { c->y8_failed = 1; ALOGW("cannot map %s: staying on RGBX framebuffer", KFB_Y8); }
    return c->y8 != NULL;
}

static int fb2_map(struct ctx *c)
{
    if (c->fb2) return 1;
    if (c->fb2_failed) return 0;
    int fd = open(KFB_FB2, O_RDWR | O_CREAT | O_CLOEXEC, 0666);
    struct stat sb;
    if (fd >= 0 && fchmod(fd, 0666) == 0 && fstat(fd, &sb) == 0 &&
        ((size_t)sb.st_size >= c->page_bytes || ftruncate(fd, (off_t)c->page_bytes) == 0)) {
        void *m = mmap(NULL, c->page_bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (m != MAP_FAILED) c->fb2 = m;
    }
    if (fd >= 0) close(fd);
    if (!c->fb2) { c->fb2_failed = 1; ALOGW("cannot map %s: dim frames compose into the framebuffer target", KFB_FB2); }
    return c->fb2 != NULL;
}

/* HWC 1.3+ passes sourceCropf (floats). Accept only exact integer crops; sub-pixel crops fall back to GLES. */
static int layer_crop(const hwc_layer_1_t *l, hwc_rect_t *r)
{
    float fl = l->sourceCropf.left, ft = l->sourceCropf.top,
          fr = l->sourceCropf.right, fb = l->sourceCropf.bottom;
    int il = (int)fl, it = (int)ft, ir = (int)fr, ib = (int)fb;
    if ((float)il != fl || (float)it != ft || (float)ir != fr || (float)ib != fb)
        return 0;
    r->left = il; r->top = it; r->right = ir; r->bottom = ib;
    return 1;
}

static int is_dim(const struct ctx *c, const hwc_layer_1_t *l)
{
    return c->dim_cap >= 0 && ((c->y8_ok && !c->y8_failed) || (!c->onepage && c->fb2_ok && !c->fb2_failed)) && !l->handle && (l->flags & HWC_SKIP_LAYER) &&
           l->compositionType == HWC_FRAMEBUFFER && l->blending == HWC_BLENDING_PREMULT &&
           l->displayFrame.right > l->displayFrame.left && l->displayFrame.bottom > l->displayFrame.top;
}

static int layer_ok2(const struct ctx *c, const hwc_layer_1_t *l, int *stride, int *bpp, hwc_rect_t *crop_out)
{
    if (is_dim(c, l)) {
        *stride = 1;
        if (bpp) *bpp = 0;
        if (crop_out) *crop_out = (hwc_rect_t){0, 0, 0, 0};
        return 1;
    }
    if (l->flags & HWC_SKIP_LAYER) return 0;
    if (l->compositionType == HWC_BACKGROUND || l->compositionType == HWC_SIDEBAND) return 0;
    if (!l->handle) return 0;
    if (l->transform != 0) return 0;
    if (l->blending != HWC_BLENDING_NONE && l->blending != HWC_BLENDING_PREMULT) return 0;
    if (l->planeAlpha != 255) return 0;
    const struct vd_handle *h = (const struct vd_handle *)l->handle;
    if (h->nh.numFds != 1 || h->magic != VD_MAGIC || (h->flags & VD_FLAG_FB) || !h->base) return 0;
    hwc_rect_t sc;
    if (!layer_crop(l, &sc)) return 0;
    const hwc_rect_t *s = &sc, *d = &l->displayFrame;
    int sw = s->right - s->left, sh = s->bottom - s->top;
    if (sw <= 0 || sh <= 0 || s->left < 0 || s->top < 0) return 0;
    if (sw != d->right - d->left || sh != d->bottom - d->top) return 0;
    int st = 0, bh = 0, b = 0;
    size_t hsz = (size_t)h->size;
    int fst = (c->w + 1) & ~1, fbh = (c->h + 1) & ~1;
    #define MATCH_SZ(ST, BH, AMBIG) ( \
        ((page_round((size_t)(BH) * (ST) * 4 + 4) == hsz && (b = 4)) || \
         (l->blending == HWC_BLENDING_NONE && page_round((size_t)(BH) * (ST) * 2 + 4) == hsz && (b = 2))) && \
        (!(AMBIG) || (page_round((size_t)(BH) * ((ST) + 2) * b + 4) != hsz && \
                      ((ST) <= 2 || page_round((size_t)(BH) * ((ST) - 2) * b + 4) != hsz) && \
                      page_round((size_t)((BH) + 2) * (ST) * b + 4) != hsz && \
                      ((BH) <= 2 || page_round((size_t)((BH) - 2) * (ST) * b + 4) != hsz))) )
    if (s->left == 0 && s->top == 0) {
        st = (s->right + 1) & ~1; bh = (s->bottom + 1) & ~1;
        if (!MATCH_SZ(st, bh, 0)) {
            if (s->right <= fst && s->bottom <= fbh && MATCH_SZ(fst, fbh, s->right < c->w)) { st = fst; bh = fbh; }
            else return 0;
        }
    } else if (s->left == 0 && MATCH_SZ((s->right + 1) & ~1, (s->bottom + 1) & ~1, s->right < c->w)) {
        st = (s->right + 1) & ~1; bh = (s->bottom + 1) & ~1;
    } else if (s->right <= fst && s->bottom <= fbh && MATCH_SZ(fst, fbh, s->right < c->w)) {
        st = fst; bh = fbh;
    } else if (MATCH_SZ((s->right + s->left + 1) & ~1, (s->bottom + s->top + 1) & ~1, 1)) {
        st = (s->right + s->left + 1) & ~1; bh = (s->bottom + s->top + 1) & ~1;
    } else {
        return 0;
    }
    #undef MATCH_SZ
    *stride = st;
    if (bpp) *bpp = b;
    if (crop_out) *crop_out = sc;
    return 1;
}
static int layer_ok(const struct ctx *c, const hwc_layer_1_t *l, int *stride) { int b; return layer_ok2(c, l, stride, &b, NULL); }

#ifdef VD_HAS_NEON
static inline uint8x16_t rgb565_16_to_y8(uint16x8_t p0, uint16x8_t p1,
                                         uint8x8_t cr, uint8x8_t cg, uint8x8_t cb)
{
    uint8x16_t r_hi = vandq_u8(vcombine_u8(vshrn_n_u16(p0, 8),  vshrn_n_u16(p1, 8)),  vdupq_n_u8(0xf8));
    uint8x16_t g_hi = vshlq_n_u8(vcombine_u8(vshrn_n_u16(p0, 5),  vshrn_n_u16(p1, 5)),  2);
    uint8x16_t b_hi = vshlq_n_u8(vcombine_u8(vmovn_u16(p0),       vmovn_u16(p1)),       3);
    uint8x16_t r8 = vsriq_n_u8(r_hi, r_hi, 5);
    uint8x16_t g8 = vsriq_n_u8(g_hi, g_hi, 6);
    uint8x16_t b8 = vsriq_n_u8(b_hi, b_hi, 5);
    uint16x8_t lo = vmull_u8(vget_low_u8(r8), cr), hi = vmull_u8(vget_high_u8(r8), cr);
    lo = vmlal_u8(lo, vget_low_u8(g8), cg);        hi = vmlal_u8(hi, vget_high_u8(g8), cg);
    lo = vmlal_u8(lo, vget_low_u8(b8), cb);        hi = vmlal_u8(hi, vget_high_u8(b8), cb);
    return vcombine_u8(vshrn_n_u16(lo, 8), vshrn_n_u16(hi, 8));
}

static inline uint8x16_t rgba8888_16_to_y8(const uint8_t *s,
                                           uint8x8_t cr, uint8x8_t cg, uint8x8_t cb)
{
    uint8x16x4_t v = vld4q_u8(s);
    uint16x8_t lo = vmull_u8(vget_low_u8(v.val[0]), cr), hi = vmull_u8(vget_high_u8(v.val[0]), cr);
    lo = vmlal_u8(lo, vget_low_u8(v.val[1]), cg);        hi = vmlal_u8(hi, vget_high_u8(v.val[1]), cg);
    lo = vmlal_u8(lo, vget_low_u8(v.val[2]), cb);        hi = vmlal_u8(hi, vget_high_u8(v.val[2]), cb);
    return vcombine_u8(vshrn_n_u16(lo, 8), vshrn_n_u16(hi, 8));
}

static inline uint32_t neon_any_nz(uint8x16_t v)
{
    uint32x2_t d = vreinterpret_u32_u8(vorr_u8(vget_low_u8(v), vget_high_u8(v)));
    return vget_lane_u32(d, 0) | vget_lane_u32(d, 1);
}
#endif

/* Fused single-pass RGB565 -> Y8 luma conversion + 64B pure-white/black e-ink fastpath */
static int row565_to_y8_diff(uint8_t *dy, const uint16_t *s, int w)
{
    int changed = 0, x = 0;
#ifdef VD_HAS_NEON
    const uint8x8_t cr = vdup_n_u8(77), cg = vdup_n_u8(151), cb = vdup_n_u8(28);
    const uint8x16_t vff = vdupq_n_u8(0xff), v00 = vdupq_n_u8(0);
    for (; x + 32 <= w; x += 32) {
        __builtin_prefetch(s + x + 96, 0, 0);
        uint16x8_t p0 = vld1q_u16(s + x),      p1 = vld1q_u16(s + x + 8);
        uint16x8_t p2 = vld1q_u16(s + x + 16), p3 = vld1q_u16(s + x + 24);
        uint8x16_t d0 = vld1q_u8(dy + x),      d1 = vld1q_u8(dy + x + 16);
        uint8x16_t sand = vreinterpretq_u8_u16(vandq_u16(vandq_u16(p0, p1), vandq_u16(p2, p3)));
        if (!neon_any_nz(vmvnq_u8(sand))) {
            if (neon_any_nz(vmvnq_u8(vandq_u8(d0, d1)))) {
                vst1q_u8(dy + x, vff); vst1q_u8(dy + x + 16, vff);
                changed = 1;
            }
            continue;
        }
        uint8x16_t sor = vreinterpretq_u8_u16(vorrq_u16(vorrq_u16(p0, p1), vorrq_u16(p2, p3)));
        if (!neon_any_nz(sor)) {
            if (neon_any_nz(vorrq_u8(d0, d1))) {
                vst1q_u8(dy + x, v00); vst1q_u8(dy + x + 16, v00);
                changed = 1;
            }
            continue;
        }
        uint8x16_t y0 = rgb565_16_to_y8(p0, p1, cr, cg, cb);
        uint8x16_t y1 = rgb565_16_to_y8(p2, p3, cr, cg, cb);
        if (neon_any_nz(vorrq_u8(veorq_u8(y0, d0), veorq_u8(y1, d1)))) {
            vst1q_u8(dy + x, y0); vst1q_u8(dy + x + 16, y1);
            changed = 1;
        }
    }
    for (; x + 16 <= w; x += 16) {
        uint8x16_t vy = rgb565_16_to_y8(vld1q_u16(s + x), vld1q_u16(s + x + 8), cr, cg, cb);
        if (neon_any_nz(veorq_u8(vy, vld1q_u8(dy + x)))) {
            vst1q_u8(dy + x, vy);
            changed = 1;
        }
    }
#endif
    for (; x < w; x++) {
        uint32_t p = s[x];
        uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
        r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
        uint8_t y = (uint8_t)((77u * r + 151u * g + 28u * b) >> 8);
        if (dy[x] != y) { dy[x] = y; changed = 1; }
    }
    return changed;
}

/* Fused single-pass RGBX/RGBA8888 -> Y8 luma conversion + 64B pure-white/black fastpath */
static int row8888_to_y8_diff(uint8_t *dy, const uint8_t *s, int w)
{
    int changed = 0, x = 0;
#ifdef VD_HAS_NEON
    const uint8x8_t cr = vdup_n_u8(77), cg = vdup_n_u8(151), cb = vdup_n_u8(28);
    const uint8x16_t vff = vdupq_n_u8(0xff), v00 = vdupq_n_u8(0);
    const uint32x4_t rgb_mask = vdupq_n_u32(0x00ffffffu);
    for (; x + 16 <= w; x += 16, s += 64) {
        __builtin_prefetch(s + 192, 0, 0);
        uint32x4_t q0 = vld1q_u32((const uint32_t *)s),      q1 = vld1q_u32((const uint32_t *)(s + 16));
        uint32x4_t q2 = vld1q_u32((const uint32_t *)(s + 32)), q3 = vld1q_u32((const uint32_t *)(s + 48));
        uint8x16_t d0 = vld1q_u8(dy + x);
        uint32x4_t wand = vandq_u32(vandq_u32(q0, q1), vandq_u32(q2, q3));
        if (!neon_any_nz(vreinterpretq_u8_u32(veorq_u32(vandq_u32(wand, rgb_mask), rgb_mask)))) {
            if (neon_any_nz(vmvnq_u8(d0))) { vst1q_u8(dy + x, vff); changed = 1; }
            continue;
        }
        uint32x4_t wor = vandq_u32(vorrq_u32(vorrq_u32(q0, q1), vorrq_u32(q2, q3)), rgb_mask);
        if (!neon_any_nz(vreinterpretq_u8_u32(wor))) {
            if (neon_any_nz(d0)) { vst1q_u8(dy + x, v00); changed = 1; }
            continue;
        }
        uint8x16_t vy = rgba8888_16_to_y8(s, cr, cg, cb);
        if (neon_any_nz(veorq_u8(vy, d0))) {
            vst1q_u8(dy + x, vy);
            changed = 1;
        }
    }
#endif
    for (; x < w; x++, s += 4) {
        uint8_t y = (uint8_t)((77u * s[0] + 151u * s[1] + 28u * s[2]) >> 8);
        if (dy[x] != y) { dy[x] = y; changed = 1; }
    }
    return changed;
}

static inline void row565(uint32_t *d, const uint16_t *s, int w)
{
    int x = 0;
#ifdef VD_HAS_NEON
    const uint8x16_t vff = vdupq_n_u8(0xff);
    for (; x + 16 <= w; x += 16) {
        uint16x8_t p0 = vld1q_u16(s + x), p1 = vld1q_u16(s + x + 8);
        uint8x16_t r_hi = vandq_u8(vcombine_u8(vshrn_n_u16(p0, 8),  vshrn_n_u16(p1, 8)),  vdupq_n_u8(0xf8));
        uint8x16_t g_hi = vshlq_n_u8(vcombine_u8(vshrn_n_u16(p0, 5),  vshrn_n_u16(p1, 5)),  2);
        uint8x16_t b_hi = vshlq_n_u8(vcombine_u8(vmovn_u16(p0),       vmovn_u16(p1)),       3);
        uint8x16x4_t out;
        out.val[0] = vsriq_n_u8(r_hi, r_hi, 5);
        out.val[1] = vsriq_n_u8(g_hi, g_hi, 6);
        out.val[2] = vsriq_n_u8(b_hi, b_hi, 5);
        out.val[3] = vff;
        vst4q_u8((uint8_t *)(d + x), out);
    }
#endif
    for (; x < w; x++) {
        uint32_t p = s[x];
        uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
        r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
        d[x] = 0xff000000u | (b << 16) | (g << 8) | r;          /* bytes R, G, B, X */
    }
}

static inline uint32_t blend_px(uint32_t s, uint32_t d)
{
    uint32_t ia = 255 - (s >> 24);
    uint32_t rb = (d & 0x00ff00ffu) * ia + 0x00800080u;
    rb = ((rb + ((rb >> 8) & 0x00ff00ffu)) >> 8) & 0x00ff00ffu;
    uint32_t ag = ((d >> 8) & 0x00ff00ffu) * ia + 0x00800080u;
    ag = (ag + ((ag >> 8) & 0x00ff00ffu)) & 0xff00ff00u;
    return s + (rb | ag);
}

static inline int dim_alpha(const struct ctx *c, const hwc_layer_1_t *l)
{
    return l->planeAlpha < c->dim_cap ? l->planeAlpha : c->dim_cap;
}

static void row_layer(const struct ctx *c, uint32_t *row, const hwc_layer_1_t *l, const hwc_rect_t *sc, int stride, int bpp, int y)
{
    const struct vd_handle *h = (const struct vd_handle *)l->handle;
    int dx = l->displayFrame.left, dy = l->displayFrame.top;
    int w = l->displayFrame.right - dx, sx = 0;
    if (y < dy || y >= l->displayFrame.bottom) return;
    if (dx < 0) { sx = -dx; w += dx; dx = 0; }
    if (dx + w > c->w) w = c->w - dx;
    if (w <= 0) return;
    uint32_t *d = row + dx;
    if (bpp == 0) {
        uint32_t a = (uint32_t)dim_alpha(c, l);
        if (a == 0) return;
        uint32_t px = a << 24;
        for (int x = 0; x < w; x++) d[x] = blend_px(px, d[x]);
        return;
    }
    sx += sc->left;
    size_t sy = (size_t)(y - dy + sc->top);
    if (bpp == 2) { row565(d, (const uint16_t *)((const uint8_t *)(uintptr_t)h->base + (sy * stride + sx) * 2), w); return; }
    const uint32_t *s = (const uint32_t *)((const uint8_t *)(uintptr_t)h->base + (sy * stride + sx) * 4);
    if (l->blending == HWC_BLENDING_NONE) { memcpy(d, s, (size_t)w * 4); return; }
    for (int x = 0; x < w; x++) {
        uint32_t p = s[x];
        uint32_t a = p >> 24;
        if (a == 255) d[x] = p;
        else if (p) d[x] = blend_px(p, d[x]);
    }
}

static int row_class(const struct ctx *c, const hwc_layer_1_t *l, const hwc_rect_t *sc, int stride, int y)
{
    const struct vd_handle *h = (const struct vd_handle *)l->handle;
    int dx = l->displayFrame.left, w = l->displayFrame.right - dx, sx = 0;
    if (dx < 0) { sx = -dx; w += dx; dx = 0; }
    if (dx + w > c->w) w = c->w - dx;
    if (w <= 0) return 0;
    const uint32_t *s = (const uint32_t *)((const uint8_t *)(uintptr_t)h->base +
                        ((size_t)(y - l->displayFrame.top + sc->top) * stride + sx + sc->left) * 4);
    uint32_t an = 0xffffffffu, o = 0;
    int x = 0;
#ifdef VD_HAS_NEON
    uint32x4_t va = vdupq_n_u32(0xffffffffu), vo = vdupq_n_u32(0);
    for (; x + 16 <= w; x += 16) {
        uint32x4_t a0 = vld1q_u32(s + x), a1 = vld1q_u32(s + x + 4), a2 = vld1q_u32(s + x + 8), a3 = vld1q_u32(s + x + 12);
        va = vandq_u32(va, vandq_u32(vandq_u32(a0, a1), vandq_u32(a2, a3)));
        vo = vorrq_u32(vo, vorrq_u32(vorrq_u32(a0, a1), vorrq_u32(a2, a3)));
        if ((x & 255) == 240) {
            uint32x2_t ta = vand_u32(vget_low_u32(va), vget_high_u32(va)), to = vorr_u32(vget_low_u32(vo), vget_high_u32(vo));
            uint32_t aa = vget_lane_u32(ta, 0) & vget_lane_u32(ta, 1), oo = vget_lane_u32(to, 0) | vget_lane_u32(to, 1);
            if (oo && (aa >> 24) != 255) return 1;
        }
    }
    {
        uint32x2_t ta = vand_u32(vget_low_u32(va), vget_high_u32(va)), to = vorr_u32(vget_low_u32(vo), vget_high_u32(vo));
        an = vget_lane_u32(ta, 0) & vget_lane_u32(ta, 1); o = vget_lane_u32(to, 0) | vget_lane_u32(to, 1);
    }
#endif
    for (; x < w; x++) { an &= s[x]; o |= s[x]; }
    if (o == 0) return 0;
    if ((an >> 24) == 255) return 2;
    return 1;
}

static int covers_all(const struct ctx *c, const hwc_layer_1_t *l)
{
    return l->blending == HWC_BLENDING_NONE && l->displayFrame.left <= 0 && l->displayFrame.top <= 0 &&
           l->displayFrame.right >= c->w && l->displayFrame.bottom >= c->h;
}

static int hwc_prepare(hwc_composer_device_1_t *dev, size_t n, hwc_display_contents_1_t **disp)
{
    struct ctx *c = (struct ctx *)dev;
    if (n < 1 || !disp[0]) return 0;
    hwc_display_contents_1_t *list = disp[0];
    time_t t = time(NULL);
    if (t != c->force_checked) {
        c->force_checked = t; c->force_gles = access(KFB_GLES, F_OK) == 0;
        int v = DIM_DEFAULT;
        FILE *f = fopen(KFB_DIM, "re");
        if (f) { if (fscanf(f, "%d", &v) != 1) v = DIM_DEFAULT; fclose(f); }
        c->dim_cap = v < 0 ? -1 : v > 255 ? 255 : v;
        c->fb2_ok = access(KFB_FB2OK, F_OK) == 0;
        c->y8_ok = access(KFB_Y8OK, F_OK) == 0;
        if (c->y8_ok && !c->y8 && !c->y8_failed) y8_map(c);
    }
    size_t nl = list->numHwLayers ? list->numHwLayers - 1 : 0;
    int ok = !c->force_gles && nl > 0 && nl <= 32 && c->fb;
    int st;
    for (size_t i = 0; ok && i < nl; i++) ok = layer_ok(c, &list->hwLayers[i], &st);
    for (size_t i = 0; i < nl; i++) {
        hwc_layer_1_t *l = &list->hwLayers[i];
        if (l->compositionType == HWC_BACKGROUND || l->compositionType == HWC_SIDEBAND) continue;
        if (ok && is_dim(c, l)) continue;
        l->compositionType = ok ? HWC_OVERLAY : HWC_FRAMEBUFFER;
    }
    if (!ok) {
        c->prev_valid = 0;
        c->fb_trimmed = 0;
        if (c->ovl_since_gles && c->fb) {
            if (c->y8_since_gles && c->y8) {
                const uint8_t *sy = y8_page_ptr(c, c->last_page);
                uint32_t *d0 = (uint32_t *)c->fb, *d1 = c->onepage ? NULL : (uint32_t *)(c->fb + c->page_bytes);
                for (size_t i = 0; i < c->y8_page_bytes; i++) {
                    uint32_t v = 0xff000000u | ((uint32_t)sy[i] * 0x010101u);
                    d0[i] = v; if (d1) d1[i] = v;
                }
                c->y8_since_gles = 0;
                c->ovl_since_gles = 0;
                c->n_sync++;
            } else if (!c->onepage) {
                if (c->last_page == 2 && c->fb2) {
                    memcpy(c->fb, c->fb2, c->page_bytes);
                    memcpy(c->fb + c->page_bytes, c->fb2, c->page_bytes);
                } else memcpy(c->fb + (size_t)(c->last_page ^ 1) * c->page_bytes, c->fb + (size_t)(c->last_page & 1) * c->page_bytes, c->page_bytes);
                c->ovl_since_gles = 0;
                c->n_sync++;
            }
        }
    }
    c->overlay = ok;
    return 0;
}

static int compute_dirty_rows(struct ctx *c, const hwc_display_contents_1_t *list,
                              size_t first, size_t top, const int *st, const int *bp,
                              const hwc_rect_t *sc, int whole, int *all_dirty_out)
{
    size_t cnt = top - first;
    struct layer_sig cur[32];
    memset(cur, 0, cnt * sizeof(cur[0]));
    for (size_t k = 0; k < cnt; k++) {
        size_t i = first + k;
        const hwc_layer_1_t *l = &list->hwLayers[i];
        cur[k].handle = (uintptr_t)l->handle;
        cur[k].df = l->displayFrame;
        cur[k].sc = sc[i];
        cur[k].vis_hash = region_sig_hash(&l->visibleRegionScreen);
        cur[k].transform = l->transform;
        cur[k].blending = l->blending;
        cur[k].planeAlpha = l->planeAlpha;
        cur[k].bpp = (uint8_t)bp[i];
        cur[k].stride = (uint16_t)st[i];
    }
    int geom_eq = !whole && c->prev_valid && c->prev_cnt == cnt && c->prev_dim_cap == c->dim_cap;
    int need_handle_eq = (list->flags & HWC_GEOMETRY_CHANGED) != 0;
    for (size_t k = 0; geom_eq && k < cnt; k++) {
        const struct layer_sig *a = &c->prev_sig[k], *b = &cur[k];
        if ((need_handle_eq && a->handle != b->handle) ||
            memcmp(&a->df, &b->df, sizeof(*a) - offsetof(struct layer_sig, df)) != 0)
            geom_eq = 0;
    }
    int same_topo = geom_eq;
    memcpy(c->prev_sig, cur, cnt * sizeof(cur[0]));
    c->prev_cnt = cnt;
    c->prev_dim_cap = c->dim_cap;
    c->prev_valid = 1;

    if (!same_topo || !c->dirty_y) {
        *all_dirty_out = 1;
        return 1;
    }
    memset(c->dirty_y, 0, (size_t)c->h);
    int any = 0, all = 0;
    for (size_t i = first; i < top && !all; i++) {
        if (!st[i] || bp[i] == 0) continue;
        const hwc_layer_1_t *l = &list->hwLayers[i];
        int ly0 = l->displayFrame.top < 0 ? 0 : l->displayFrame.top;
        int ly1 = l->displayFrame.bottom > c->h ? c->h : l->displayFrame.bottom;
        if (ly0 >= ly1) continue;
        if (l->surfaceDamage.numRects == 0 || !l->surfaceDamage.rects) {
            if (ly0 == 0 && ly1 == c->h) { all = 1; any = 1; }
            else { memset(c->dirty_y + ly0, 1, (size_t)(ly1 - ly0)); any = 1; }
        } else {
            for (size_t r = 0; r < l->surfaceDamage.numRects; r++) {
                hwc_rect_t dr = l->surfaceDamage.rects[r];
                if (dr.right <= dr.left || dr.bottom <= dr.top) continue;
                int dy0 = l->displayFrame.top + (dr.top - sc[i].top);
                int dy1 = l->displayFrame.top + (dr.bottom - sc[i].top);
                if (dy0 < ly0) dy0 = ly0;
                if (dy1 > ly1) dy1 = ly1;
                if (dy0 < dy1) {
                    if (dy0 == 0 && dy1 == c->h) { all = 1; any = 1; break; }
                    memset(c->dirty_y + dy0, 1, (size_t)(dy1 - dy0));
                    any = 1;
                }
            }
        }
    }
    *all_dirty_out = all;
    return any;
}

static int hwc_set(hwc_composer_device_1_t *dev, size_t n, hwc_display_contents_1_t **disp)
{
    struct ctx *c = (struct ctx *)dev;
    for (size_t d = 1; d < n; d++) {
        if (!disp[d]) continue;
        if (disp[d]->outbufAcquireFenceFd >= 0) { close(disp[d]->outbufAcquireFenceFd); disp[d]->outbufAcquireFenceFd = -1; }
        disp[d]->retireFenceFd = -1;
        for (size_t i = 0; i < disp[d]->numHwLayers; i++) {
            if (disp[d]->hwLayers[i].acquireFenceFd >= 0) { close(disp[d]->hwLayers[i].acquireFenceFd); disp[d]->hwLayers[i].acquireFenceFd = -1; }
            disp[d]->hwLayers[i].releaseFenceFd = -1;
        }
    }
    if (n < 1 || !disp[0]) return 0;
    hwc_display_contents_1_t *list = disp[0];
    if (list->outbufAcquireFenceFd >= 0) { close(list->outbufAcquireFenceFd); list->outbufAcquireFenceFd = -1; }
    size_t nl = list->numHwLayers ? list->numHwLayers - 1 : 0;
    for (size_t i = 0; i < list->numHwLayers; i++) {
        hwc_layer_1_t *l = &list->hwLayers[i];
        if (l->acquireFenceFd >= 0) { close(l->acquireFenceFd); l->acquireFenceFd = -1; }
        l->releaseFenceFd = -1;
    }
    list->retireFenceFd = -1;
    if (c->overlay && nl > 0) {
        long long t0 = now_ns();
        int use_y8 = c->y8_ok && y8_map(c);
        int page = c->last_page;
        size_t first = 0;
        for (size_t i = nl; i-- > 0;)
            if (covers_all(c, &list->hwLayers[i])) { first = i; break; }
        int st[32], bp[32], ndim = 0, whole = 0;
        hwc_rect_t sc[32];
        size_t top = nl > 32 ? 32 : nl;
        for (size_t i = first; i < top; i++) {
            st[i] = 0; bp[i] = 4; sc[i] = (hwc_rect_t){0, 0, 0, 0};
            if (!layer_ok2(c, &list->hwLayers[i], &st[i], &bp[i], &sc[i])) st[i] = 0;
            else if (bp[i] == 0) ndim++;
        }
        int y0 = -1, y1 = 0, all_dirty = 1;
        const size_t rb = (size_t)c->w * 4;
        if (use_y8) {
            if (!c->y8_inited || c->gles_since_y8) { whole = 1; c->y8_inited = 1; c->gles_since_y8 = 0; }
            if (ndim) {
                page = 2;
                if (page != c->last_page) whole = 1;
                c->n_dim++;
            } else if (page == 2) {
                page = 0; whole = 1;
                c->n_leave2++;
            }
            if (!compute_dirty_rows(c, list, first, top, st, bp, sc, whole, &all_dirty)) {
                c->n_same++;
                c->n_skip_frames++;
                c->n_skip_rows += (unsigned long)c->h;
                fb_trim_pages(c);
                c->last_us = (long)((now_ns() - t0) / 1000);
                c->sum_us += (unsigned long long)c->last_us;
                if (c->last_us > c->max_us) c->max_us = c->last_us;
                return 0;
            }
            uint8_t *dst_y8 = y8_page_ptr(c, page);
            for (int y = 0; y < c->h; y++) {
                if (!all_dirty && !c->dirty_y[y]) { c->n_skip_rows++; continue; }
                int base = -1, nvis = 0;
                unsigned char vis[32];
                for (size_t i = top; i-- > first;) {
                    const hwc_layer_1_t *l = &list->hwLayers[i];
                    if (!st[i] || y < l->displayFrame.top || y >= l->displayFrame.bottom) continue;
                    if (bp[i] == 0) { vis[nvis++] = (unsigned char)i; continue; }
                    int fullw = l->displayFrame.left <= 0 && l->displayFrame.right >= c->w;
                    if (bp[i] != 4 || l->blending == HWC_BLENDING_NONE) {
                        if (fullw) { base = (int)i; break; }
                        vis[nvis++] = (unsigned char)i; continue;
                    }
                    int k = row_class(c, l, &sc[i], st[i], y);
                    if (k == 0) continue;
                    if (k == 2 && fullw) { base = (int)i; break; }
                    vis[nvis++] = (unsigned char)i;
                }
                int chg;
                if (base >= 0 && nvis == 0 && list->hwLayers[base].displayFrame.left <= 0 && (bp[base] == 2 || bp[base] == 4)) {
                    const hwc_layer_1_t *l = &list->hwLayers[base];
                    const struct vd_handle *h = (const struct vd_handle *)l->handle;
                    size_t px_off = (size_t)(y - l->displayFrame.top + sc[base].top) * st[base] +
                                    (size_t)(sc[base].left - l->displayFrame.left);
                    c->n_rows_direct++;
                    if (bp[base] == 2)
                        chg = row565_to_y8_diff(dst_y8 + (size_t)y * c->w, (const uint16_t *)((const uint8_t *)(uintptr_t)h->base + px_off * 2), c->w);
                    else
                        chg = row8888_to_y8_diff(dst_y8 + (size_t)y * c->w, (const uint8_t *)(uintptr_t)h->base + px_off * 4, c->w);
                } else {
                    if (base >= 0) row_layer(c, c->rowbuf, &list->hwLayers[base], &sc[base], st[base], bp[base], y);
                    else memset(c->rowbuf, 0, rb);
                    for (int j = nvis; j-- > 0;)
                        row_layer(c, c->rowbuf, &list->hwLayers[vis[j]], &sc[vis[j]], st[vis[j]], bp[vis[j]], y);
                    c->n_rows_blend++;
                    chg = row8888_to_y8_diff(dst_y8 + (size_t)y * c->w, (const uint8_t *)c->rowbuf, c->w);
                }
                if (chg) {
                    if (y0 < 0) y0 = y;
                    y1 = y + 1;
                }
            }
            if (whole) { notify_cmd(c, 'Y', page, 0, 0); c->ovl_since_gles = 1; c->y8_since_gles = 1; c->n_ovl++; }
            else if (y0 >= 0) { notify_cmd(c, 'Y', page, y0, y1); c->ovl_since_gles = 1; c->y8_since_gles = 1; c->n_ovl++; }
            else c->n_same++;
            fb_trim_pages(c);
        } else {
            uint8_t *dst = page_ptr(c, page);
            if (ndim) {
                if (fb2_map(c)) { page = 2; dst = c->fb2; whole = page != c->last_page; }
                else {
                    const hwc_layer_1_t *t = &list->hwLayers[list->numHwLayers - 1];
                    const struct vd_handle *th = (const struct vd_handle *)t->handle;
                    if (t->compositionType == HWC_FRAMEBUFFER_TARGET && th && th->magic == VD_MAGIC && (th->flags & VD_FLAG_FB)) {
                        page = (int)((size_t)th->offset / c->page_bytes) & 1;
                        dst = page_ptr(c, page); whole = 1;
                    }
                }
                c->n_dim++;
            } else if (page == 2) {
                page = 0; dst = page_ptr(c, 0); whole = 1;
                c->n_leave2++;
            }
            if (!compute_dirty_rows(c, list, first, top, st, bp, sc, whole, &all_dirty)) {
                c->n_same++;
                c->n_skip_frames++;
                c->n_skip_rows += (unsigned long)c->h;
                c->last_us = (long)((now_ns() - t0) / 1000);
                c->sum_us += (unsigned long long)c->last_us;
                if (c->last_us > c->max_us) c->max_us = c->last_us;
                return 0;
            }
            for (int y = 0; y < c->h; y++) {
                if (!all_dirty && !c->dirty_y[y]) { c->n_skip_rows++; continue; }
                const uint8_t *src;
                int base = -1, nvis = 0;
                unsigned char vis[32];
                for (size_t i = top; i-- > first;) {
                    const hwc_layer_1_t *l = &list->hwLayers[i];
                    if (!st[i] || y < l->displayFrame.top || y >= l->displayFrame.bottom) continue;
                    if (bp[i] == 0) { vis[nvis++] = (unsigned char)i; continue; }
                    int fullw = l->displayFrame.left <= 0 && l->displayFrame.right >= c->w;
                    if (bp[i] != 4 || l->blending == HWC_BLENDING_NONE) {
                        if (fullw) { base = (int)i; break; }
                        vis[nvis++] = (unsigned char)i; continue;
                    }
                    int k = row_class(c, l, &sc[i], st[i], y);
                    if (k == 0) continue;
                    if (k == 2 && fullw) { base = (int)i; break; }
                    vis[nvis++] = (unsigned char)i;
                }
                if (base >= 0 && nvis == 0 && bp[base] == 4 && list->hwLayers[base].displayFrame.left <= 0) {
                    const hwc_layer_1_t *l = &list->hwLayers[base];
                    const struct vd_handle *h = (const struct vd_handle *)l->handle;
                    src = (const uint8_t *)(uintptr_t)h->base + ((size_t)(y - l->displayFrame.top + sc[base].top) * st[base] +
                          (size_t)(sc[base].left - l->displayFrame.left)) * 4;
                    c->n_rows_direct++;
                } else {
                    if (base >= 0) row_layer(c, c->rowbuf, &list->hwLayers[base], &sc[base], st[base], bp[base], y);
                    else memset(c->rowbuf, 0, rb);
                    for (int j = nvis; j-- > 0;)
                        row_layer(c, c->rowbuf, &list->hwLayers[vis[j]], &sc[vis[j]], st[vis[j]], bp[vis[j]], y);
                    src = (const uint8_t *)c->rowbuf;
                    c->n_rows_blend++;
                }
                uint8_t *d = dst + (size_t)y * rb;
                if (memcmp(d, src, rb)) {
                    memcpy(d, src, rb);
                    if (y0 < 0) y0 = y;
                    y1 = y + 1;
                }
            }
            if (whole) { notify(c, page, 0, 0); c->ovl_since_gles = 1; c->n_ovl++; }
            else if (y0 >= 0) { notify(c, page, y0, y1); c->ovl_since_gles = 1; c->n_ovl++; }
            else c->n_same++;
        }
        c->last_us = (long)((now_ns() - t0) / 1000);
        c->sum_us += (unsigned long long)c->last_us;
        if (c->last_us > c->max_us) c->max_us = c->last_us;
    } else if (list->numHwLayers) {
        const hwc_layer_1_t *t = &list->hwLayers[list->numHwLayers - 1];
        const struct vd_handle *h = (const struct vd_handle *)t->handle;
        if (t->compositionType == HWC_FRAMEBUFFER_TARGET && h && h->magic == VD_MAGIC && (h->flags & VD_FLAG_FB)) {
            c->prev_valid = 0;
            c->fb_trimmed = 0;
            c->gles_cooldown = 16;
            if (c->y8) c->gles_since_y8 = 1;
            c->y8_since_gles = 0;
            notify(c, (int)((size_t)h->offset / c->page_bytes) & 1, 0, 0);
            c->n_gles++;
        }
    }
    return 0;
}

static void *vsync_thread(void *arg)
{
    struct ctx *c = arg;
#if defined(__ANDROID__) || defined(__GLIBC__)
    pthread_setname_np(pthread_self(), "HwcVsync");
#endif
    long long next = now_ns();
    for (;;) {
        pthread_mutex_lock(&c->lock);
        while (!c->vsync_on) { pthread_cond_wait(&c->cond, &c->lock); next = now_ns(); }
        pthread_mutex_unlock(&c->lock);
        next += c->period_ns;
        long long t = now_ns();
        if (next < t) next = t + c->period_ns - (t - next) % c->period_ns;
        struct timespec ts = { next / 1000000000LL, next % 1000000000LL };
        while (clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &ts, NULL) == EINTR) {}
        if (c->vsync_on && c->procs && c->procs->vsync) c->procs->vsync(c->procs, HWC_DISPLAY_PRIMARY, next);
    }
    return NULL;
}

static int hwc_event_control(hwc_composer_device_1_t *dev, int disp, int event, int enabled)
{
    struct ctx *c = (struct ctx *)dev;
    if (disp != HWC_DISPLAY_PRIMARY || event != HWC_EVENT_VSYNC || (enabled != 0 && enabled != 1)) return -EINVAL;
    pthread_mutex_lock(&c->lock);
    c->vsync_on = enabled != 0;
    pthread_cond_signal(&c->cond);
    pthread_mutex_unlock(&c->lock);
    return 0;
}

static int hwc_set_power_mode(hwc_composer_device_1_t *dev, int disp, int mode)
{
    (void)dev; (void)mode;
    return disp == HWC_DISPLAY_PRIMARY ? 0 : -EINVAL;
}

static int hwc_query(hwc_composer_device_1_t *dev, int what, int *value)
{
    struct ctx *c = (struct ctx *)dev;
    switch (what) {
    case HWC_BACKGROUND_LAYER_SUPPORTED: *value = 0; return 0;
    case HWC_VSYNC_PERIOD: *value = (int)c->period_ns; return 0;
    case HWC_DISPLAY_TYPES_SUPPORTED: *value = HWC_DISPLAY_PRIMARY_BIT; return 0;
    default: return -EINVAL;
    }
}

static void hwc_register_procs(hwc_composer_device_1_t *dev, hwc_procs_t const *procs) { ((struct ctx *)dev)->procs = procs; }

static void hwc_dump(hwc_composer_device_1_t *dev, char *buf, int len)
{
    struct ctx *c = (struct ctx *)dev;
    snprintf(buf, len, "hwc_kindle: %dx%d %ld Hz, frames written here %lu + unchanged %lu (last %ld us, max %ld us, avg %ld us), rows direct %lu blended %lu skipped %lu (idle frames %lu), GLES frames %lu, page syncs %lu, dim frames %lu (cap %d, page 2 %s, left page 2 %lu), force_gles=%d onepage=%d y8=%d fb_trim=%d\n",
             c->w, c->h, 1000000000L / c->period_ns, c->n_ovl, c->n_same, c->last_us, c->max_us,
             (c->n_ovl + c->n_same) ? (long)(c->sum_us / (c->n_ovl + c->n_same)) : 0L, c->n_rows_direct, c->n_rows_blend,
             c->n_skip_rows, c->n_skip_frames,
             c->n_gles, c->n_sync, c->n_dim, c->dim_cap, c->fb2_ok ? (c->fb2_failed ? "failed" : "yes") : "no bridge", c->n_leave2,
             c->force_gles, c->onepage, c->y8_ok && c->y8 != NULL, c->fb_trimmed);
}

static int hwc_get_configs(hwc_composer_device_1_t *dev, int disp, uint32_t *configs, size_t *num)
{
    (void)dev;
    if (disp != HWC_DISPLAY_PRIMARY) return -EINVAL;
    if (*num >= 1 && configs) configs[0] = 0;
    *num = 1;
    return 0;
}

static int hwc_get_attributes(hwc_composer_device_1_t *dev, int disp, uint32_t config, const uint32_t *attr, int32_t *values)
{
    struct ctx *c = (struct ctx *)dev;
    if (disp != HWC_DISPLAY_PRIMARY || config != 0) return -EINVAL;
    for (int i = 0; attr[i] != HWC_DISPLAY_NO_ATTRIBUTE; i++) {
        switch (attr[i]) {
        case HWC_DISPLAY_VSYNC_PERIOD: values[i] = (int32_t)c->period_ns; break;
        case HWC_DISPLAY_WIDTH: values[i] = c->w; break;
        case HWC_DISPLAY_HEIGHT: values[i] = c->h; break;
        case HWC_DISPLAY_DPI_X: values[i] = 300 * 1000; break;
        case HWC_DISPLAY_DPI_Y: values[i] = 300 * 1000; break;
#ifdef HWC_DISPLAY_COLOR_TRANSFORM
        case HWC_DISPLAY_COLOR_TRANSFORM: values[i] = 0; break;
#endif
        default: return -EINVAL;
        }
    }
    return 0;
}

static int hwc_get_active_config(hwc_composer_device_1_t *dev, int disp)
{
    (void)dev;
    return disp == HWC_DISPLAY_PRIMARY ? 0 : -1;
}

static int hwc_set_active_config(hwc_composer_device_1_t *dev, int disp, int index)
{
    (void)dev;
    return (disp == HWC_DISPLAY_PRIMARY && index == 0) ? 0 : -EINVAL;
}

static int hwc_close(hw_device_t *dev)
{
    struct ctx *c = (struct ctx *)dev;
    if (c && c->fb_fd >= 0) { close(c->fb_fd); c->fb_fd = -1; }
    return 0;
}

static int hwc_open(const struct hw_module_t *module, const char *name, struct hw_device_t **device)
{
    if (strcmp(name, HWC_HARDWARE_COMPOSER)) return -EINVAL;
    if (access(KFB_FILE, R_OK | W_OK) != 0 || access(KFB_OFF, F_OK) == 0) {
        ALOGI("not used (%s)", access(KFB_OFF, F_OK) == 0 ? "switched off" : "no virtual framebuffer");
        return -ENODEV;
    }
    struct ctx *c = calloc(1, sizeof(*c));
    if (!c) return -ENOMEM;
    c->fb_fd = -1;
    c->w = 1272; c->h = 1696;
    FILE *f = fopen(KFB_INFO, "r");
    if (f) { if (fscanf(f, "%d %d", &c->w, &c->h) != 2) { c->w = 1272; c->h = 1696; } fclose(f); }
    int hz = 30;
    f = fopen(KFB_HZ, "r");
    if (f) { if (fscanf(f, "%d", &hz) != 1) hz = 30; fclose(f); }
    if (hz < 10 || hz > 60) hz = 30;
    c->period_ns = 1000000000L / hz;
    c->page_bytes = (size_t)c->w * c->h * 4;
    c->y8_page_bytes = (size_t)c->w * c->h;
    int fd = open(KFB_FILE, O_RDWR | O_CLOEXEC);
    if (fd < 0) { free(c); return -errno; }
    struct stat sb;
    if (fstat(fd, &sb) < 0 || (size_t)sb.st_size < c->page_bytes * 2) { close(fd); free(c); return -ENODEV; }
    c->fb = mmap(NULL, c->page_bytes * 2, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (c->fb == MAP_FAILED) { close(fd); free(c); return -ENOMEM; }
    c->fb_fd = fd;
    c->fifo = -1;
    c->page_noted = -1;
    c->onepage = access(KFB_ONEPAGE, F_OK) == 0;
    c->y8_ok = access(KFB_Y8OK, F_OK) == 0;
    if (c->y8_ok) y8_map(c);
    c->dim_cap = DIM_DEFAULT;
    c->rowbuf = malloc((size_t)c->w * 4);
    c->dirty_y = calloc((size_t)c->h, 1);
    if (!c->rowbuf || !c->dirty_y) {
        munmap(c->fb, c->page_bytes * 2);
        close(c->fb_fd);
        free(c->rowbuf);
        free(c->dirty_y);
        free(c);
        return -ENOMEM;
    }
    pthread_mutex_init(&c->lock, NULL);
    pthread_cond_init(&c->cond, NULL);
    c->dev.common.tag = HARDWARE_DEVICE_TAG;
    c->dev.common.version = HWC_DEVICE_API_VERSION_1_5;
    c->dev.common.module = (struct hw_module_t *)module;
    c->dev.common.close = hwc_close;
    c->dev.prepare = hwc_prepare;
    c->dev.set = hwc_set;
    c->dev.eventControl = hwc_event_control;
    c->dev.setPowerMode = hwc_set_power_mode;
    c->dev.query = hwc_query;
    c->dev.registerProcs = hwc_register_procs;
    c->dev.dump = hwc_dump;
    c->dev.getDisplayConfigs = hwc_get_configs;
    c->dev.getDisplayAttributes = hwc_get_attributes;
    c->dev.getActiveConfig = hwc_get_active_config;
    c->dev.setActiveConfig = hwc_set_active_config;
    pthread_create(&c->vthread, NULL, vsync_thread, c);
    ALOGI("virtual e-ink composer 1.5: %dx%d, %d Hz%s", c->w, c->h, hz, c->onepage ? ", one page" : "");
    *device = &c->dev.common;
    return 0;
}

static struct hw_module_methods_t hwc_methods = { .open = hwc_open };

hwc_module_t HAL_MODULE_INFO_SYM = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .module_api_version = HWC_MODULE_API_VERSION_0_1,
        .hal_api_version = HARDWARE_HAL_API_VERSION,
        .id = HWC_HARDWARE_MODULE_ID,
        .name = "VeraDroid e-ink composer",
        .author = "VeraDroid",
        .methods = &hwc_methods,
    }
};
