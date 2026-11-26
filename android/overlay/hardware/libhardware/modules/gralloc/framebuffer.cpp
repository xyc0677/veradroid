/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <sys/mman.h>

#include <dlfcn.h>

#include <cutils/ashmem.h>
#include <cutils/log.h>

#include <hardware/hardware.h>
#include <hardware/gralloc.h>

#include <fcntl.h>
#include <errno.h>
#include <sys/ioctl.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>

#include <cutils/log.h>
#include <cutils/atomic.h>

#ifdef __ANDROID__
#include <linux/fb.h>
#endif

#include "gralloc_priv.h"
#include "gr.h"

/*****************************************************************************/

// Set TARGET_USE_PAN_DISPLAY to true at compile time if the
// board uses FBIOPAN_DISPLAY to setup page flipping, otherwise
// default ioctl to do page-flipping is FBIOPUT_VSCREENINFO.
#ifndef USE_PAN_DISPLAY
#define USE_PAN_DISPLAY 0
#endif

// numbers of buffers for page flipping
#define NUM_BUFFERS 2


enum {
    PAGE_FLIP = 0x00000001,
    LOCKED = 0x00000002
};

struct fb_context_t {
    framebuffer_device_t  device;
};

/*
 * VeraDroid: the Kindle's real framebuffer is 8-bit gray behind an e-ink controller, so Android never opens it.
 * The host program creates /dev/kfb/fb, a regular file of 2 pages of RGBX8888, and a FIFO /dev/kfb/epd.fifo.
 * We draw into the file and tell the host bridge (veradroid-epd) which page to put on the e-ink panel.
 */
#define KFB_FILE "/dev/kfb/fb"
#define KFB_INFO "/dev/kfb/info"
#define KFB_FIFO "/dev/kfb/epd.fifo"
/* framework project (docs/framework-project.md): when the host creates this file, both framebuffer "pages" handed to
   SurfaceFlinger are page 0 of /dev/kfb/fb (gralloc.cpp does not step to the next page, see KFB_FLAG_ONEPAGE). SurfaceFlinger
   then draws straight into the picture the bridge reads and page 1 is never touched (tmpfs: no memory). */
#define KFB_ONEPAGE "/dev/kfb/onepage"
#define KFB_FLAG_ONEPAGE 0x00010000   /* private_module_t::flags, read by gralloc.cpp (android/fw-patches.py) */
static bool g_kfb = false;
static int g_kfb_fifo = -1;

