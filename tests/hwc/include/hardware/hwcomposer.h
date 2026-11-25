/* host stand-in for the parts of <hardware/hwcomposer.h> (HWC 1.5) hwc_kindle.c uses (tests/hwc-test.sh) */
#pragma once
#include <stddef.h>
#include "hardware.h"
#define HWC_HARDWARE_MODULE_ID "hwcomposer"
#define HWC_HARDWARE_COMPOSER "composer"
#define HWC_MODULE_API_VERSION_0_1 0x0001
#define HWC_DEVICE_API_VERSION_1_1 0x01010000
#define HWC_DEVICE_API_VERSION_1_5 0x01050000
#define HWC_GEOMETRY_CHANGED 0x00000001
enum { HWC_FRAMEBUFFER = 0, HWC_OVERLAY = 1, HWC_BACKGROUND = 2, HWC_FRAMEBUFFER_TARGET = 3, HWC_SIDEBAND = 4, HWC_CURSOR_OVERLAY = 5 };
enum { HWC_SKIP_LAYER = 1 };
enum { HWC_BLENDING_NONE = 0x0100, HWC_BLENDING_PREMULT = 0x0105, HWC_BLENDING_COVERAGE = 0x0405 };
enum { HWC_DISPLAY_PRIMARY = 0, HWC_DISPLAY_PRIMARY_BIT = 1, HWC_EVENT_VSYNC = 0 };
enum { HWC_BACKGROUND_LAYER_SUPPORTED = 0, HWC_VSYNC_PERIOD = 1, HWC_DISPLAY_TYPES_SUPPORTED = 2 };
enum { HWC_DISPLAY_NO_ATTRIBUTE = 0, HWC_DISPLAY_VSYNC_PERIOD = 1, HWC_DISPLAY_WIDTH = 2, HWC_DISPLAY_HEIGHT = 3,
       HWC_DISPLAY_DPI_X = 4, HWC_DISPLAY_DPI_Y = 5, HWC_DISPLAY_COLOR_TRANSFORM = 6 };
typedef struct hwc_rect { int left, top, right, bottom; } hwc_rect_t;
typedef struct hwc_frect { float left, top, right, bottom; } hwc_frect_t;
typedef struct hwc_region { size_t numRects; hwc_rect_t const *rects; } hwc_region_t;
typedef struct hwc_layer_1 {
    int32_t compositionType; uint32_t hints, flags;
    buffer_handle_t handle;
    uint32_t transform; int32_t blending;
    union { hwc_rect_t sourceCropi; hwc_frect_t sourceCropf; };
    hwc_rect_t displayFrame;
    hwc_region_t visibleRegionScreen;
    int acquireFenceFd, releaseFenceFd;
    uint8_t planeAlpha;
    hwc_region_t surfaceDamage;
} hwc_layer_1_t;
typedef struct hwc_display_contents_1 {
    int retireFenceFd;
    buffer_handle_t outbuf;
    int outbufAcquireFenceFd;
    uint32_t flags;
    size_t numHwLayers;
    hwc_layer_1_t hwLayers[0];
} hwc_display_contents_1_t;
typedef struct hwc_procs { void (*invalidate)(const struct hwc_procs *); void (*vsync)(const struct hwc_procs *, int, int64_t); } hwc_procs_t;
typedef struct hwc_module { struct hw_module_t common; } hwc_module_t;
typedef struct hwc_composer_device_1 {
    struct hw_device_t common;
    int (*prepare)(struct hwc_composer_device_1 *, size_t, hwc_display_contents_1_t **);
    int (*set)(struct hwc_composer_device_1 *, size_t, hwc_display_contents_1_t **);
    int (*eventControl)(struct hwc_composer_device_1 *, int, int, int);
    union {
        int (*blank)(struct hwc_composer_device_1 *, int, int);
        int (*setPowerMode)(struct hwc_composer_device_1 *, int, int);
    };
    int (*query)(struct hwc_composer_device_1 *, int, int *);
    void (*registerProcs)(struct hwc_composer_device_1 *, hwc_procs_t const *);
    void (*dump)(struct hwc_composer_device_1 *, char *, int);
    int (*getDisplayConfigs)(struct hwc_composer_device_1 *, int, uint32_t *, size_t *);
    int (*getDisplayAttributes)(struct hwc_composer_device_1 *, int, uint32_t, const uint32_t *, int32_t *);
    int (*getActiveConfig)(struct hwc_composer_device_1 *, int);
    int (*setActiveConfig)(struct hwc_composer_device_1 *, int, int);
    int (*setCursorPositionAsync)(struct hwc_composer_device_1 *, int, int, int);
} hwc_composer_device_1_t;
