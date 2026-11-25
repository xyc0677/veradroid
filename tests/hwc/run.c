/* tests/hwc-test.sh driver: HWC 1.5 surfaceDamage skipping, /dev/kfb/fb page trimming, y8 & page 2 */
#include "hwc_kindle.c"
#include <assert.h>

#define TW 64
#define TH 48
static int fails;
#define CHECK(c, ...) do { if (!(c)) { fails++; printf("FAIL %s:%d: ", __FILE__, __LINE__); printf(__VA_ARGS__); printf("\n"); } } while (0)

static struct vd_handle app_h, dlg_h, fbt_h;
static uint32_t *app_px, *dlg_px;
static int rfd;

static void mkbuf(struct vd_handle *h, uint32_t **px, int w, int hh, uint32_t colour)
{
    size_t sz = page_round((size_t)((hh + 1) & ~1) * ((w + 1) & ~1) * 4 + 4);
    *px = calloc(1, sz);
    for (int i = 0; i < ((w + 1) & ~1) * hh; i++) (*px)[i] = colour;
    memset(h, 0, sizeof(*h));
    h->nh.numFds = 1; h->magic = VD_MAGIC; h->size = (int)sz; h->base = (uint64_t)(uintptr_t)*px;
}

static void layer(hwc_layer_1_t *l, const struct vd_handle *h, int x, int y, int w, int hh, int blend)
{
    memset(l, 0, sizeof(*l));
    l->compositionType = HWC_FRAMEBUFFER; l->handle = (buffer_handle_t)h; l->blending = blend; l->planeAlpha = 255;
    l->sourceCropf = (hwc_frect_t){0.0f, 0.0f, (float)w, (float)hh};
    l->displayFrame = (hwc_rect_t){x, y, x + w, y + hh};
    l->acquireFenceFd = l->releaseFenceFd = -1;
}

static char said[256];
static const char *frame_dmg(hwc_composer_device_1_t *dev, int dialog, int fbpage, const hwc_region_t *app_dmg, int *overlay)
{
    static union { hwc_display_contents_1_t d; char b[sizeof(hwc_display_contents_1_t) + 5 * sizeof(hwc_layer_1_t)]; } u;
    hwc_display_contents_1_t *list = &u.d;
    memset(&u, 0, sizeof(u));
    list->outbufAcquireFenceFd = -1;
    size_t n = 0;
    layer(&list->hwLayers[n], &app_h, 0, 0, TW, TH, HWC_BLENDING_NONE);
    if (app_dmg) list->hwLayers[n].surfaceDamage = *app_dmg;
    n++;
    if (dialog) {
        hwc_layer_1_t *d = &list->hwLayers[n++];
        memset(d, 0, sizeof(*d));
        d->compositionType = HWC_FRAMEBUFFER; d->flags = HWC_SKIP_LAYER; d->blending = HWC_BLENDING_PREMULT; d->planeAlpha = 255;
        d->displayFrame = (hwc_rect_t){-TW / 2, -TH / 2, TW + TW / 2, TH + TH / 2};
        d->acquireFenceFd = d->releaseFenceFd = -1;
        layer(&list->hwLayers[n++], &dlg_h, 8, 8, TW - 16, TH - 16, HWC_BLENDING_PREMULT);
    }
    hwc_layer_1_t *t = &list->hwLayers[n++];
    memset(t, 0, sizeof(*t));
    fbt_h.offset = fbpage * TW * TH * 4;
    t->compositionType = HWC_FRAMEBUFFER_TARGET; t->handle = (buffer_handle_t)&fbt_h; t->blending = HWC_BLENDING_PREMULT;
    t->acquireFenceFd = t->releaseFenceFd = -1;
    list->numHwLayers = n;
    hwc_display_contents_1_t *disp[1] = { list };
    dev->prepare(dev, 1, disp);
    *overlay = list->hwLayers[0].compositionType == HWC_OVERLAY;
    dev->set(dev, 1, disp);
    ssize_t r = read(rfd, said, sizeof(said) - 1);
    said[r > 0 ? r : 0] = 0;
    char *nl = strchr(said, '\n'); if (nl) *nl = 0;
    return said;
}

