/*
 * VeraDroid: a "null" OpenGL ES 2.0 for processes that insist on a GLES2 context but never show what it draws.
 *
 * Why: there is no GPU. SurfaceFlinger composes with libagl (GLES 1.x software) and every app draws its views with
 * the CPU. Chromium WebView (52) still needs a working GLES2 context in its in-process GPU thread before it will
 * create the synchronous compositor that serves View.onDraw(): without one it falls back to a compositor that only
 * the Chrome browser can display and the page body stays blank (only the app's own toolbar shows). On a software
 * canvas WebView draws the page itself (DemandDrawSw / resourceless software draw), so the GL context is only
 * bookkeeping: it is created, queried, given shaders and textures, and nothing it renders is ever looked at.
 *
 * So this library answers like a minimal GLES 2.0 driver (ids, limits, compile / link / framebuffer status all "ok")
 * and draws nothing. The EGL loader uses it for the GLESv2 dispatch table, and the EGL wrapper only hands that table
 * to contexts created on libagl's ES2-only configs (see small-patches.py); everything else, SurfaceFlinger included,
 * keeps libagl exactly as before.
 */
#include <string.h>
#include <stdint.h>
#include <GLES/gl.h>
#include <GLES/glext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES3/gl32.h>

#pragma GCC diagnostic ignored "-Wunused-parameter"