static void kfb_notify(unsigned page)
{
    if (g_kfb_fifo < 0) {
        g_kfb_fifo = open(KFB_FIFO, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
        if (g_kfb_fifo < 0) return;     // the bridge is not running (yet): drop the update
    }
    char msg[32];
    int n = snprintf(msg, sizeof(msg), "P %u\n", page);
    if (write(g_kfb_fifo, msg, n) < 0 && errno != EAGAIN) {
        close(g_kfb_fifo);
        g_kfb_fifo = -1;
    }
}

/*****************************************************************************/

static int fb_setSwapInterval(struct framebuffer_device_t* dev,
            int interval)
{
    fb_context_t* ctx = (fb_context_t*)dev;
    if (interval < dev->minSwapInterval || interval > dev->maxSwapInterval)
        return -EINVAL;
    // FIXME: implement fb_setSwapInterval
    return 0;
}

static int fb_setUpdateRect(struct framebuffer_device_t* dev,
        int l, int t, int w, int h)
{
    if (((w|h) <= 0) || ((l|t)<0))
        return -EINVAL;
        
    fb_context_t* ctx = (fb_context_t*)dev;
    private_module_t* m = reinterpret_cast<private_module_t*>(
            dev->common.module);
    m->info.reserved[0] = 0x54445055; // "UPDT";
    m->info.reserved[1] = (uint16_t)l | ((uint32_t)t << 16);
    m->info.reserved[2] = (uint16_t)(l+w) | ((uint32_t)(t+h) << 16);
    return 0;
}

static int fb_post(struct framebuffer_device_t* dev, buffer_handle_t buffer)
{
    if (private_handle_t::validate(buffer) < 0)
        return -EINVAL;

    fb_context_t* ctx = (fb_context_t*)dev;

    private_handle_t const* hnd = reinterpret_cast<private_handle_t const*>(buffer);
    private_module_t* m = reinterpret_cast<private_module_t*>(
            dev->common.module);

    if (hnd->flags & private_handle_t::PRIV_FLAGS_FRAMEBUFFER) {
        const size_t offset = hnd->base - m->framebuffer->base;
        m->info.activate = FB_ACTIVATE_VBL;
        m->info.yoffset = offset / m->finfo.line_length;
        if (g_kfb) {
            kfb_notify(m->info.yoffset / m->info.yres);
        } else if (ioctl(m->framebuffer->fd, FBIOPUT_VSCREENINFO, &m->info) == -1) {
            ALOGE("FBIOPUT_VSCREENINFO failed");
            m->base.unlock(&m->base, buffer); 
            return -errno;
        }
        m->currentBuffer = buffer;
        
    } else {
        // If we can't do the page_flip, just copy the buffer to the front 
        // FIXME: use copybit HAL instead of memcpy
        
        void* fb_vaddr;
        void* buffer_vaddr;
        
        m->base.lock(&m->base, m->framebuffer, 
                GRALLOC_USAGE_SW_WRITE_RARELY, 
                0, 0, m->info.xres, m->info.yres,
                &fb_vaddr);

        m->base.lock(&m->base, buffer, 
                GRALLOC_USAGE_SW_READ_RARELY, 
                0, 0, m->info.xres, m->info.yres,
                &buffer_vaddr);

        memcpy(fb_vaddr, buffer_vaddr, m->finfo.line_length * m->info.yres);
        
        m->base.unlock(&m->base, buffer); 
        m->base.unlock(&m->base, m->framebuffer); 
        if (g_kfb) kfb_notify(0);
    }
    
    return 0;
}

/*****************************************************************************/

int mapFrameBufferLocked(struct private_module_t* module)
{
    // already initialized...
    if (module->framebuffer) {
        return 0;
    }
        
    // VeraDroid: use the host-provided virtual framebuffer when it exists
    if (access(KFB_FILE, R_OK | W_OK) == 0) {
        int w = 1272, h = 1696;
        FILE* fi = fopen(KFB_INFO, "r");
        if (fi) { if (fscanf(fi, "%d %d", &w, &h) != 2) { w = 1272; h = 1696; } fclose(fi); }
        int vfd = open(KFB_FILE, O_RDWR | O_CLOEXEC, 0);
        if (vfd < 0)
            return -errno;
        struct fb_fix_screeninfo vfinfo;
        struct fb_var_screeninfo vinfo;
        memset(&vfinfo, 0, sizeof(vfinfo));
        memset(&vinfo, 0, sizeof(vinfo));
        strlcpy((char*)vfinfo.id, "kindle-epd", sizeof(vfinfo.id));
        vinfo.xres = vinfo.xres_virtual = w;
        vinfo.yres = h;
        vinfo.yres_virtual = h * NUM_BUFFERS;
        vinfo.bits_per_pixel = 32;
        vinfo.red.offset = 0;   vinfo.red.length = 8;      // bytes R,G,B,X: HAL_PIXEL_FORMAT_RGBX_8888
        vinfo.green.offset = 8; vinfo.green.length = 8;
        vinfo.blue.offset = 16; vinfo.blue.length = 8;
        vinfo.transp.offset = 24; vinfo.transp.length = 0;
        vinfo.width = (uint32_t)(w * 25.4f / 300.0f + 0.5f);   // 300 dpi panel
        vinfo.height = (uint32_t)(h * 25.4f / 300.0f + 0.5f);
        // 30 fps: the panel itself is slower, but input is delivered per frame, so 10 fps made every touch wait up to 100 ms
        vinfo.pixclock = (uint32_t)(100000000000ULL / ((uint64_t)w * h));
        vfinfo.line_length = w * 4;
        vfinfo.smem_len = vfinfo.line_length * vinfo.yres_virtual;
        size_t need = vfinfo.smem_len;
        if (ftruncate(vfd, need) < 0 && errno != EPERM && errno != EINVAL)
            ALOGW("ftruncate(%s) failed: %s", KFB_FILE, strerror(errno));

        const bool onepage = access(KFB_ONEPAGE, F_OK) == 0;
        module->flags = PAGE_FLIP | (onepage ? KFB_FLAG_ONEPAGE : 0);
        module->info = vinfo;
        module->finfo = vfinfo;
        module->xdpi = 300.0f;
        module->ydpi = 300.0f;
        module->fps = 30.0f;
        size_t vbSize = roundUpToPageSize(need);
        module->framebuffer = new private_handle_t(dup(vfd), vbSize, 0);
        module->numBuffers = NUM_BUFFERS;
        module->bufferMask = 0;
        void* vaddr = mmap(0, vbSize, PROT_READ|PROT_WRITE, MAP_SHARED, vfd, 0);
        int saved_errno = errno;
        close(vfd);
        if (vaddr == MAP_FAILED) {
            ALOGE("Error mapping %s (%s)", KFB_FILE, strerror(saved_errno));
            close(module->framebuffer->fd);
            delete module->framebuffer;
            module->framebuffer = NULL;
            return -saved_errno;
        }
        module->framebuffer->base = intptr_t(vaddr);
        // white; with one page only page 0 is touched (and ever used)
        memset(vaddr, 0xff, onepage ? (size_t)vfinfo.line_length * h : vbSize);
        g_kfb = true;
        ALOGI("using virtual e-ink framebuffer %s: %dx%d, %d pages%s", KFB_FILE, w, h, NUM_BUFFERS, onepage ? " (one page: both are page 0)" : "");
        return 0;
    }

    char const * const device_template[] = {
            "/dev/graphics/fb%u",
            "/dev/fb%u",
            0 };

    int fd = -1;
    int i=0;
    char name[64];

    while ((fd==-1) && device_template[i]) {
        snprintf(name, 64, device_template[i], 0);
        fd = open(name, O_RDWR, 0);
        i++;
    }
    if (fd < 0)
        return -errno;

    struct fb_fix_screeninfo finfo;
    if (ioctl(fd, FBIOGET_FSCREENINFO, &finfo) == -1)
        return -errno;

    struct fb_var_screeninfo info;
    if (ioctl(fd, FBIOGET_VSCREENINFO, &info) == -1)
        return -errno;

    info.reserved[0] = 0;
    info.reserved[1] = 0;
    info.reserved[2] = 0;
    info.xoffset = 0;
    info.yoffset = 0;
    info.activate = FB_ACTIVATE_NOW;

    /*
     * Request NUM_BUFFERS screens (at lest 2 for page flipping)
     */
    info.yres_virtual = info.yres * NUM_BUFFERS;


    uint32_t flags = PAGE_FLIP;
#if USE_PAN_DISPLAY
    if (ioctl(fd, FBIOPAN_DISPLAY, &info) == -1) {
        ALOGW("FBIOPAN_DISPLAY failed, page flipping not supported");
#else
    if (ioctl(fd, FBIOPUT_VSCREENINFO, &info) == -1) {
        ALOGW("FBIOPUT_VSCREENINFO failed, page flipping not supported");
#endif
        info.yres_virtual = info.yres;
        flags &= ~PAGE_FLIP;
    }

    if (info.yres_virtual < info.yres * 2) {
        // we need at least 2 for page-flipping
        info.yres_virtual = info.yres;
        flags &= ~PAGE_FLIP;
        ALOGW("page flipping not supported (yres_virtual=%d, requested=%d)",
                info.yres_virtual, info.yres*2);
    }

    if (ioctl(fd, FBIOGET_VSCREENINFO, &info) == -1)
        return -errno;

    uint64_t  refreshQuotient =
    (
            uint64_t( info.upper_margin + info.lower_margin + info.yres )
            * ( info.left_margin  + info.right_margin + info.xres )
            * info.pixclock
    );

    /* Beware, info.pixclock might be 0 under emulation, so avoid a
     * division-by-0 here (SIGFPE on ARM) */
    int refreshRate = refreshQuotient > 0 ? (int)(1000000000000000LLU / refreshQuotient) : 0;

    if (refreshRate == 0) {
        // bleagh, bad info from the driver
        refreshRate = 60*1000;  // 60 Hz
    }

    if (int(info.width) <= 0 || int(info.height) <= 0) {
        // the driver doesn't return that information
        // default to 160 dpi
        info.width  = ((info.xres * 25.4f)/160.0f + 0.5f);
        info.height = ((info.yres * 25.4f)/160.0f + 0.5f);
    }

    float xdpi = (info.xres * 25.4f) / info.width;
    float ydpi = (info.yres * 25.4f) / info.height;
    float fps  = refreshRate / 1000.0f;

    ALOGI(   "using (fd=%d)\n"
            "id           = %s\n"
            "xres         = %d px\n"
            "yres         = %d px\n"
            "xres_virtual = %d px\n"
            "yres_virtual = %d px\n"
            "bpp          = %d\n"
            "r            = %2u:%u\n"
            "g            = %2u:%u\n"
            "b            = %2u:%u\n",
            fd,
            finfo.id,
            info.xres,
            info.yres,
            info.xres_virtual,
            info.yres_virtual,
            info.bits_per_pixel,
            info.red.offset, info.red.length,
            info.green.offset, info.green.length,
            info.blue.offset, info.blue.length
    );

    ALOGI(   "width        = %d mm (%f dpi)\n"
            "height       = %d mm (%f dpi)\n"
            "refresh rate = %.2f Hz\n",
            info.width,  xdpi,
            info.height, ydpi,
            fps
    );


    if (ioctl(fd, FBIOGET_FSCREENINFO, &finfo) == -1)
        return -errno;

    if (finfo.smem_len <= 0)
        return -errno;


    module->flags = flags;
    module->info = info;
    module->finfo = finfo;
    module->xdpi = xdpi;
    module->ydpi = ydpi;
    module->fps = fps;

    /*
     * map the framebuffer
     */

    int err;
    size_t fbSize = roundUpToPageSize(finfo.line_length * info.yres_virtual);
    module->framebuffer = new private_handle_t(dup(fd), fbSize, 0);

    module->numBuffers = info.yres_virtual / info.yres;
    module->bufferMask = 0;

    void* vaddr = mmap(0, fbSize, PROT_READ|PROT_WRITE, MAP_SHARED, fd, 0);
    if (vaddr == MAP_FAILED) {
        ALOGE("Error mapping the framebuffer (%s)", strerror(errno));
        return -errno;
    }
    module->framebuffer->base = intptr_t(vaddr);
    memset(vaddr, 0, fbSize);
    return 0;
}

static int mapFrameBuffer(struct private_module_t* module)
{
    pthread_mutex_lock(&module->lock);
    int err = mapFrameBufferLocked(module);
    pthread_mutex_unlock(&module->lock);
    return err;
}

/*****************************************************************************/

static int fb_close(struct hw_device_t *dev)
{
    fb_context_t* ctx = (fb_context_t*)dev;
    if (ctx) {
        free(ctx);
    }
    return 0;
}

int fb_device_open(hw_module_t const* module, const char* name,
        hw_device_t** device)
{
    int status = -EINVAL;
    if (!strcmp(name, GRALLOC_HARDWARE_FB0)) {
        /* initialize our state here */
        fb_context_t *dev = (fb_context_t*)malloc(sizeof(*dev));
        memset(dev, 0, sizeof(*dev));

        /* initialize the procs */
        dev->device.common.tag = HARDWARE_DEVICE_TAG;
        dev->device.common.version = 0;
        dev->device.common.module = const_cast<hw_module_t*>(module);
        dev->device.common.close = fb_close;
        dev->device.setSwapInterval = fb_setSwapInterval;
        dev->device.post            = fb_post;
        dev->device.setUpdateRect = 0;

        private_module_t* m = (private_module_t*)module;
        status = mapFrameBuffer(m);
        if (status >= 0) {
            int stride = m->finfo.line_length / (m->info.bits_per_pixel >> 3);
            int format = (m->info.bits_per_pixel == 32)
                         ? (m->info.red.offset ? HAL_PIXEL_FORMAT_BGRA_8888 : HAL_PIXEL_FORMAT_RGBX_8888)
                         : HAL_PIXEL_FORMAT_RGB_565;
            const_cast<uint32_t&>(dev->device.flags) = 0;
            const_cast<uint32_t&>(dev->device.width) = m->info.xres;
            const_cast<uint32_t&>(dev->device.height) = m->info.yres;
            const_cast<int&>(dev->device.stride) = stride;
            const_cast<int&>(dev->device.format) = format;
            const_cast<float&>(dev->device.xdpi) = m->xdpi;
            const_cast<float&>(dev->device.ydpi) = m->ydpi;
            const_cast<float&>(dev->device.fps) = m->fps;
            const_cast<int&>(dev->device.minSwapInterval) = 1;
            const_cast<int&>(dev->device.maxSwapInterval) = 1;
            *device = &dev->device.common;
        }
    }
    return status;
}