static const char *frame(hwc_composer_device_1_t *dev, int dialog, int fbpage, int *overlay)
{
    return frame_dmg(dev, dialog, fbpage, NULL, overlay);
}

static void check_565_exact(void)
{
    uint16_t in[256];
    uint8_t dy[256];
    for (int base = 0; base < 65536; base += 256) {
        for (int i = 0; i < 256; i++) { in[i] = (uint16_t)(base + i); dy[i] = 0x5au; }
        row565_to_y8_diff(dy, in, 256);
        for (int i = 0; i < 256; i++) {
            uint32_t p = (uint32_t)(base + i);
            uint32_t r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
            r = (r << 3) | (r >> 2); g = (g << 2) | (g >> 4); b = (b << 3) | (b >> 2);
            uint8_t want = (uint8_t)((77u * r + 151u * g + 28u * b) >> 8);
            if (dy[i] != want) { CHECK(0, "565 exact mismatch at 0x%04x: got %u want %u", base + i, dy[i], want); return; }
        }
        CHECK(row565_to_y8_diff(dy, in, 256) == 0, "565 unchanged diff returned non-zero at base %d", base);
    }
}

int main(int argc, char **argv)
{
    const char *dir = argv[1];
    int y8 = !strcmp(argv[2], "y8");
    int ok = y8 || !strcmp(argv[2], "ok");
    check_565_exact();
    assert(!strncmp(KFB_DIR, dir, strlen(dir)));
    rfd = open(KFB_FIFO, O_RDONLY | O_NONBLOCK);
    assert(rfd >= 0);
    mkbuf(&app_h, &app_px, TW, TH, 0xff102030u);
    mkbuf(&dlg_h, &dlg_px, TW - 16, TH - 16, 0xffe0e0e0u);
    memset(&fbt_h, 0, sizeof(fbt_h)); fbt_h.nh.numFds = 1; fbt_h.magic = VD_MAGIC; fbt_h.flags = VD_FLAG_FB;
    hw_device_t *hd = NULL;
    int rc = HAL_MODULE_INFO_SYM.common.methods->open(&HAL_MODULE_INFO_SYM.common, HWC_HARDWARE_COMPOSER, &hd);
    CHECK(rc == 0 && hd, "open: %d", rc);
    if (!hd) return 1;
    CHECK(hd->version == HWC_DEVICE_API_VERSION_1_5, "expected HWC 1.5, got %#x", hd->version);
    hwc_composer_device_1_t *dev = (hwc_composer_device_1_t *)hd;
    struct ctx *c = (struct ctx *)dev;
    int ovl;
    char cmd_ch = y8 ? 'Y' : 'P';
    const char *s = frame(dev, 0, 0, &ovl);
    CHECK(ovl && s[0] == cmd_ch && s[2] == '0', "app frame: overlay %d, said '%s'", ovl, s);
    if (y8) {
        uint8_t want_app = (uint8_t)((77u * 0x30 + 151u * 0x20 + 28u * 0x10) >> 8);
        CHECK(c->y8 && c->y8[0] == want_app, "y8 page 0 holds the app luma (%u vs %u)", c->y8 ? c->y8[0] : 255, want_app);
        struct stat fsb;
        CHECK(fstat(c->fb_fd, &fsb) == 0 && fsb.st_blocks <= 4 && (size_t)fsb.st_size == c->page_bytes * 2,
              "y8 mode did not punch out /dev/kfb/fb backing blocks (blocks=%lld size=%lld)",
              (long long)fsb.st_blocks, (long long)fsb.st_size);
    } else {
        CHECK(((uint32_t *)c->fb)[0] == 0xff102030u, "page 0 holds the app");
    }
    hwc_rect_t empty_r = {0, 0, 0, 0};
    hwc_region_t empty_dmg = {1, &empty_r};
    unsigned long rd_before = c->n_rows_direct;
    s = frame_dmg(dev, 0, 0, &empty_dmg, &ovl);
    CHECK(ovl && !s[0] && c->n_rows_direct == rd_before && c->n_skip_frames == 1,
          "empty damage should skip frame (said='%s', direct=%lu->%lu, skip_frames=%lu)",
          s, rd_before, c->n_rows_direct, c->n_skip_frames);

    for (int i = 4 * TW; i < 10 * TW; i++) app_px[i] = 0xff223344u;
    hwc_rect_t part_r = {0, 4, TW, 10};
    hwc_region_t part_dmg = {1, &part_r};
    rd_before = c->n_rows_direct;
    s = frame_dmg(dev, 0, 0, &part_dmg, &ovl);
    CHECK(ovl && !strcmp(s, y8 ? "Y 0 4 10" : "P 0 4 10") && (c->n_rows_direct - rd_before) == 6,
          "partial damage got said='%s', scanned=%lu (want 6)", s, c->n_rows_direct - rd_before);

    for (int i = 0; i < TW * TH; i++) app_px[i] = 0xff102030u;
    (void)frame(dev, 0, 0, &ovl);

    for (int i = 0; i < 3; i++) {
        s = frame(dev, 1, i & 1, &ovl);
        if (ok) CHECK((ovl && s[0] == cmd_ch && s[2] == '2') || (ovl && !s[0]), "dialog frame %d: overlay %d, said '%s' (want %c 2)", i, ovl, s, cmd_ch);
        else CHECK(!ovl && strncmp(s, "P 2", 3), "dialog frame %d without fb2.ok: overlay %d, said '%s' (want GLES, never P 2)", i, ovl, s);
    }
    if (y8) {
        const uint8_t *p2 = c->y8 + 2 * c->y8_page_bytes;
        uint8_t mid = p2[(TH / 2) * TW + TW / 2], edge = p2[1];
        uint8_t want_app = (uint8_t)((77u * 0x30 + 151u * 0x20 + 28u * 0x10) >> 8);
        CHECK(mid == 0xe0u, "y8 page 2: dialog in the middle (%u)", mid);
        CHECK(edge < want_app, "y8 page 2: app under the veil is darker (%u < %u)", edge, want_app);
    } else if (ok) {
        uint32_t mid = ((uint32_t *)c->fb2)[(TH / 2) * TW + TW / 2], edge = ((uint32_t *)c->fb2)[1];
        CHECK(mid == 0xffe0e0e0u, "page 2: dialog in the middle (%08x)", mid);
        CHECK(edge != 0xff102030u && (edge & 0xff) < 0x30, "page 2: app under the veil is darker (%08x)", edge);
    }
    for (int i = 0; i < TW * TH; i++) app_px[i] = 0xff405060u;
    s = frame(dev, 0, 0, &ovl);
    CHECK(ovl && s[0] == cmd_ch && (s[2] == '0' || s[2] == '1'), "frame after the dialog: overlay %d, said '%s' (want %c 0 / %c 1)", ovl, s, cmd_ch, cmd_ch);
    int pg = s[0] ? s[2] - '0' : 0;
    if (y8) {
        uint8_t want2 = (uint8_t)((77u * 0x60 + 151u * 0x50 + 28u * 0x40) >> 8);
        CHECK(c->y8[(size_t)(pg & 1) * c->y8_page_bytes + 5] == want2, "y8 page %d holds new app luma", pg);
    } else {
        CHECK(((uint32_t *)(c->fb + (size_t)(pg & 1) * c->page_bytes))[5] == 0xff405060u, "page %d holds the new app picture", pg);
    }
    for (int i = 0; i < TW * TH; i++) app_px[i] = 0xff708090u;
    s = frame(dev, 0, 0, &ovl);
    CHECK(ovl && s[0] == cmd_ch && s[2] != '2', "next frame: said '%s' (want not page 2)", s);
    char d[1024]; dev->dump(dev, d, sizeof(d)); printf("%s", d);
    printf("%s: %s\n", ok ? "fb2.ok" : "no fb2.ok", fails ? "FAILED" : "ok");
    return fails != 0;
}