namespace {

uint32_t g_next_id = 1;
GLuint next_id() { return __atomic_fetch_add(&g_next_id, 1, __ATOMIC_RELAXED); }

// every entry point the EGL loader knows: do nothing, return 0
#define GL_ENTRY(_r, _api, ...) _r n_##_api(__VA_ARGS__) { return (_r)0; }
#include "entries.in"
#undef GL_ENTRY

void gen(GLsizei n, GLuint* ids) { for (GLsizei i = 0; ids && i < n; i++) ids[i] = next_id(); }
void v_glGenBuffers(GLsizei n, GLuint* ids) { gen(n, ids); }
void v_glGenTextures(GLsizei n, GLuint* ids) { gen(n, ids); }
void v_glGenFramebuffers(GLsizei n, GLuint* ids) { gen(n, ids); }
void v_glGenRenderbuffers(GLsizei n, GLuint* ids) { gen(n, ids); }
void v_glGenQueriesEXT(GLsizei n, GLuint* ids) { gen(n, ids); }
void v_glGenVertexArraysOES(GLsizei n, GLuint* ids) { gen(n, ids); }
GLuint v_glCreateShader(GLenum) { return next_id(); }
GLuint v_glCreateProgram() { return next_id(); }
GLboolean v_glIsObject(GLuint id) { return id ? GL_TRUE : GL_FALSE; }
GLenum v_glCheckFramebufferStatus(GLenum) { return GL_FRAMEBUFFER_COMPLETE; }
GLint v_glGetLocation(GLuint, const GLchar*) { return -1; }

const GLubyte* v_glGetString(GLenum name) {
    switch (name) {
    case GL_VENDOR: return (const GLubyte*)"VeraDroid";
    case GL_RENDERER: return (const GLubyte*)"VeraDroid null GLES2 (no GPU, views are drawn by the CPU)";
    case GL_VERSION: return (const GLubyte*)"OpenGL ES 2.0 VeraDroid-null";
    case GL_SHADING_LANGUAGE_VERSION: return (const GLubyte*)"OpenGL ES GLSL ES 1.00";
    case GL_EXTENSIONS:
        return (const GLubyte*)"GL_OES_rgb8_rgba8 GL_OES_packed_depth_stencil GL_OES_depth24 "
                               "GL_OES_element_index_uint GL_OES_texture_npot GL_EXT_texture_format_BGRA8888";
    }
    return 0;
}

// number of values a glGet* writes for pname, and the value(s); 0 = unknown pname (write one zero)
int limits(GLenum pname, GLint* v) {
    switch (pname) {
    case GL_MAX_TEXTURE_SIZE: case GL_MAX_RENDERBUFFER_SIZE: case GL_MAX_CUBE_MAP_TEXTURE_SIZE: v[0] = 4096; return 1;
    case GL_MAX_VIEWPORT_DIMS: v[0] = v[1] = 4096; return 2;
    case GL_MAX_VERTEX_ATTRIBS: v[0] = 16; return 1;
    case GL_MAX_TEXTURE_IMAGE_UNITS: case GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS: v[0] = 16; return 1;
    case GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: v[0] = 32; return 1;
    case GL_MAX_VERTEX_UNIFORM_VECTORS: case GL_MAX_FRAGMENT_UNIFORM_VECTORS: v[0] = 256; return 1;
    case GL_MAX_VARYING_VECTORS: v[0] = 15; return 1;
    case GL_RED_BITS: case GL_GREEN_BITS: case GL_BLUE_BITS: case GL_ALPHA_BITS: v[0] = 8; return 1;
    case GL_DEPTH_BITS: v[0] = 16; return 1;
    case GL_SUBPIXEL_BITS: v[0] = 4; return 1;
    case GL_PACK_ALIGNMENT: case GL_UNPACK_ALIGNMENT: v[0] = 4; return 1;
    case GL_IMPLEMENTATION_COLOR_READ_FORMAT: v[0] = GL_RGBA; return 1;
    case GL_IMPLEMENTATION_COLOR_READ_TYPE: v[0] = GL_UNSIGNED_BYTE; return 1;
    case GL_NUM_COMPRESSED_TEXTURE_FORMATS: case GL_NUM_SHADER_BINARY_FORMATS: v[0] = 0; return 1;
    case GL_SHADER_COMPILER: v[0] = GL_TRUE; return 1;
    case GL_COMPRESSED_TEXTURE_FORMATS: case GL_SHADER_BINARY_FORMATS: return -1;   // zero values follow
    case GL_ALIASED_POINT_SIZE_RANGE: case GL_ALIASED_LINE_WIDTH_RANGE: v[0] = 1; v[1] = 1; return 2;
    case GL_VIEWPORT: case GL_SCISSOR_BOX: v[0] = v[1] = 0; v[2] = v[3] = 1; return 4;
    case GL_COLOR_WRITEMASK: v[0] = v[1] = v[2] = v[3] = 1; return 4;
    case GL_COLOR_CLEAR_VALUE: case GL_BLEND_COLOR: v[0] = v[1] = v[2] = v[3] = 0; return 4;
    case GL_DEPTH_RANGE: v[0] = 0; v[1] = 1; return 2;
    }
    v[0] = 0;
    return 1;
}
void v_glGetIntegerv(GLenum p, GLint* out) { GLint v[4]; int n = limits(p, v); for (int i = 0; out && i < n; i++) out[i] = v[i]; }
void v_glGetFloatv(GLenum p, GLfloat* out) { GLint v[4]; int n = limits(p, v); for (int i = 0; out && i < n; i++) out[i] = (GLfloat)v[i]; }
void v_glGetBooleanv(GLenum p, GLboolean* out) { GLint v[4]; int n = limits(p, v); for (int i = 0; out && i < n; i++) out[i] = v[i] ? GL_TRUE : GL_FALSE; }

void v_glGetShaderPrecisionFormat(GLenum, GLenum type, GLint* range, GLint* precision) {
    bool isInt = type == GL_LOW_INT || type == GL_MEDIUM_INT || type == GL_HIGH_INT;
    if (range) { range[0] = isInt ? 31 : 127; range[1] = isInt ? 30 : 127; }
    if (precision) *precision = isInt ? 0 : 23;
}
void v_glGetShaderiv(GLuint, GLenum pname, GLint* p) {
    if (p) *p = (pname == GL_COMPILE_STATUS) ? GL_TRUE : 0;
}
void v_glGetProgramiv(GLuint, GLenum pname, GLint* p) {
    if (p) *p = (pname == GL_LINK_STATUS || pname == GL_VALIDATE_STATUS) ? GL_TRUE : 0;
}
void v_glGetInfoLog(GLuint, GLsizei bufSize, GLsizei* length, GLchar* log) {
    if (length) *length = 0;
    if (log && bufSize > 0) log[0] = 0;
}
void v_glReadPixels(GLint, GLint, GLsizei w, GLsizei h, GLenum format, GLenum type, void* pixels) {
    if (!pixels || w <= 0 || h <= 0) return;
    size_t bpp = 0;
    switch (type) {
    case GL_UNSIGNED_BYTE:
        switch (format) {
        case GL_RGBA: case GL_BGRA_EXT: bpp = 4; break;
        case GL_RGB: bpp = 3; break;
        case GL_LUMINANCE_ALPHA: bpp = 2; break;
        case GL_ALPHA: case GL_LUMINANCE: bpp = 1; break;
        }
        break;
    case GL_UNSIGNED_SHORT_5_6_5:
    case GL_UNSIGNED_SHORT_4_4_4_4:
    case GL_UNSIGNED_SHORT_5_5_5_1:
        bpp = 2;
        break;
    }
    if (!bpp) return;
    size_t row = (size_t)w;
    if (row > SIZE_MAX / bpp) return;
    row *= bpp;
    if ((size_t)h > SIZE_MAX / row) return;
    memset(pixels, 0, row * (size_t)h);
}

struct Entry { const char* name; void* fn; };
const Entry kOverrides[] = {
    { "glGenBuffers", (void*)v_glGenBuffers },
    { "glGenTextures", (void*)v_glGenTextures },
    { "glGenFramebuffers", (void*)v_glGenFramebuffers },
    { "glGenRenderbuffers", (void*)v_glGenRenderbuffers },
    { "glGenQueriesEXT", (void*)v_glGenQueriesEXT },
    { "glGenVertexArraysOES", (void*)v_glGenVertexArraysOES },
    { "glGenVertexArrays", (void*)v_glGenBuffers },
    { "glGenSamplers", (void*)v_glGenBuffers },
    { "glCreateShader", (void*)v_glCreateShader },
    { "glCreateProgram", (void*)v_glCreateProgram },
    { "glIsBuffer", (void*)v_glIsObject },
    { "glIsTexture", (void*)v_glIsObject },
    { "glIsFramebuffer", (void*)v_glIsObject },
    { "glIsRenderbuffer", (void*)v_glIsObject },
    { "glIsShader", (void*)v_glIsObject },
    { "glIsProgram", (void*)v_glIsObject },
    { "glCheckFramebufferStatus", (void*)v_glCheckFramebufferStatus },
    { "glGetAttribLocation", (void*)v_glGetLocation },
    { "glGetUniformLocation", (void*)v_glGetLocation },
    { "glGetString", (void*)v_glGetString },
    { "glGetIntegerv", (void*)v_glGetIntegerv },
    { "glGetFloatv", (void*)v_glGetFloatv },
    { "glGetBooleanv", (void*)v_glGetBooleanv },
    { "glGetShaderPrecisionFormat", (void*)v_glGetShaderPrecisionFormat },
    { "glGetShaderiv", (void*)v_glGetShaderiv },
    { "glGetProgramiv", (void*)v_glGetProgramiv },
    { "glGetShaderInfoLog", (void*)v_glGetInfoLog },
    { "glGetProgramInfoLog", (void*)v_glGetInfoLog },
    { "glReadPixels", (void*)v_glReadPixels },
};
const Entry kAll[] = {
#define GL_ENTRY(_r, _api, ...) { #_api, (void*)n_##_api },
#include "entries.in"
#undef GL_ENTRY
};

}  // namespace

// The EGL loader (Loader.cpp, VeraDroid patch) fills the GLESv2 dispatch table with this.
extern "C" __attribute__((visibility("default"))) void* vdnull_gles2_get_proc(const char* name) {
    if (!name) return 0;
    for (const Entry& e : kOverrides) if (!strcmp(e.name, name)) return e.fn;
    for (const Entry& e : kAll) if (!strcmp(e.name, name)) return e.fn;
    return 0;
}
