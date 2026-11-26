// SPDX-License-Identifier: GPL-3.0-or-later
/*
 * veradroid-input: touch front end for VeraDroid.
 *
 * Grabs the Kindle touch screen (FocalTech fts_ts, multitouch type B), recognises the global gestures (they must work in
 * every Android app, and cannot be switched off for the bottom-edge "back"), and passes everything else through to a
 * uinput virtual touch screen ("veradroid-touch") that the Android container reads. Gesture results are injected as
 * key events on a second virtual device ("veradroid-keys") or run as host actions.
 *
 *   edge gestures (single finger, never forwarded):
 *       start in the bottom edge and swipe up      -> KEY_BACK
 *       start in the top edge and swipe down       -> action "panel"
 *   two fingers (second finger within --window ms of the first; never forwarded):
 *       tap                                         -> action "clear"   (full-screen refresh)
 *       long press (>= 2 s)                         -> action "invert"
 *       horizontal swipe                            -> KEY_BACK
 *       vertical swipe, left half / right half      -> "light+/-" / "warmth+/-"
 *   one finger, long press (>= --corner-ms) in a screen corner -> actions "tl" "tr" "bl" "br" (the finger is released
 *       towards Android first)
 *
 * Each gesture can be switched off on its own (--gconf FILE, KEY=0|1 lines, re-read on the next touch after it changes):
 *   G_EDGE_BACK (bottom edge up -> back)  G_EDGE_PANEL (top edge down -> panel)  G_TWO_TAP (clear)  G_TWO_LONG (invert)
 *   G_TWO_BACK (two-finger horizontal -> back)  G_TWO_SLIDE (two-finger vertical -> light / warmth)
 *   G_CORNERS=tl,bl  -- only these corners take the long press (the ones with an action; "none" = no corner; key absent = all).
 *   G_BACK_EXCLUDE=pkg,pkg  -- no bottom-edge back while one of these apps is in front (--fg FILE holds the foreground
 *   package, written by VeraHome). WeRead uses its own swipe-up for the reader toolbar.
 *   G_EDGE_THIRDS=1  -- bottom edge in thirds: the swipe up does G_EB_L / G_EB_M / G_EB_R depending on
 *   where it started (left / middle / right third of the logical width), and "swipe up and hold still" for
 *   G_EB_HOLD_MS (default 500) does G_EB_HOLD. Values are action names for action.sh ("eb <name>"), "back" is sent as
 *   the key right here, "none" = nothing (a third whose action and the hold are both none is not a gesture at all: the
 *   touch goes to Android). Defaults appopt / home / back / switch. Only while the gestures are on (--flag absent) and
 *   the bottom-edge gesture itself is on (G_EDGE_BACK, not excluded); otherwise the plain bottom-edge back as before.
 *   With a hold action the third's action fires when the finger is lifted (not when the swipe is long enough).
 *   With every two-finger gesture off, two-finger touches go to Android (pinch zoom).
 *
 * Actions run  sh <actdir>/action.sh <name> [arg]  (the host decides what they do), and are logged.
 *
 * Signals: SIGUSR1 releases the touch screen (the Kindle UI can use it; we drop everything we read), SIGUSR2 grabs it again.
 *
 * Bezel double tap (--tapdev, PW6 and other models with the lis2du12 accelerometer): the Kindle's gesturetapd turns the
 *   accelerometer's double-tap interrupt (iio lis2du12_tap_tap + 30 extra samples, its own filter) into keys on a uinput
 *   device "gesture_tap" (KEY_F6..F8 advertised). It only runs where devcap allows it ("Base config device" -> exits).
 *   We grab that device while Android is in front and turn a press into KEY_VOLUMEDOWN (next page in WeRead and most
 *   readers); G_TAP=0 in --gconf switches it off, G_TAP_KEY=pageup|pagedown|volup|voldown picks the key.
 *
 * Rotation (--rotate 0|90|180|270, docs/rotation.md): Android draws upright into a buffer of the LOGICAL size (portrait
 *   W x H for 0/180, H x W for 90/270) and veradroid-epd rotates it onto the panel. Here every position event is turned
 *   from panel coordinates into logical ones as soon as it is read (also the --inject ones, which stay in panel
 *   coordinates like a real finger), so the gesture code, edge strips, corners and the virtual touch screen all work in
 *   the logical picture. --w/--h stay the PANEL size in pixels (--vw: visible width when --w is the framebuffer stride,
 *   PW5 1248/1236); the flips use them (not the touch range) so that touch and picture use exactly the same mapping as
 *   veradroid-epd. Logical size: W x H at 0/180, H x VW at 90/270. 90 = the content is turned 90 degrees clockwise on the panel (its top is
 *   at the panel's right edge). A "ROTATE=n" line in --gconf switches live between 0/180 or 90/270 (same logical size;
 *   without --rotate the virtual touch screen keeps the source ranges, which must then equal the panel pixels).
 *   With --rotate the single-touch axes (ABS_X / ABS_Y, PW5 goodix) are rotated and passed on as well.
 *
 * Source protocols (detected from the device's ABS bits, --proto b|a|st forces one):
 *   B   (ABS_MT_SLOT: fts_ts, goodix-ts gtx8, cyttsp4, ili211x, pt_mt) -- read as is, the original path, unchanged.
 *   A   (ABS_MT_POSITION_X without ABS_MT_SLOT: contacts separated by SYN_MT_REPORT, a frame without contacts = all up)
 *       -- every frame is turned into slot events before anything else looks at it: a contact is matched to a slot by
 *       ABS_MT_TRACKING_ID when the driver sends one, otherwise to the nearest finger of the previous frame (greedy, at
 *       most a third of the screen away); a slot with no contact in a frame is released. New contacts get fresh tracking
 *       ids. A contact with ABS_MT_PRESSURE 0 or a frame with BTN_TOUCH 0 counts as lifted.
 *   ST  (ABS_X / ABS_Y + BTN_TOUCH only) -- one finger in slot 0 (down while BTN_TOUCH is 1).
 *   For A and ST the virtual touch screen is a protocol-B one (ABS_MT_SLOT + ABS_MT_TRACKING_ID, position ranges from the
 *   source), so the gestures and Android see exactly what a B panel would give. The conversion adds no delay: it works
 *   on the frame as it is read.
 *
 * Touch boost (gconf BOOST_MS, BOOST_KHZ): when the first finger lands, the CPU's minimum frequency
 *   (cpufreq scaling_min_freq) is raised to BOOST_KHZ (0 = scaling_max_freq) and put back BOOST_MS after the last finger
 *   left (default 150; 0 = off). Only with --cpufreq <dir> (the cpufreq directory, e.g. /sys/devices/system/cpu/cpu0/cpufreq). The Kindle's ondemand governor only steps up after a 20 ms sample >= 95 % busy, so the
 *   app's answer to a tap used to start at 600 MHz.
 * Scroll hints (gconf SCROLL_PX): a single finger that Android gets and that moved more than SCROLL_PX panel pixels
 *   (default 20 on a 1272 px wide panel, scaled to the panel width; about Android's touch slop) sends "SCROLL 1" to the
 *   display bridge, and "SCROLL 0" when it is lifted: veradroid-epd draws the drag with DU and cleans up once after
 *   (KFB_SCROLLDELAY). 0 = off.
 * Early release (gconf PASS_PX, default 12 on a 1272 px panel): a single finger that is still inside the two-finger window
 *   (--window) but already moved more than PASS_PX from where it landed is a drag: it goes to Android at once instead of
 *   after the window. 0 = always wait the window.
 * --rt <prio>: run as SCHED_FIFO <prio> (reset on fork: action.sh and the like run normally).
 * Idle freeze (host IDLE_FREEZE): the host freezes the Android container after a while with no touch
 *   and no screen change, and leaves the file --idle-mark. On the next touch-down, before the frame is forwarded, this
 *   program writes THAWED to --idle-freezer (the container's freezer.state), wakes the container's network helper
 *   (SIGCONT to the pid in --slirp-pid) and removes the mark: Android's input reader then reads the queued touch as usual.
 *   --touch-stamp FILE gets its modification time set on every touch-down (the host's "last input" clock).
 *
 * Stuck contacts: Android must never keep a contact down that the panel no longer has. Every frame
 *   read from a protocol-B panel names its slot (ABS_MT_SLOT is added when the driver left it out), so frames that a
 *   gesture swallowed can no longer leave Android's slot pointer somewhere else. Whenever a gesture takes a touch back,
 *   every contact Android was given is lifted (by what was sent to the virtual touch screen, not by the panel's state).
 *   After every frame: in a mode that holds or swallows the touch Android must have no contact, otherwise only contacts
 *   the panel still has. A tracking id that changes without a lift is sent to Android as a lift + a new contact.
 *   Watchdog (--stuck-ms, default 2000, 0 = off): no panel event for that long while a contact is down -> ask the kernel
 *   (EVIOCGMTSLOTS) which slots really hold a finger and lift the others. "reset [why]" on the --inject FIFO lifts
 *   everything in Android (the host sends it around freezing / thawing the container); the rest of a touch that is
 *   still down then goes nowhere.
 * Usage: veradroid-input <touch-event-dev> <log-file> <action-script> [--gconf FILE] [--fg FILE] [--w 1272] [--h 1696] [--edge 60] [--window 50]
 *                        [--corner 160] [--corner-ms 2000] [--edge-hold 60] [--edge-swipe 80] [--edge-back 0|1] [--no-gestures]
 *                        [--rotate 0|90|180|270] [--proto b|a|st]
 * Build (armhf, static): arm-linux-gnueabihf-gcc -O2 -static vinput.c -o veradroid-input
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAXSLOT 64   /* cyttsp4 (PW2/PW3/Voyage/Oasis 1) has max_tchs+1 slots, typically 11; ilitek 10; fts_ts 2; pt_mt (KT5 / KT6) 32.
                        It was 16: a pt_mt contact in slot 16..31 was invisible to the gestures and to the stuck-contact
                        bookkeeping */
#define ASLOTS 16    /* slots of the virtual touch screen for protocol A / single-touch sources */
#define MAXEV 512

static int W = 1272, H = 1696, EDGE = 60, EDGE_TOP = -1, TOUCH_DEBUG = 0, WINDOW_MS = 80, CORNER = 160, CORNER_MS = 2000, GESTURES = 1, EDGE_BACK = 1;
static const char *LOGF = "/dev/null", *ACT = "", *FLAGF = NULL, *INJECT = NULL, *GCONF = NULL, *FGF = NULL;
/* per-gesture switches (--gconf) */
static int G_EDGE_BACK = 1, G_EDGE_PANEL = 1, G_TWO_TAP = 1, G_TWO_LONG = 1, G_TWO_BACK = 1, G_TWO_SLIDE = 1;
static int G_TAP = 1, G_TAP_KEY = KEY_VOLUMEDOWN;   /* bezel double tap (gesture_tap) -> this key, see the header */
static int tapfd = -1;
static int BOOST_MS = 150, BOOST_KHZ = 0, SCROLL_PX = 20, PASS_PX = 12;
static int TAP_PX = 28;       /* gconf TAP_PX: tap dead zone (px on a 1272 px wide panel, scaled), see dz_forward; 0 = off */
static int HELD_REPLAY = 0;   /* gconf HELD_REPLAY=1: held frames replayed as read (before 1.04 / 1.12), see flush_held */
static int RT_PRIO = 0, RT_GCONF = -1, started = 0;   /* --rt, gconf RT= (overrides it while present) */
static void set_rt(int prio, const char *who);   /* touch boost / scroll hints, see the header */
static const char *TAPDEV = NULL;
static int G_EDGE_SENS = 1;            /* edge swipe sensitivity: 0 low, 1 standard, 2 high (scales the strip width and the swipe distance) */
static int EDGE_SWIPE;                 /* defined with its default further down */
static int EDGE_BASE = -1, EDGE_TOP_BASE = -1, EDGE_SWIPE_BASE = -1;   /* command-line values, captured at startup */
static char G_BACK_EXCLUDE[512] = "";
static char G_CORNERS[24] = "all";   /* corners whose long press is a gesture ("tl,bl", "" = none, "all"); others stay Android's */
/* bottom edge in thirds (see the header): G_EB[0..2] = left / middle / right swipe up, G_EB[3] = swipe up and hold */
static int G_EDGE_THIRDS = 0, G_EB_HOLD_MS = 500;
static char G_EB[4][24] = { "appopt", "home", "back", "switch" };
static const char *const EB_KEY[4] = { "G_EB_L", "G_EB_M", "G_EB_R", "G_EB_HOLD" };
static const char *const EB_DEF[4] = { "appopt", "home", "back", "switch" };
/* rotation (see the header): ROT_ON = --rotate was given at all; PW/PH = panel size in pixels; W/H become the logical size */
static int ROT = 0, ROT_ON = 0, PW = 0, PH = 0, VW = 0;   /* FLAGF exists -> gestures are off (only bottom-edge back stays) */
static volatile sig_atomic_t quit = 0, want_release = 0, want_grab = 0;
static int paused = 0;
static int hw_slot = 0;          /* protocol B: the slot the panel's next events refer to (it only sends ABS_MT_SLOT on change) */
static long long last_hw_ms = 0;      /* last event read from the panel */
static int STUCK_MS = 2000;      /* --stuck-ms: the watchdog, see the header */
static unsigned long n_released = 0, n_tidchg = 0, n_wd = 0, n_reset = 0;
static int src_btn_touch = 0;
static int tfd = -1, ufd = -1, kfd = -1;
enum { PROTO_B, PROTO_A, PROTO_ST };
static int src_proto = PROTO_B, proto_forced = -1;

static void on_sig(int s) { (void)s; quit = 1; }
static void on_usr1(int s) { (void)s; want_release = 1; }   /* give the touch screen back to the Kindle */
static void on_usr2(int s) { (void)s; want_grab = 1; }      /* take it again */
static long long now_ms(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return (long long)ts.tv_sec * 1000LL + ts.tv_nsec / 1000000LL; }

static void logf_(const char *fmt, ...)
{
    FILE *f = fopen(LOGF, "a");
    if (!f) return;
    va_list ap; va_start(ap, fmt);
    fprintf(f, "[%lld] ", now_ms());
    vfprintf(f, fmt, ap);
    fputc('\n', f);
    va_end(ap); fclose(f);
}

static long long gconf_sig = -2;   /* long long: on 32-bit ARM tv_sec*1000 overflowed long -> looked like a stat failure -> file never parsed */
static void gconf_load(void)
{
    if (!GCONF) return;
    struct stat st;
    int ok = stat(GCONF, &st) == 0;
    long long sig = !ok ? -1 : (long long)st.st_mtim.tv_sec * 1000 + st.st_mtim.tv_nsec / 1000000 + (long long)st.st_size * 7919;
    if (sig == gconf_sig) return;
    gconf_sig = sig;
    G_EDGE_BACK = G_EDGE_PANEL = G_TWO_TAP = G_TWO_LONG = G_TWO_BACK = G_TWO_SLIDE = 1; G_BACK_EXCLUDE[0] = 0; G_EDGE_SENS = 1;
    G_TAP = 1; G_TAP_KEY = KEY_VOLUMEDOWN; snprintf(G_CORNERS, sizeof(G_CORNERS), "all");
    BOOST_MS = 150; BOOST_KHZ = 0; SCROLL_PX = 20; PASS_PX = 12; RT_GCONF = -1; HELD_REPLAY = 0; TAP_PX = 28;
    G_EDGE_THIRDS = 0; G_EB_HOLD_MS = 500;
    for (int i = 0; i < 4; i++) snprintf(G_EB[i], sizeof(G_EB[i]), "%s", EB_DEF[i]);
    FILE *f = !ok ? NULL : fopen(GCONF, "r");
    if (f) {
        char line[600];
        while (fgets(line, sizeof(line), f)) {
            char *eq = strchr(line, '='); if (!eq) continue;
            *eq = 0; char *v = eq + 1; v[strcspn(v, "\r\n")] = 0;
            int b = atoi(v) != 0;
            if (!strcmp(line, "G_EDGE_BACK")) G_EDGE_BACK = b;
            else if (!strcmp(line, "G_EDGE_PANEL")) G_EDGE_PANEL = b;
            else if (!strcmp(line, "G_TWO_TAP")) G_TWO_TAP = b;
            else if (!strcmp(line, "G_TWO_LONG")) G_TWO_LONG = b;
            else if (!strcmp(line, "G_TWO_BACK")) G_TWO_BACK = b;
            else if (!strcmp(line, "G_TWO_SLIDE")) G_TWO_SLIDE = b;
            else if (!strcmp(line, "G_TAP")) G_TAP = b;
            else if (!strcmp(line, "G_EDGE_THIRDS")) G_EDGE_THIRDS = b;
            else if (!strcmp(line, "G_EB_HOLD_MS")) { G_EB_HOLD_MS = atoi(v); if (G_EB_HOLD_MS < 200 || G_EB_HOLD_MS > 3000) G_EB_HOLD_MS = 500; }
            else if (!strncmp(line, "G_EB_", 5)) {
                for (int i = 0; i < 4; i++)
                    if (!strcmp(line, EB_KEY[i])) {   /* an action name: plain characters only (action.sh checks it again) */
                        int okv = v[0] != 0 && strlen(v) < sizeof(G_EB[i]);
                        for (const char *c = v; *c; c++) if (!((*c >= 'a' && *c <= 'z') || (*c >= 'A' && *c <= 'Z') || (*c >= '0' && *c <= '9') || *c == '.' || *c == '_')) okv = 0;
                        if (okv) snprintf(G_EB[i], sizeof(G_EB[i]), "%.23s", v);
                    }
            }
            else if (!strcmp(line, "BOOST_MS")) { BOOST_MS = atoi(v); if (BOOST_MS < 0 || BOOST_MS > 2000) BOOST_MS = 150; }
            else if (!strcmp(line, "BOOST_KHZ")) { BOOST_KHZ = atoi(v); if (BOOST_KHZ < 0) BOOST_KHZ = 0; }
            else if (!strcmp(line, "RT")) { int r = atoi(v); if (r >= 0 && r <= 20) RT_GCONF = r; }
            else if (!strcmp(line, "PASS_PX")) { PASS_PX = atoi(v); if (PASS_PX < 0 || PASS_PX > 400) PASS_PX = 12; }
            else if (!strcmp(line, "HELD_REPLAY")) HELD_REPLAY = b;
            else if (!strcmp(line, "TAP_PX")) { TAP_PX = atoi(v); if (TAP_PX < 0 || TAP_PX > 200) TAP_PX = 28; }
            else if (!strcmp(line, "SCROLL_PX")) { SCROLL_PX = atoi(v); if (SCROLL_PX < 0 || SCROLL_PX > 400) SCROLL_PX = 20; }
            else if (!strcmp(line, "G_TAP_KEY")) {
                if (!strcmp(v, "pageup")) G_TAP_KEY = KEY_PAGEUP;
                else if (!strcmp(v, "pagedown")) G_TAP_KEY = KEY_PAGEDOWN;
                else if (!strcmp(v, "volup")) G_TAP_KEY = KEY_VOLUMEUP;
                else G_TAP_KEY = KEY_VOLUMEDOWN;
            }
            else if (!strcmp(line, "G_EDGE_SENS")) { G_EDGE_SENS = atoi(v); if (G_EDGE_SENS < 0 || G_EDGE_SENS > 2) G_EDGE_SENS = 1; }
            else if (!strcmp(line, "G_CORNERS")) snprintf(G_CORNERS, sizeof(G_CORNERS), "%s", strcmp(v, "none") ? v : "");
            else if (!strcmp(line, "G_BACK_EXCLUDE")) { snprintf(G_BACK_EXCLUDE, sizeof(G_BACK_EXCLUDE), "%s", strcmp(v, "none") ? v : ""); }
            else if (!strcmp(line, "ROTATE")) {
                /* live only within the same logical size: the virtual touch screen's ranges are fixed at start */
                int r = atoi(v);
                if ((r == 0 || r == 90 || r == 180 || r == 270) && (r % 180) == (ROT % 180) && r != ROT) {
                    logf_("rotate %d -> %d", ROT, r);
                    ROT = r;
                }
            }
        }
        fclose(f);
    }
    if (started) set_rt(RT_GCONF >= 0 ? RT_GCONF : RT_PRIO, "veradroid-input");   /* gconf RT= switches it live */
    if (EDGE_BASE > 0) {   /* low: narrower strip, longer swipe; high: wider strip, shorter swipe */
        static const int strip_pct[3] = { 67, 100, 140 }, swipe_pct[3] = { 150, 100, 60 };
        EDGE = EDGE_BASE * strip_pct[G_EDGE_SENS] / 100;
        EDGE_TOP = EDGE_TOP_BASE * strip_pct[G_EDGE_SENS] / 100;
        EDGE_SWIPE = EDGE_SWIPE_BASE * swipe_pct[G_EDGE_SENS] / 100;
    }
    logf_("gconf: edge_sens=%d (strip %d/%d px, swipe %d px) boost=%dms/%dkHz scroll_px=%d pass_px=%d", G_EDGE_SENS, EDGE, EDGE_TOP, EDGE_SWIPE, BOOST_MS, BOOST_KHZ, SCROLL_PX, PASS_PX);
    logf_("gconf: edge_back=%d edge_panel=%d two_tap=%d two_long=%d two_back=%d two_slide=%d exclude=%s",
          G_EDGE_BACK, G_EDGE_PANEL, G_TWO_TAP, G_TWO_LONG, G_TWO_BACK, G_TWO_SLIDE, G_BACK_EXCLUDE[0] ? G_BACK_EXCLUDE : "-");
    if (G_EDGE_THIRDS) logf_("gconf: bottom edge thirds: left=%s middle=%s right=%s hold=%s (%d ms)", G_EB[0], G_EB[1], G_EB[2], G_EB[3], G_EB_HOLD_MS);
}
/* is the foreground app (VeraHome writes it to --fg) in G_BACK_EXCLUDE? */
static int back_excluded(void)
{
    if (!FGF || !G_BACK_EXCLUDE[0]) return 0;
    char pkg[256] = "";
    FILE *f = fopen(FGF, "r");
    if (!f) return 0;
    if (!fgets(pkg, sizeof(pkg), f)) pkg[0] = 0;
    fclose(f);
    pkg[strcspn(pkg, "\r\n /")] = 0;
    if (!pkg[0]) return 0;
    size_t l = strlen(pkg);
    for (const char *p = G_BACK_EXCLUDE; (p = strstr(p, pkg)) != NULL; p += l) {
        int start_ok = p == G_BACK_EXCLUDE || p[-1] == ',';
        int end_ok = p[l] == 0 || p[l] == ',';
        if (start_ok && end_ok) return 1;
    }
    return 0;
}
static int two_any(void) { return G_TWO_TAP || G_TWO_LONG || G_TWO_BACK || G_TWO_SLIDE; }

/* ---- what Android has been told (every write to the virtual touch screen goes through uout) ----
   BTN_TOUCH on the virtual device follows the fingers we forwarded, not the panel's own BTN_TOUCH: Android's multitouch
   reader treats a contact while BTN_TOUCH is 0 as hovering (ACTION_HOVER_*, no tap). The PW5 goodix driver releases every
   finger when the Kindle locks the panel for the screensaver; when a finger was down at that moment (tapping "sleep" on
   the home screen, a thumb resting on the glass) its first contact after the wake comes without BTN_TOUCH 1 (pw5-5192
   emulator, real goodix driver), so the first tap after waking reached Android as a hover and did nothing. */
static int out_slot = 0, out_btn = 0;
static unsigned char out_act[MAXSLOT];
static int out_tid[MAXSLOT];
static unsigned long btn_fixes = 0;
/* ABS_MT_PRESSURE the same way: when the virtual device has a pressure axis (copied from panels that report one: PW4,
   Oasis 2/3, ...), Android also counts a contact whose pressure is 0 as hovering. Our own contacts (veradroid-input's
   test / inject path, contacts converted from drivers that leave pressure out of the first frame) carried none, so on the
   legacy emulators every injected tap reached Android as HOVER_MOVE / HOVER_EXIT. Every new contact whose frame has no
   pressure of its own gets one. It alternates between two values: the kernel drops an ABS value equal to the one it
   already has, and Android clears its slots whenever it (re)opens the device (Android restart), so a repeated value
   would never reach it. */
static int out_pres_max = 0;          /* > 0: the virtual device has ABS_MT_PRESSURE (0..out_pres_max) */
static int out_pres[MAXSLOT];
static unsigned char out_new[MAXSLOT], out_pset[MAXSLOT];   /* this frame: slot got a new tracking id / its own pressure */
static unsigned long pres_fixes = 0;
static void uraw(int type, int code, int value)
{
    struct input_event e;
    memset(&e, 0, sizeof(e));
    e.type = type; e.code = code; e.value = value;
    if (write(ufd, &e, sizeof(e)) < 0) { /* device gone */ }
}
static void uout(const struct input_event *e)
{
    if (e->type == EV_ABS && e->code == ABS_MT_SLOT) {
        if (e->value == out_slot) return;    /* the kernel drops it as well: Android's slot pointer is already there */
        out_slot = e->value;
    }
    else if (e->type == EV_ABS && e->code == ABS_MT_TRACKING_ID) {
        if (out_slot >= 0 && out_slot < MAXSLOT) {
            if (e->value >= 0 && out_act[out_slot] && out_tid[out_slot] != e->value) {
                /* a new tracking id in a slot Android still has down (the lift was lost or swallowed, or the panel
                   reused the slot within one frame): Android gets the lift first, as a frame of its own */
                struct input_event r = *e; r.value = -1; uout(&r);
                r.type = EV_SYN; r.code = SYN_REPORT; r.value = 0; uout(&r);
                if (++n_tidchg <= 10) logf_("slot %d: tracking id %d -> %d without a lift, lifted it in Android first", out_slot, out_tid[out_slot], e->value);
            }
            out_act[out_slot] = e->value >= 0; out_tid[out_slot] = e->value; if (e->value >= 0) out_new[out_slot] = 1;
        }
    }
    else if (e->type == EV_KEY && e->code == BTN_TOUCH) out_btn = e->value != 0;
    else if (e->type == EV_ABS && e->code == ABS_MT_PRESSURE) { if (out_slot >= 0 && out_slot < MAXSLOT) { out_pres[out_slot] = e->value; out_pset[out_slot] = 1; } }
    else if (e->type == EV_SYN && e->code == SYN_REPORT) {
        int any = 0, fixed = 0;
        for (int i = 0; i < MAXSLOT; i++) any |= out_act[i];
        if (out_pres_max > 0) {
            for (int i = 0; i < MAXSLOT; i++) {
                if (out_act[i] && !out_pset[i] && (out_new[i] || out_pres[i] <= 0)) {
                    int a = out_pres_max > 1 ? out_pres_max / 2 : 1, b = a > 1 ? a - 1 : a + 1;
                    int v = out_pres[i] == a ? b : a;
                    uraw(EV_ABS, ABS_MT_SLOT, i); uraw(EV_ABS, ABS_MT_PRESSURE, v); out_pres[i] = v; fixed = 1;
                    if (++pres_fixes <= 5) logf_("pressure: new contact in slot %d came without pressure, sent %d with the frame", i, v);
                }
                out_new[i] = 0; out_pset[i] = 0;
            }
            if (fixed) uraw(EV_ABS, ABS_MT_SLOT, out_slot);
        }
        if (any != out_btn) {
            uraw(EV_KEY, BTN_TOUCH, any); out_btn = any;
            if (++btn_fixes <= 20 && src_btn_touch) logf_("btn_touch: panel %s, sent BTN_TOUCH %d with the frame", any ? "left it out on a new contact" : "kept it down with no contact", any);
        }
    }
    if (write(ufd, e, sizeof(*e)) < 0) { /* device gone */ }
}
static void emit(int fd, int type, int code, int value)
{
    struct input_event e;
    memset(&e, 0, sizeof(e));
    e.type = type; e.code = code; e.value = value;
    if (fd == ufd) { uout(&e); return; }
    if (write(fd, &e, sizeof(e)) < 0) { /* device gone */ }
}

static void key_tap(int code)
{
    emit(kfd, EV_KEY, code, 1); emit(kfd, EV_SYN, SYN_REPORT, 0);
    emit(kfd, EV_KEY, code, 0); emit(kfd, EV_SYN, SYN_REPORT, 0);
}

static void action(const char *name, const char *arg)
{
    logf_("action %s %s", name, arg ? arg : "");
    if (!ACT[0]) return;
    pid_t p = fork();
    if (p == 0) {
        setsid();
        execlp("sh", "sh", ACT, name, arg ? arg : "", (char *)NULL);
        _exit(127);
    }
    /* children are reaped by SIGCHLD ignore */
}

/* back gesture: the key for Android, and "hint back" so the host shows its on-screen badge (action.sh, GESTURE_HINT) */
static void back_key(void)
{
    key_tap(KEY_BACK);
    action("hint", "back");
}

/* ---- touch state ---- */
struct slot { int active; int x, y; int sx, sy; long long t0, tl; };   /* tl: last event for this slot */
static struct slot sl[MAXSLOT];
static int cur_slot = 0, slot_ign = 0;   /* slot_ign: the driver is talking about a slot >= MAXSLOT */

static int count_active(void) { int n = 0; for (int i = 0; i < MAXSLOT; i++) n += sl[i].active; return n; }

/* ---- resync with the kernel after lost events ----
   The evdev buffer of a 3.0 kernel holds 64 events (a few frames of a multitouch panel). When the reader is starved
   (single-core A9, an app starting / GC / dexopt), the kernel throws the oldest events away and sends SYN_DROPPED. A lost
   ABS_MT_TRACKING_ID -1 left a slot "active" here for good: every later tap then looked like a second finger (TWO: never
   forwarded), so Android saw no touches while the host gestures still worked (Voyage report, 2026-11-16).
   Fix: after SYN_DROPPED, and whenever a new finger lands while another one is believed down, ask the kernel which slots
   really hold a finger (EVIOCGMTSLOTS) and lift the ones it does not know, here and in Android. */
#ifndef EVIOCGMTSLOTS
#define EVIOCGMTSLOTS(len) _IOC(_IOC_READ, 'E', 0x0a, len)
#endif
static unsigned long resync_lifts = 0, drops = 0;
#define STALE_MS 3000
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

static void handle_frame(struct input_event *fr, int n);
/* after_drop: 1 = after SYN_DROPPED, 0 = a new finger lands over one believed down, 2 = the watchdog (no events for
   --stuck-ms): only what the kernel says counts, without kernel slot state nothing is lifted (a finger resting still) */
static void resync_slots(int after_drop)
{
    if (src_proto != PROTO_B || tfd < 0) return;
    struct { uint32_t code; int32_t v[MAXSLOT]; } q;
    memset(&q, 0, sizeof(q));
    q.code = ABS_MT_TRACKING_ID;
    if (ioctl(tfd, EVIOCGMTSLOTS(sizeof(q)), &q) < 0) {
        /* no kernel slot state (a driver without input_mt_init_slots): after lost events lift every finger -- a finger
           that is really still down only loses the rest of this touch; while a new finger lands, lift the fingers that
           sent nothing for STALE_MS (a resting finger on these panels still reports small changes) */
        static int warned = 0;
        if (!warned) { warned = 1; logf_("resync: EVIOCGMTSLOTS failed (%s), using the fallback", strerror(errno)); }
        if (after_drop == 2) return;
        long long now = now_ms();
        for (int i = 0; i < MAXSLOT; i++) q.v[i] = (sl[i].active && !after_drop && now - sl[i].tl < STALE_MS) ? 1 : -1;
    }
    if (after_drop == 1) {                  /* the slot the driver's next events refer to (it only sends ABS_MT_SLOT on change) */
        struct input_absinfo ai;
        if (ioctl(tfd, EVIOCGABS(ABS_MT_SLOT), &ai) == 0 && ai.value >= 0 && ai.value < MAXSLOT) cur_slot = hw_slot = ai.value;
    }
    struct input_event fr[2 * MAXSLOT + 4];
    int n = 0, lifted = 0, keep = cur_slot, left = 0;
    memset(fr, 0, sizeof(fr));
#define RS(t, c, v) do { fr[n].type = (t); fr[n].code = (c); fr[n].value = (v); n++; } while (0)
    for (int i = 0; i < MAXSLOT; i++) {
        if (sl[i].active && q.v[i] < 0) { RS(EV_ABS, ABS_MT_SLOT, i); RS(EV_ABS, ABS_MT_TRACKING_ID, -1); lifted++; }
        else if (sl[i].active) left++;
    }
    if (!lifted) return;
    RS(EV_ABS, ABS_MT_SLOT, keep);          /* Android's slot pointer must be where the driver's next events expect it */
    if (src_btn_touch && !left) RS(EV_KEY, BTN_TOUCH, 0);
    RS(EV_SYN, SYN_REPORT, 0);
#undef RS
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    for (int i = 0; i < n; i++) { fr[i].time.tv_sec = ts.tv_sec; fr[i].time.tv_usec = ts.tv_nsec / 1000; }
    resync_lifts += lifted;
    logf_("resync (%s): %d stuck finger(s) lifted, %d still down", after_drop == 1 ? "events dropped" : after_drop == 2 ? "watchdog" : "new touch", lifted, left);
    handle_frame(fr, n);
    cur_slot = keep;
}
/* does this frame put a finger down while we already think one is down? */
static int new_touch_over_active(const struct input_event *fr, int n)
{
    if (!count_active()) return 0;
    for (int i = 0; i < n; i++) if (fr[i].type == EV_ABS && fr[i].code == ABS_MT_TRACKING_ID && fr[i].value >= 0) return 1;
    return 0;
}

enum mode { IDLE, PENDING, PASS, EDGE_B, EDGE_T, TWO, SWALLOW, EDGE_FWD, EDGE_ARM };
static enum mode md = IDLE;
static enum mode edge_kind = IDLE;     /* which edge strip (EDGE_B / EDGE_T) an EDGE_FWD touch started in */
static int EDGE_HOLD_MS = 60;          /* how long a touch in the edge strip is held before Android gets it (a back swipe fits inside) */
static int EDGE_SWIPE = 80;            /* vertical distance that turns an edge touch into back / panel */
static struct input_event held[MAXEV];
static int nheld = 0;
static long long t_down = 0;
static int two_sx[2], two_sy[2], two_done = 0;
static int two_i[2] = { 0, 1 };        /* the slots of the two fingers: the first two active ones. fts_ts (PW6) has 2 slots,
                                          but cyttsp4 / ilitek / zforce have up to 10, and after a finger lifts the next one
                                          can land in any free slot, not only 0 and 1 */
static void two_begin(void)
{
    int n = 0;
    for (int i = 0; i < MAXSLOT && n < 2; i++) if (sl[i].active) two_i[n++] = i;
    if (n < 2) { two_i[0] = 0; two_i[1] = 1; }
    for (int k = 0; k < 2; k++) {
        struct slot *s = &sl[two_i[k]];
        two_sx[k] = s->sx >= 0 ? s->sx : s->x; two_sy[k] = s->sy >= 0 ? s->sy : s->y;
    }
}
static long long two_t0 = 0;
static int corner_fired = 0;

static unsigned long fwd_count = 0;
static void forward(const struct input_event *e)
{
    fwd_count++;
    uout(e);
}
/* Held frames go to Android as ONE touch-down at the finger's latest position (plus one lift frame when the finger already
   left), not replayed. uinput stamps every event with the time it is written, so a replay squeezed the first 40-60 ms of
   the touch into well under a millisecond: a tap with 3-6 px of finger jitter reached Android as a "move" of thousands of
   px/s, and readers that turn pages on a fling (多看 on KT6, field report 2026-11-21: "next page" went back one or two pages,
   or nothing happened) read the tap as a swipe in whatever direction the jitter went. The movement inside the hold is at
   most PASS_PX (otherwise the touch was released at once) or the edge-hold time, so nothing a gesture needs is lost.
   HELD_REPLAY=1 in --gconf restores the old replay. Falls back to the replay when the held frames are not one clean
   down (+ lift) per slot. */
static unsigned long n_compact = 0;
/* Tap dead zone (TAP_PX): after the held touch-down, a single finger's position changes are kept from Android until it
   has moved more than TAP_PX from the touch-down point; then Android gets the current position and everything after it
   as read. A finger that lifts inside the zone is a tap exactly where it went down. 多看's e-ink flip gesture
   (EInkReadingFlipGesture) treats any move of more than 20 px from the touch-down as a swipe: a tap whose contact drifted
   20+ px (the KT6 pt_mt panel reports the landing finger's centre moving as it flattens) became "swipe down" = previous
   page, or, when it came back under 20 px by the lift, nothing at all (the tap was already taken by the swipe). */
static int dz_on = 0, dz_slot = 0, dz_x = 0, dz_y = 0;
static unsigned long n_dz_taps = 0, n_dz_drops = 0;
/* at least 22 px: 多看 counts 20 raw px whatever the panel, so a scaled-down zone on a narrow panel (PW2 758 px) would be
   smaller than its swipe threshold */
static int dz_thr(void) { int t = TAP_PX * (PW ? PW : W) / 1272; return t < 22 ? 22 : t; }
static void mkev_(struct input_event *e, int type, int code, int value);
/* forward a frame (PASS / EDGE_FWD) through the dead zone */
static void dz_forward(const struct input_event *fr, int n)
{
    if (!dz_on) { for (int i = 0; i < n; i++) forward(&fr[i]); return; }
    struct slot *z = &sl[dz_slot];
    int thr = dz_thr();
    int lifted = !z->active;
    int leave = !lifted && (count_active() != 1 || abs(z->x - dz_x) > thr || abs(z->y - dz_y) > thr);
    int cs = dz_slot;
    for (int i = 0; i < n; i++) {
        const struct input_event *e = &fr[i];
        if (e->type == EV_ABS && e->code == ABS_MT_SLOT) cs = e->value;
        if (leave && e->type == EV_SYN && e->code == SYN_REPORT) {
            /* out of the zone: Android gets where the finger really is now (both axes: a frame may carry only one) */
            struct input_event x;
            mkev_(&x, EV_ABS, ABS_MT_SLOT, dz_slot); forward(&x);
            mkev_(&x, EV_ABS, ABS_MT_POSITION_X, z->x); forward(&x);
            mkev_(&x, EV_ABS, ABS_MT_POSITION_Y, z->y); forward(&x);
            if (src_proto == PROTO_B && cs != dz_slot) { mkev_(&x, EV_ABS, ABS_MT_SLOT, cs); forward(&x); }
        } else if (!leave && cs == dz_slot && e->type == EV_ABS && (e->code == ABS_MT_POSITION_X || e->code == ABS_MT_POSITION_Y)) {
            n_dz_drops++;
            continue;
        }
        forward(e);
    }
    if (lifted) { n_dz_taps++; dz_on = 0; if (TOUCH_DEBUG) logf_("tap dead zone: lifted inside (%d px)", thr); }
    else if (leave) { dz_on = 0; if (TOUCH_DEBUG) logf_("tap dead zone: left at %d,%d", z->x, z->y); }
}
static void mkev_(struct input_event *e, int type, int code, int value) { memset(e, 0, sizeof(*e)); e->type = type; e->code = code; e->value = value; }
static void flush_held(void)
{
    if (!nheld) return;
    if (TOUCH_DEBUG) logf_("flush %d after %lldms", nheld, now_ms() - t_down);
    enum { NA = ABS_MT_TOOL_Y + 1 };
    static int tid[MAXSLOT], last[MAXSLOT][NA - ABS_MT_SLOT], has[MAXSLOT][NA - ABS_MT_SLOT], lifted[MAXSLOT], used[MAXSLOT];
    int gabs[ABS_MT_SLOT], ghas[ABS_MT_SLOT], key[KEY_CNT > 0x2ff ? 0x2ff : KEY_CNT], khas[KEY_CNT > 0x2ff ? 0x2ff : KEY_CNT];
    int ok = !HELD_REPLAY, s = hw_slot >= 0 && hw_slot < MAXSLOT ? hw_slot : 0, nslots = 0;
    memset(used, 0, sizeof(used)); memset(lifted, 0, sizeof(lifted)); memset(has, 0, sizeof(has));
    memset(ghas, 0, sizeof(ghas)); memset(khas, 0, sizeof(khas));
    /* the slot pointer at the start of the hold: the first held frame names it (frames are normalised to name their slot) */
    for (int i = 0; ok && i < nheld; i++) {
        const struct input_event *e = &held[i];
        if (e->type == EV_ABS && e->code == ABS_MT_SLOT) { if (e->value < 0 || e->value >= MAXSLOT) ok = 0; else s = e->value; }
        else if (e->type == EV_ABS && e->code == ABS_MT_TRACKING_ID) {
            if (e->value >= 0) { if (used[s] || out_act[s]) ok = 0; used[s] = 1; tid[s] = e->value; nslots++; }
            else { if (!used[s] || lifted[s]) ok = 0; lifted[s] = 1; }
        } else if (e->type == EV_ABS && e->code > ABS_MT_SLOT && e->code < NA) {
            if (!used[s] || lifted[s]) { if (!lifted[s]) ok = 0; continue; }   /* a move after the lift: dropped */
            last[s][e->code - ABS_MT_SLOT] = e->value; has[s][e->code - ABS_MT_SLOT] = 1;
        } else if (e->type == EV_ABS && e->code < ABS_MT_SLOT) { gabs[e->code] = e->value; ghas[e->code] = 1; }
        else if (e->type == EV_KEY && e->code < (int)(sizeof(key) / sizeof(key[0]))) {
            if (!khas[e->code] || e->value) key[e->code] = e->value;   /* the down frame: a key that was pressed at all */
            khas[e->code] = 1;
        } else if (e->type == EV_SYN && e->code == SYN_REPORT) { }
        else if (e->type == EV_SYN || e->type == EV_MSC) { }
        else ok = 0;
    }
    /* a touch that already ended and moved more than a tap (a quick flick inside the edge hold): replayed as before,
       a flick must stay a flick */
    if (ok) {
        int slop = PASS_PX * (PW ? PW : W) / 1272; if (slop < 8) slop = 8;
        for (int i = 0; i < MAXSLOT && ok; i++) {
            if (!used[i] || !lifted[i]) continue;
            int x0 = -1, y0 = -1, cs = s, seen = 0;
            for (int k = 0; k < nheld; k++) {
                const struct input_event *e = &held[k];
                if (e->type == EV_ABS && e->code == ABS_MT_SLOT) cs = e->value;
                else if (cs == i && e->type == EV_ABS && e->code == ABS_MT_TRACKING_ID) { if (e->value >= 0) seen = 1; else seen = 0; }
                else if (cs == i && seen && e->type == EV_ABS && e->code == ABS_MT_POSITION_X) { if (x0 < 0) x0 = e->value; }
                else if (cs == i && seen && e->type == EV_ABS && e->code == ABS_MT_POSITION_Y) { if (y0 < 0) y0 = e->value; }
            }
            int lx = has[i][ABS_MT_POSITION_X - ABS_MT_SLOT] ? last[i][ABS_MT_POSITION_X - ABS_MT_SLOT] : x0;
            int ly = has[i][ABS_MT_POSITION_Y - ABS_MT_SLOT] ? last[i][ABS_MT_POSITION_Y - ABS_MT_SLOT] : y0;
            if (x0 >= 0 && y0 >= 0 && (abs(lx - x0) > slop || abs(ly - y0) > slop)) ok = 0;
        }
    }
    if (!ok || !nslots) {
        for (int i = 0; i < nheld; i++) forward(&held[i]);
        nheld = 0;
        return;
    }
    n_compact++;
    struct input_event e;
    int anylift = 0;
    for (int i = 0; i < MAXSLOT; i++) {
        if (!used[i]) continue;
        anylift |= lifted[i];
        mkev_(&e, EV_ABS, ABS_MT_SLOT, i); forward(&e);
        mkev_(&e, EV_ABS, ABS_MT_TRACKING_ID, tid[i]); forward(&e);
        for (int c = ABS_MT_SLOT + 1; c < NA; c++)
            if (c != ABS_MT_TRACKING_ID && has[i][c - ABS_MT_SLOT]) { mkev_(&e, EV_ABS, c, last[i][c - ABS_MT_SLOT]); forward(&e); }
    }
    for (int c = 0; c < ABS_MT_SLOT; c++) if (ghas[c]) { mkev_(&e, EV_ABS, c, gabs[c]); forward(&e); }
    for (int c = 0; c < (int)(sizeof(key) / sizeof(key[0])); c++) if (khas[c] && key[c]) { mkev_(&e, EV_KEY, c, key[c]); forward(&e); }
    if (src_proto == PROTO_B && !anylift) { mkev_(&e, EV_ABS, ABS_MT_SLOT, s); forward(&e); }
    mkev_(&e, EV_SYN, SYN_REPORT, 0); forward(&e);
    if (anylift) {
        for (int i = 0; i < MAXSLOT; i++) {
            if (!lifted[i]) continue;
            mkev_(&e, EV_ABS, ABS_MT_SLOT, i); forward(&e);
            mkev_(&e, EV_ABS, ABS_MT_TRACKING_ID, -1); forward(&e);
        }
        for (int c = 0; c < (int)(sizeof(key) / sizeof(key[0])); c++)
            if (khas[c]) {   /* the keys' last values (BTN_TOUCH 0 after the lift) */
                int v = 0;
                for (int i = nheld - 1; i >= 0; i--) if (held[i].type == EV_KEY && held[i].code == c) { v = held[i].value; break; }
                if (v != key[c] || !v) { mkev_(&e, EV_KEY, c, v); forward(&e); }
            }
        if (src_proto == PROTO_B) { mkev_(&e, EV_ABS, ABS_MT_SLOT, s); forward(&e); }
        mkev_(&e, EV_SYN, SYN_REPORT, 0); forward(&e);
    }
    if (!anylift && nslots == 1 && TAP_PX > 0) {
        for (int i = 0; i < MAXSLOT; i++) if (used[i]) {
            dz_slot = i;
            dz_x = has[i][ABS_MT_POSITION_X - ABS_MT_SLOT] ? last[i][ABS_MT_POSITION_X - ABS_MT_SLOT] : sl[i].x;
            dz_y = has[i][ABS_MT_POSITION_Y - ABS_MT_SLOT] ? last[i][ABS_MT_POSITION_Y - ABS_MT_SLOT] : sl[i].y;
            dz_on = 1;
        }
    }
    if (TOUCH_DEBUG) logf_("flush: %d held events -> one touch-down%s", nheld, anylift ? " + lift" : "");
    nheld = 0;
}
/* one command line to the display bridge (never blocks: no reader = dropped).
   Keeps the FIFO fd open across touches so every TOUCH / SCROLL / DIR avoids open+close syscalls;
   reopens transparently if the display bridge restarts (EPIPE / EBADF). */
static const char *EPDF = "/dev/shm/veradroid/kfb/epd.fifo";   /* --epd-fifo */
static int epd_fd = -1;
static void epd_cmd(const char *line)
{
    size_t len = strlen(line);
    if (epd_fd < 0) epd_fd = open(EPDF, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (epd_fd < 0) return;
    if (write(epd_fd, line, len) < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            close(epd_fd);
            epd_fd = open(EPDF, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
            if (epd_fd >= 0) {
                if (write(epd_fd, line, len) < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
                    close(epd_fd);
                    epd_fd = -1;
                }
            }
        }
    }
}
/* 给显示桥一个方向提示（下一次大面积变化用哪边滑入），不强制动画 */
static void epd_dir(int dir)
{
    char b[32];
    snprintf(b, sizeof(b), "DIR %d\n", dir);
    epd_cmd(b);
}

/* ---- touch boost (see the header) ---- */
static long long boost_until = 0;          /* 0 = not boosted (or finger still down) */
static long long boost_t0 = 0;             /* when boost_begin raised scaling_min_freq */
#define BOOST_HOLD_MAX_MS 1500             /* safety cap: never lock CPU at max freq if a finger rests or a lift is missed */
static int boost_on = 0;
static int prev_any = 0;
static char boost_orig[24];
static unsigned long n_boost = 0;
static char CPUFREQ_MIN[160], CPUFREQ_MAX[160];   /* empty = no --cpufreq: boost off */
static int sysfs_read(const char *path, char *buf, int len)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int n = read(fd, buf, len - 1);
    close(fd);
    if (n <= 0) return -1;
    buf[n] = 0; buf[strcspn(buf, "\r\n")] = 0;
    return n;
}
static int sysfs_write(const char *path, const char *v)
{
    int fd = open(path, O_WRONLY | O_TRUNC);   /* O_TRUNC: no effect on sysfs, needed for the plain files of the test */
    if (fd < 0) return -1;
    int r = write(fd, v, strlen(v));
    close(fd);
    return r < 0 ? -1 : 0;
}
static void boost_begin(void)
{
    if (BOOST_MS <= 0 || boost_on || !CPUFREQ_MIN[0]) return;
    char mx[24];
    if (sysfs_read(CPUFREQ_MIN, boost_orig, sizeof(boost_orig)) < 0) return;
    if (BOOST_KHZ > 0) snprintf(mx, sizeof(mx), "%d", BOOST_KHZ);
    else if (sysfs_read(CPUFREQ_MAX, mx, sizeof(mx)) < 0) return;
    if (atol(mx) <= atol(boost_orig)) return;       /* already there (or capped below the minimum: powersave) */
    if (sysfs_write(CPUFREQ_MIN, mx) == 0) { boost_on = 1; boost_t0 = now_ms(); n_boost++; }
}
static void boost_end(void)
{
    if (boost_on && sysfs_write(CPUFREQ_MIN, boost_orig) < 0) logf_("boost: could not put scaling_min_freq back to %s", boost_orig);
    boost_on = 0; boost_until = 0; boost_t0 = 0;
}

/* ---- idle freeze: thaw on touch (see the header) ---- */
static const char *IDLE_MARK = NULL, *IDLE_FZ = NULL, *SLIRP_PID = NULL, *TOUCH_STAMP = NULL;
static unsigned long n_thaw = 0;
static void idle_thaw(void)
{
    if (TOUCH_STAMP) { int fd = open(TOUCH_STAMP, O_WRONLY | O_CREAT, 0644); if (fd >= 0) { futimens(fd, NULL); close(fd); } }
    if (!IDLE_MARK || !IDLE_FZ || access(IDLE_MARK, F_OK) != 0) return;
    struct timespec a, b;
    clock_gettime(CLOCK_MONOTONIC, &a);
    if (sysfs_write(IDLE_FZ, "THAWED") < 0) logf_("idle thaw: cannot write %s: %s", IDLE_FZ, strerror(errno));
    if (SLIRP_PID) {
        char pb[24];
        if (sysfs_read(SLIRP_PID, pb, sizeof(pb)) > 0 && atoi(pb) > 1) kill(atoi(pb), SIGCONT);
    }
    unlink(IDLE_MARK);
    clock_gettime(CLOCK_MONOTONIC, &b);
    n_thaw++;
    logf_("idle thaw in %ld us", (b.tv_sec - a.tv_sec) * 1000000L + (b.tv_nsec - a.tv_nsec) / 1000);
}

/* ---- scroll hints (see the header) ---- */
static int scroll_sent = 0;
static unsigned long n_scroll = 0;
static int out_any(void) { for (int i = 0; i < MAXSLOT; i++) if (out_act[i]) return 1; return 0; }
/* lift contacts in Android: every slot Android was given (all = 1), or only those the panel no longer has (all = 0).
   By what was written to the virtual touch screen (out_act), never by the panel's state (sl): those two differed when
   a gesture had swallowed a slot change, and the cancel then lifted slots Android did not have while the ones it had
   stayed down for good (seen on a KT5: two contacts stuck from an edge swipe that opened the panel). Afterwards
   Android's slot pointer is put back where the panel's next events expect it. Returns how many were lifted. */
static int release_android(int all, const char *why)
{
    int n = 0;
    for (int i = 0; i < MAXSLOT; i++)
        if (out_act[i] && (all || !sl[i].active)) {
            emit(ufd, EV_ABS, ABS_MT_SLOT, i);
            emit(ufd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            n++;
        }
    if (!n && !(all && out_btn)) return 0;
    if (src_proto == PROTO_B) emit(ufd, EV_ABS, ABS_MT_SLOT, hw_slot);
    if (!out_any()) emit(ufd, EV_KEY, BTN_TOUCH, 0);
    emit(ufd, EV_SYN, SYN_REPORT, 0);
    n_released += n;
    if (n && why) logf_("release: %d contact(s) lifted in Android (%s)", n, why);
    return n;
}
/* tell Android every finger is up (used when a gesture takes over after events were already forwarded) */
static void cancel_to_android(void) { release_android(1, NULL); }
/* after every frame: in the modes that hold or swallow a touch Android must have no contact at all; in the others (the
   touch goes to Android) only contacts the panel still has */
static void android_check(void)
{
    if (!out_any()) return;
    if (md == IDLE || md == PASS || md == EDGE_FWD) release_android(0, "the panel has no such contact");
    else release_android(1, "touch held or taken by a gesture");
}

static int in_corner(int x, int y, const char **name)
{
    int l = x < CORNER, r = x > W - CORNER, t = y < CORNER, b = y > H - CORNER;
    if (t && l) *name = "tl";
    if (t && r) *name = "tr";
    if (b && l) *name = "bl";
    if (b && r) *name = "br";
    if (!(t || b) || !(l || r)) return 0;
    /* only corners that have an action (G_CORNERS from --gconf): a long press in any other corner stays Android's */
    return !strcmp(G_CORNERS, "all") || strstr(G_CORNERS, *name) != NULL;
}

/* ---- bottom edge in thirds (G_EDGE_THIRDS, see the header) ----
   eb_third: which third this touch started in (0 left, 1 middle, 2 right; -1 = plain bottom-edge back for this touch).
   EDGE_ARM: the swipe up was seen and the touch is ours; lifting fires the third's action, holding still for G_EB_HOLD_MS
   fires G_EB_HOLD. Moving more than the slop restarts the hold clock (a slow drag is not a hold). */
static int eb_third = -1, eb_slot = 0, arm_x, arm_y;
static long long arm_t = 0;
static int active_slot(void);
static int eb_none(int i) { return !strcmp(G_EB[i], "none"); }
static int eb_slop(void) { int s = 30 * (PW ? PW : W) / 1272; return s < 8 ? 8 : s; }
static void eb_fire(int i)
{
    static const char *const nm[4] = { "left", "middle", "right", "hold" };
    if (eb_none(i)) { logf_("edge thirds: %s -> none", nm[i]); return; }
    logf_("edge thirds: %s -> %s", nm[i], G_EB[i]);
    if (!strcmp(G_EB[i], "back")) back_key();   /* the key straight away, as the plain bottom-edge back */
    else action("eb", G_EB[i]);
}
/* the bottom-edge swipe was recognised (the touch is ours from now on): back as before, or the thirds */
static void eb_swipe(int lifted)
{
    if (eb_third < 0) { logf_("edge swipe up -> back"); back_key(); return; }
    if (lifted || eb_none(3)) { eb_fire(eb_third); return; }   /* no hold action: no reason to wait for the lift */
    eb_slot = active_slot();
    arm_x = sl[eb_slot].x; arm_y = sl[eb_slot].y; arm_t = now_ms();
    md = EDGE_ARM;
}
/* a frame (or the idle tick, frame = 0) while armed */
static void eb_armed(int active, int frame)
{
    if (frame && (active == 0 || !sl[eb_slot].active)) { eb_fire(eb_third); md = active ? SWALLOW : IDLE; return; }
    if (active == 0) return;
    if (frame && (abs(sl[eb_slot].x - arm_x) > eb_slop() || abs(sl[eb_slot].y - arm_y) > eb_slop())) {
        arm_x = sl[eb_slot].x; arm_y = sl[eb_slot].y; arm_t = now_ms();
        return;
    }
    if (now_ms() - arm_t >= G_EB_HOLD_MS) { eb_fire(3); md = SWALLOW; }
}

static void two_finger_end(void)
{
    /* classify by the movement of the first two fingers */
    long long dur = now_ms() - two_t0;
    int mv[2][2];
    int n = 0;
    for (int i = 0; i < MAXSLOT && n < 2; i++) {
        (void)i;
    }
    (void)mv;
    int dx = sl[two_i[0]].x - two_sx[0], dy = sl[two_i[0]].y - two_sy[0];
    /* use the finger that moved most */
    int dx1 = sl[two_i[1]].x - two_sx[1], dy1 = sl[two_i[1]].y - two_sy[1];
    if (abs(dx1) + abs(dy1) > abs(dx) + abs(dy)) { dx = dx1; dy = dy1; }
    int mid = (two_sx[0] + two_sx[1]) / 2;
    if (two_done) return;
    if (abs(dx) < 60 && abs(dy) < 60) {
        if (dur >= 2000) { if (G_TWO_LONG) action("invert", NULL); }
        else if (dur < 500) { if (G_TWO_TAP) action("clear", NULL); }
    } else if (abs(dx) > abs(dy) * 2 && abs(dx) > 200) {
        if (!G_TWO_BACK) { two_done = 1; return; }
        logf_("two-finger horizontal swipe -> back");
        back_key();
    } else if (abs(dy) > abs(dx) * 2 && abs(dy) > 120 && G_TWO_SLIDE) {
        const char *what = mid < W / 2 ? "light" : "warmth";
        action(what, dy < 0 ? "+" : "-");
    }
    two_done = 1;
}

/* source ranges of the position axes: drivers report one past the advertised maximum at an edge (fts_ts / ili211x take
   the panel size as the maximum; cyttsp4 mirrors an axis as max - v, giving 1..max+1 on PW3 / Oasis 1), and Android 7
   drops a touch-down outside the range. Unrotated events are clamped to the source range, rotated ones in rot_event. */
static int sx_min = 0, sx_max = -1, sy_min = 0, sy_max = -1;

/* panel coordinates -> logical ones. A rotation by a multiple of 90 degrees maps each panel axis onto one logical axis,
   possibly flipped, so every event is rewritten on its own (code and value), no state is needed. */
static void rot_event(struct input_event *e)
{
    if (!ROT || e->type != EV_ABS) return;
    int isx = e->code == ABS_MT_POSITION_X || e->code == ABS_X;
    int isy = e->code == ABS_MT_POSITION_Y || e->code == ABS_Y;
    if (!isx && !isy) return;
    int mt = e->code == ABS_MT_POSITION_X || e->code == ABS_MT_POSITION_Y;
    int cx = mt ? ABS_MT_POSITION_X : ABS_X, cy = mt ? ABS_MT_POSITION_Y : ABS_Y;
    int v = e->value;
    if (!ROT_ON && ROT == 180) {
        if (isx && sx_max > sx_min && (sx_min != 0 || (sx_max != PW - 1 && sx_max != PW && sx_max != VW - 1 && sx_max != VW))) {
            e->value = sx_max + sx_min - v;
            if (e->value < sx_min) e->value = sx_min;
            else if (e->value > sx_max) e->value = sx_max;
            return;
        }
        if (isy && sy_max > sy_min && (sy_min != 0 || (sy_max != PH - 1 && sy_max != PH))) {
            e->value = sy_max + sy_min - v;
            if (e->value < sy_min) e->value = sy_min;
            else if (e->value > sy_max) e->value = sy_max;
            return;
        }
    }
    switch (ROT) {
    case 90:  if (isx) { e->code = cy; e->value = VW - 1 - v; } else { e->code = cx; e->value = v; } break;
    case 180: if (isx) e->value = VW - 1 - v; else e->value = PH - 1 - v; break;
    case 270: if (isx) { e->code = cy; e->value = v; } else { e->code = cx; e->value = PH - 1 - v; } break;
    }
    /* the drivers report one past the last pixel (fts_ts / ili211x: 0..1272 on a 1272 panel, display-coords in the dts),
       which turned into -1 here; keep every value inside the logical picture */
    int lim = (e->code == ABS_MT_POSITION_X || e->code == ABS_X) ? W - 1 : H - 1;
    if (e->value < 0) e->value = 0;
    else if (e->value > lim) e->value = lim;
}
static void clamp_event(struct input_event *e)
{
    if (e->type != EV_ABS) return;
    if ((e->code == ABS_MT_POSITION_X || e->code == ABS_X) && sx_max > sx_min) {
        if (e->value < sx_min) e->value = sx_min; else if (e->value > sx_max) e->value = sx_max;
    } else if ((e->code == ABS_MT_POSITION_Y || e->code == ABS_Y) && sy_max > sy_min) {
        if (e->value < sy_min) e->value = sy_min; else if (e->value > sy_max) e->value = sy_max;
    }
}
static void rot_frame(struct input_event *fr, int n)
{
    if (md == IDLE) gconf_load();          /* a live 0 <-> 180 switch takes effect between touches (only stats the file) */
    if (!ROT) { for (int i = 0; i < n; i++) clamp_event(&fr[i]); return; }
    for (int i = 0; i < n; i++) rot_event(&fr[i]);
}

/* PENDING (one finger, inside the two-finger window): did it move more than PASS_PX (scaled to the panel width) from
   where it landed? Then it is a drag and is released to Android at once. 0 = always wait the whole window (old way). */
static int active_slot(void)
{
    if (cur_slot >= 0 && cur_slot < MAXSLOT && sl[cur_slot].active) return cur_slot;
    for (int i = 0; i < MAXSLOT; i++) if (sl[i].active) return i;
    return cur_slot;
}

static int pending_moved(void)
{
    if (PASS_PX <= 0 || count_active() != 1) return 0;
    const struct slot *s = &sl[active_slot()];
    if (!s->active || s->sx < 0 || s->sy < 0) return 0;
    int thr = PASS_PX * (PW ? PW : W) / 1272;
    if (thr < 4) thr = 4;
    return abs(s->x - s->sx) > thr || abs(s->y - s->sy) > thr;
}

static void handle_frame(struct input_event *fr, int n)
{
    /* update state from the frame's events */
    int down_edge = 0;
    for (int i = 0; i < n; i++) {
        struct input_event *e = &fr[i];
        if (e->type == EV_ABS) {
            if (e->code == ABS_MT_SLOT) { slot_ign = !(e->value >= 0 && e->value < MAXSLOT); if (!slot_ign) cur_slot = e->value; }
            else if (slot_ign) continue;   /* a slot we do not track: Android still gets the events, the gestures ignore it */
            else if ((sl[cur_slot].tl = now_ms()), e->code == ABS_MT_TRACKING_ID) {
                if (e->value >= 0) { sl[cur_slot].active = 1; sl[cur_slot].t0 = now_ms(); sl[cur_slot].sx = sl[cur_slot].sy = -1; }
                else sl[cur_slot].active = 0;
            } else if (e->code == ABS_MT_POSITION_X) { sl[cur_slot].x = e->value; if (sl[cur_slot].sx < 0) sl[cur_slot].sx = e->value; }
            else if (e->code == ABS_MT_POSITION_Y) { sl[cur_slot].y = e->value; if (sl[cur_slot].sy < 0) sl[cur_slot].sy = e->value; }
        }
    }
    int active = count_active();
    (void)down_edge;
    if (active && !prev_any) {        /* first finger down: boost before anything else (the gconf may switch it off) */
        idle_thaw();                  /* the container first: the frame below goes to its input reader */
        gconf_load();
        boost_begin();
        boost_until = 0;              /* held while a finger is down */
        scroll_sent = 0;
    } else if (!active && prev_any) {
        if (boost_on) boost_until = now_ms() + BOOST_MS;
        epd_cmd("TOUCH\n");          /* an input event: the bridge counts only page turns that follow one (FLIPINPUT) */
    }
    prev_any = active != 0;
    /* Panels without BTN_TOUCH (some 5.19.5 PW6, KT5 / KT6 pt_mt): uout() sends it with the frames Android gets. It used
       to be written here for every touch, also for touches a gesture held or swallowed (a BTN_TOUCH 1 with no contact). */
    if (TOUCH_DEBUG) {
        static int prev_active = 0;
        if (active != prev_active) {
            logf_("touch active %d->%d slot%d x=%d y=%d start=%d,%d", prev_active, active, cur_slot, sl[cur_slot].x, sl[cur_slot].y, sl[cur_slot].sx, sl[cur_slot].sy);
            prev_active = active;
        }
    }

    int gest = GESTURES && !(FLAGF && access(FLAGF, F_OK) == 0);

    static int poke_due = 0;
    if (active == 0 && poke_due) { poke_due = 0; action("poke", NULL); }

    switch (md) {
    case IDLE:
        dz_on = 0;
        if (active >= 1) {
            int sx = sl[cur_slot].sx >= 0 ? sl[cur_slot].sx : sl[cur_slot].x, sy = sl[cur_slot].sy >= 0 ? sl[cur_slot].sy : sl[cur_slot].y;
            gconf_load();
            /* bottom-edge back: its own switch, and off while an excluded app (WeRead: swipe-up = toolbar) is in front */
            int eb = EDGE_BACK && G_EDGE_BACK && active == 1 && sy > H - EDGE && !back_excluded();
            /* gestures off: everything goes to Android except a swipe that starts in the bottom edge (back stays) */
            if (!gest && !eb) { for (int i = 0; i < n; i++) forward(&fr[i]); break; }
            t_down = now_ms();
            for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
            /* keep the Kindle from sleeping while the screen is being used; defer fork/exec until lift so touch-down has 0 delay */
            { static long long last_poke = 0; if (t_down - last_poke > 20000) { last_poke = t_down; poke_due = 1; } }
            corner_fired = 0;
            two_done = 0;
            eb_third = -1;
            if (eb && gest && G_EDGE_THIRDS) {
                int t = sx < W / 3 ? 0 : sx >= W - W / 3 ? 2 : 1;
                if (eb_none(t) && eb_none(3)) eb = 0;   /* nothing bound here: an ordinary touch for Android */
                else eb_third = t;
            }
            if (active >= 2 && !two_any()) {    /* every two-finger gesture off: Android gets them (pinch) */
                flush_held(); md = PASS;
            } else if (active >= 2) {       /* both fingers landed in the same frame */
                nheld = 0;
                md = TWO; two_t0 = now_ms();
                two_begin();
            } else if (eb) md = EDGE_B;
            else if (gest && G_EDGE_PANEL && sy < (EDGE_TOP >= 0 ? EDGE_TOP : EDGE)) md = EDGE_T;
            else if (!two_any()) {          /* all two-finger gestures off: zero-wait pass-through to Android */
                flush_held(); md = PASS;
            } else md = PENDING;
            (void)sx;
        } else {
            for (int i = 0; i < n; i++) forward(&fr[i]);
        }
        break;
    case PENDING:
        if (active >= 2 && !two_any()) {
            for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
            flush_held(); md = PASS;
        } else if (active >= 2) {
            nheld = 0;                      /* two fingers: swallow what we held */
            md = TWO; two_t0 = now_ms();
            two_begin();
        } else if (active == 0) {
            for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
            flush_held(); md = IDLE;
        } else if (now_ms() - t_down >= WINDOW_MS || pending_moved()) {
            /* the window ran out, or the finger already moved past the touch slop (a drag, not the first finger of a
               two-finger gesture): Android gets it now instead of after the whole window */
            for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
            flush_held(); md = PASS;
        } else {
            for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
        }
        break;
    case PASS: {
        dz_forward(fr, n);
        if (active >= 2) {                  /* a second finger arriving late: let Android have it (pinch etc.) */
        }
        if (active == 0) {
            /* remember the gesture direction for the next page change: horizontal swipe -> left/right,
               vertical swipe -> up/down, short tap -> which half of the screen */
            if (!corner_fired && sl[cur_slot].sx >= 0 && now_ms() - t_down < 800) {
                int dx = sl[cur_slot].x - sl[cur_slot].sx;
                int dy = sl[cur_slot].y - sl[cur_slot].sy;
                if (abs(dx) > 80 && abs(dx) > abs(dy)) epd_dir(dx < 0 ? 2 : 3);
                else if (abs(dy) > 80 && abs(dy) > abs(dx)) epd_dir(dy < 0 ? 1 : 0);
                else if (abs(dx) < 40 && abs(dy) < 40) epd_dir(sl[cur_slot].sx > W / 2 ? 2 : 3);
            }
            md = IDLE;
            break;
        }
        const char *cn;
        if (!corner_fired && CORNER_MS > 0 && active == 1 && now_ms() - t_down >= CORNER_MS) {
            int as = active_slot();
            int sx = sl[as].sx >= 0 ? sl[as].sx : sl[as].x, sy = sl[as].sy >= 0 ? sl[as].sy : sl[as].y;
            if (sl[as].active && in_corner(sl[as].x, sl[as].y, &cn)
                && abs(sl[as].x - sx) < 40 && abs(sl[as].y - sy) < 40) {
                corner_fired = 1;
                cancel_to_android();
                action(cn, NULL);
                md = SWALLOW;
            }
        }
        break;
    }
    case EDGE_B:
    case EDGE_T: {
        /* a touch that starts in the top/bottom edge strip is held for EDGE_HOLD_MS: if it turns into the edge swipe (back / panel)
           it is swallowed, otherwise Android gets it -- a plain tap there must still work */
        if (active >= 2 && !corner_fired) {
            if (gest && two_any()) {
                nheld = 0;
                md = TWO; two_t0 = now_ms();
                two_begin();
            } else {
                for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
                flush_held();
                md = PASS;
            }
            break;
        }
        int as = active == 1 ? active_slot() : cur_slot;
        int sx = sl[as].sx >= 0 ? sl[as].sx : sl[as].x;
        int sy = sl[as].sy >= 0 ? sl[as].sy : sl[as].y;
        int dx = sl[as].x - sx;
        int dy = sl[as].y - sy;
        int swipe = !corner_fired && ((md == EDGE_B && dy < -EDGE_SWIPE) || (md == EDGE_T && dy > EDGE_SWIPE)) && abs(dy) > abs(dx);
        if (active == 0) {
            if (swipe) {                    /* a fast flick that ended before the swipe was seen while held */
                corner_fired = 1;
                if (md == EDGE_B) eb_swipe(1); else action("panel", NULL);
            } else if (!corner_fired) {     /* a tap: deliver the held press and this release to Android */
                for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
                flush_held();
            }
            md = IDLE; nheld = 0;
        } else if (swipe) {
            corner_fired = 1;               /* fire as soon as the swipe is long enough; the held press never reaches Android */
            nheld = 0;
            if (md == EDGE_B) eb_swipe(0);  /* thirds with a hold action: armed (EDGE_ARM) until lift / hold */
            else action("panel", NULL);
        } else if (!corner_fired) {
            for (int i = 0; i < n && nheld < MAXEV; i++) held[nheld++] = fr[i];
            if (now_ms() - t_down >= EDGE_HOLD_MS || nheld >= MAXEV - 8) {
                flush_held();              /* not a gesture (yet): Android gets the press, the rest of the touch is forwarded */
                edge_kind = md;
                md = EDGE_FWD;
            }
        }
        break;
    }
    case EDGE_FWD: {
        int as = active == 1 ? active_slot() : cur_slot;
        int sx = sl[as].sx >= 0 ? sl[as].sx : sl[as].x;
        int sy = sl[as].sy >= 0 ? sl[as].sy : sl[as].y;
        int dx = sl[as].x - sx;
        int dy = sl[as].y - sy;
        dz_forward(fr, n);
        if (active == 0) { md = IDLE; break; }
        if (!corner_fired && ((edge_kind == EDGE_B && dy < -EDGE_SWIPE) || (edge_kind == EDGE_T && dy > EDGE_SWIPE)) && abs(dy) > abs(dx)) {
            corner_fired = 1;               /* the swipe turned out to be a gesture after all: take the touch back from Android */
            cancel_to_android();
            md = SWALLOW;
            if (edge_kind == EDGE_B) eb_swipe(0);   /* may arm (md = EDGE_ARM) */
            else action("panel", NULL);
        } else {
            /* corner long press also for touches that started inside the top/bottom edge strip (they are in EDGE_FWD,
               not PASS): same rule as below -- one finger, held >= CORNER_MS, moved < 40 px */
            const char *ecn;
            if (!corner_fired && CORNER_MS > 0 && active == 1 && sl[as].active && now_ms() - t_down >= CORNER_MS
                && in_corner(sl[as].x, sl[as].y, &ecn) && abs(dx) < 40 && abs(dy) < 40) {
                corner_fired = 1;
                cancel_to_android();
                action(ecn, NULL);
                md = SWALLOW;
            }
        }
        break;
    }
    case TWO:
        if (active == 0) { two_finger_end(); md = IDLE; nheld = 0; }
        else if (!two_done && G_TWO_LONG && active >= 2 && sl[two_i[0]].active && sl[two_i[1]].active
            && now_ms() - two_t0 >= 2000
            && abs(sl[two_i[0]].x - two_sx[0]) < 60 && abs(sl[two_i[0]].y - two_sy[0]) < 60
            && abs(sl[two_i[1]].x - two_sx[1]) < 60 && abs(sl[two_i[1]].y - two_sy[1]) < 60) {
            action("invert", NULL); two_done = 1;       /* long press fires while both fingers are still down and still */
        }
        break;
    case SWALLOW:
        if (active == 0) md = IDLE;
        break;
    case EDGE_ARM:
        eb_armed(active, 1);
        break;
    }
    /* scroll hint: one finger that Android is getting moved past the slop */
    if (!scroll_sent && !dz_on && SCROLL_PX > 0 && active == 1 && (md == PASS || md == EDGE_FWD || md == IDLE)) {
        int sx = sl[cur_slot].sx, sy = sl[cur_slot].sy;
        int thr = SCROLL_PX * PW / 1272;
        if (thr < 4) thr = 4;
        if (sx >= 0 && sy >= 0 && (abs(sl[cur_slot].x - sx) > thr || abs(sl[cur_slot].y - sy) > thr)) {
            epd_cmd("SCROLL 1\n"); scroll_sent = 1; n_scroll++;
            if (TOUCH_DEBUG) logf_("scroll begin (%d,%d -> %d,%d)", sx, sy, sl[cur_slot].x, sl[cur_slot].y);
        }
    }
    if (scroll_sent && (active == 0 || active >= 2 || md == SWALLOW || md == TWO || md == EDGE_ARM)) { epd_cmd("SCROLL 0\n"); scroll_sent = active ? 2 : 0; }
    android_check();
}

/* ---- protocol A / single touch -> slots (see the header). Only used when the source is not protocol B. ---- */
struct acon { int x, y, major, pres, tid; };   /* one contact of the frame being read; -1 = not sent */
static struct aslot { int used, tid, x, y, major, pres, id; } as_[MAXSLOT];
static int a_next_id = 1;
static int st_btn = 0, st_x = -1, st_y = -1, st_pres = -1;   /* single touch: the last values the driver sent */

/* turn one source frame (ending in SYN_REPORT) into protocol-B events in out[]; returns the count (0: nothing changed) */
static int normalize_frame(const struct input_event *in, int n, struct input_event *out, int max)
{
    struct acon c[MAXSLOT], cur = { -1, -1, -1, -1, -1 };
    int nc = 0, btn_up = 0, nout = 0, any = 0;
    struct input_event other[64];
    int nother = 0;
    struct input_event tmpl;                         /* the SYN_REPORT: every generated event carries its time stamp */
    memset(&tmpl, 0, sizeof(tmpl));
    if (n) tmpl = in[n - 1];
    for (int i = 0; i < n; i++) {
        const struct input_event *e = &in[i];
        if (e->type == EV_SYN && e->code == SYN_MT_REPORT) {
            if (any && nc < MAXSLOT) c[nc++] = cur;
            cur = (struct acon){ -1, -1, -1, -1, -1 }; any = 0;
        } else if (e->type == EV_SYN) {
            continue;                                  /* SYN_REPORT: added at the end; SYN_DROPPED: ignore */
        } else if (e->type == EV_ABS && src_proto == PROTO_A && e->code >= ABS_MT_TOUCH_MAJOR) {
            any = 1;
            if (e->code == ABS_MT_POSITION_X) cur.x = e->value;
            else if (e->code == ABS_MT_POSITION_Y) cur.y = e->value;
            else if (e->code == ABS_MT_TOUCH_MAJOR) cur.major = e->value;
            else if (e->code == ABS_MT_PRESSURE) cur.pres = e->value;
            else if (e->code == ABS_MT_TRACKING_ID) cur.tid = e->value;
        } else if (e->type == EV_ABS && (e->code == ABS_X || e->code == ABS_Y || e->code == ABS_PRESSURE)) {
            if (src_proto == PROTO_ST) {
                if (e->code == ABS_X) st_x = e->value; else if (e->code == ABS_Y) st_y = e->value; else st_pres = e->value;
            }                                          /* A: the driver's single-touch copy, the slots replace it */
        } else {
            if (e->type == EV_KEY && e->code == BTN_TOUCH) {
                if (!e->value) btn_up = 1;
                if (src_proto == PROTO_ST) st_btn = e->value;
            }
            if (nother < 64) other[nother++] = *e;
        }
    }
    if (any && nc < MAXSLOT) c[nc++] = cur;            /* last contact without its SYN_MT_REPORT */
    if (src_proto == PROTO_ST) {
        nc = 0;
        int down = src_btn_touch ? st_btn : st_pres > 0;
        if (down && st_x >= 0 && st_y >= 0) { c[0] = (struct acon){ st_x, st_y, -1, st_pres, 0 }; nc = 1; }
    } else {
        int k = 0;                                     /* pressure 0 = lifted; BTN_TOUCH 0 = everything lifted */
        for (int i = 0; i < nc; i++) if (!btn_up && c[i].pres != 0) c[k++] = c[i];
        nc = k;
    }
    /* match contacts to the slots of the previous frame */
    int cslot[MAXSLOT], taken[MAXSLOT];
    memset(taken, 0, sizeof(taken));
    for (int i = 0; i < nc; i++) cslot[i] = -1;
    for (int i = 0; i < nc; i++)                       /* by the driver's tracking id */
        if (c[i].tid >= 0)
            for (int s = 0; s < MAXSLOT; s++)
                if (as_[s].used && !taken[s] && as_[s].tid == c[i].tid) { cslot[i] = s; taken[s] = 1; break; }
    long lim = (long)(W > H ? W : H) / 3; lim *= lim;
    for (;;) {                                         /* the rest: nearest neighbour, closest pair first */
        long best = -1; int bi = -1, bs = -1;
        for (int i = 0; i < nc; i++) {
            if (cslot[i] >= 0) continue;
            int x = c[i].x, y = c[i].y;
            for (int s = 0; s < MAXSLOT; s++) {
                if (!as_[s].used || taken[s]) continue;
                if (c[i].tid >= 0 && as_[s].tid >= 0) continue;   /* both have ids and they differ: not the same finger */
                long dx = (x >= 0 ? x : as_[s].x) - as_[s].x, dy = (y >= 0 ? y : as_[s].y) - as_[s].y, d = dx * dx + dy * dy;
                if (d <= lim && (best < 0 || d < best)) { best = d; bi = i; bs = s; }
            }
        }
        if (bi < 0) break;
        cslot[bi] = bs; taken[bs] = 1;
    }
#define OUT(t, cd, v) do { if (nout < max - 1) { out[nout] = tmpl; out[nout].type = (t); out[nout].code = (cd); out[nout].value = (v); nout++; } } while (0)
    for (int s = 0; s < MAXSLOT; s++)                  /* fingers that are gone */
        if (as_[s].used && !taken[s]) {
            OUT(EV_ABS, ABS_MT_SLOT, s); OUT(EV_ABS, ABS_MT_TRACKING_ID, -1);
            as_[s].used = 0;
        }
    for (int i = 0; i < nc; i++) {
        int s = cslot[i], fresh = 0;
        if (s < 0) {                                   /* a new finger: the lowest free slot */
            for (s = 0; s < ASLOTS && (as_[s].used || taken[s]); s++) ;
            if (s >= ASLOTS) continue;
            if (c[i].x < 0 || c[i].y < 0) continue;    /* no position yet: wait for one */
            taken[s] = 1; fresh = 1;
            as_[s] = (struct aslot){ 1, c[i].tid, -1, -1, -1, -1, a_next_id };
            a_next_id = a_next_id >= 65535 ? 1 : a_next_id + 1;
        }
        struct aslot *a = &as_[s];
        int hdr = 0;
#define SLOTHDR() do { if (!hdr) { OUT(EV_ABS, ABS_MT_SLOT, s); hdr = 1; } } while (0)
        if (fresh) { SLOTHDR(); OUT(EV_ABS, ABS_MT_TRACKING_ID, a->id); }
        a->tid = c[i].tid;
        if (c[i].x >= 0 && c[i].x != a->x) { SLOTHDR(); OUT(EV_ABS, ABS_MT_POSITION_X, c[i].x); a->x = c[i].x; }
        if (c[i].y >= 0 && c[i].y != a->y) { SLOTHDR(); OUT(EV_ABS, ABS_MT_POSITION_Y, c[i].y); a->y = c[i].y; }
        if (c[i].major >= 0 && c[i].major != a->major) { SLOTHDR(); OUT(EV_ABS, ABS_MT_TOUCH_MAJOR, c[i].major); a->major = c[i].major; }
        if (src_proto == PROTO_A && c[i].pres >= 0 && c[i].pres != a->pres) { SLOTHDR(); OUT(EV_ABS, ABS_MT_PRESSURE, c[i].pres); a->pres = c[i].pres; }
#undef SLOTHDR
    }
    for (int i = 0; i < nother; i++) OUT(other[i].type, other[i].code, other[i].value);
    if (!nout) return 0;
    out[nout] = tmpl; out[nout].type = EV_SYN; out[nout].code = SYN_REPORT; out[nout].value = 0;
    return nout + 1;
#undef OUT
}

static int setup_uinput_touch(void)
{
    ufd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ufd < 0) ufd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (ufd < 0) { perror("uinput"); return -1; }
    ioctl(ufd, UI_SET_EVBIT, EV_SYN);
    ioctl(ufd, UI_SET_EVBIT, EV_KEY);
    ioctl(ufd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(ufd, UI_SET_EVBIT, EV_ABS);
    ioctl(ufd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    static const int codes[] = { ABS_MT_SLOT, ABS_MT_TRACKING_ID, ABS_MT_POSITION_X, ABS_MT_POSITION_Y, ABS_MT_TOUCH_MAJOR, ABS_MT_PRESSURE };
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "veradroid-touch");
    dev.id.bustype = BUS_VIRTUAL; dev.id.vendor = 0x1d6b; dev.id.product = 0x0001; dev.id.version = 1;
    for (unsigned i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) {
        struct input_absinfo ai;
        if (ioctl(tfd, EVIOCGABS(codes[i]), &ai) < 0) continue;       /* the source device does not have it */
        if (codes[i] == ABS_MT_POSITION_X) { sx_min = ai.minimum; sx_max = ai.maximum; }
        if (codes[i] == ABS_MT_POSITION_Y) { sy_min = ai.minimum; sy_max = ai.maximum; }
        if (codes[i] == ABS_MT_PRESSURE && ai.maximum > ai.minimum && ai.maximum > 0) out_pres_max = ai.maximum;
        ioctl(ufd, UI_SET_ABSBIT, codes[i]);
        dev.absmin[codes[i]] = ai.minimum; dev.absmax[codes[i]] = ai.maximum;
        if (ROT_ON && codes[i] == ABS_MT_POSITION_X) { dev.absmin[codes[i]] = 0; dev.absmax[codes[i]] = W - 1; }   /* logical pixels */
        if (ROT_ON && codes[i] == ABS_MT_POSITION_Y) { dev.absmin[codes[i]] = 0; dev.absmax[codes[i]] = H - 1; }
        fprintf(stderr, "abs %d: %d..%d\n", codes[i], dev.absmin[codes[i]], dev.absmax[codes[i]]);
    }
    if (src_proto != PROTO_B) {
        /* protocol A / single touch: the virtual device is protocol B (see the header). Position ranges: the source's MT
           axes (A) or ABS_X / ABS_Y (ST); logical pixels when rotated */
        struct input_absinfo ax, ay;
        int okx = ioctl(tfd, EVIOCGABS(src_proto == PROTO_A ? ABS_MT_POSITION_X : ABS_X), &ax) >= 0;
        int oky = ioctl(tfd, EVIOCGABS(src_proto == PROTO_A ? ABS_MT_POSITION_Y : ABS_Y), &ay) >= 0;
        if (!okx) { ax.minimum = 0; ax.maximum = W - 1; }
        if (!oky) { ay.minimum = 0; ay.maximum = H - 1; }
        sx_min = ax.minimum; sx_max = ax.maximum; sy_min = ay.minimum; sy_max = ay.maximum;
        const int syn[4] = { ABS_MT_SLOT, ABS_MT_TRACKING_ID, ABS_MT_POSITION_X, ABS_MT_POSITION_Y };
        const int smin[4] = { 0, 0, ROT_ON ? 0 : ax.minimum, ROT_ON ? 0 : ay.minimum };
        const int smax[4] = { ASLOTS - 1, 65535, ROT_ON ? W - 1 : ax.maximum, ROT_ON ? H - 1 : ay.maximum };
        for (int i = 0; i < 4; i++) {
            ioctl(ufd, UI_SET_ABSBIT, syn[i]);
            dev.absmin[syn[i]] = smin[i]; dev.absmax[syn[i]] = smax[i];
            fprintf(stderr, "abs %d: %d..%d (proto %s)\n", syn[i], smin[i], smax[i], src_proto == PROTO_A ? "A" : "ST");
        }
    } else if (ROT_ON) {
        /* single-touch panels (PW5 goodix: ABS_X / ABS_Y + BTN_TOUCH) are only run through here when rotated */
        static const int st[] = { ABS_X, ABS_Y, ABS_PRESSURE };
        for (unsigned i = 0; i < sizeof(st) / sizeof(st[0]); i++) {
            struct input_absinfo ai;
            if (ioctl(tfd, EVIOCGABS(st[i]), &ai) < 0) continue;
            ioctl(ufd, UI_SET_ABSBIT, st[i]);
            dev.absmin[st[i]] = st[i] == ABS_X || st[i] == ABS_Y ? 0 : ai.minimum;
            dev.absmax[st[i]] = st[i] == ABS_X ? W - 1 : st[i] == ABS_Y ? H - 1 : ai.maximum;
            fprintf(stderr, "abs %d: %d..%d\n", st[i], dev.absmin[st[i]], dev.absmax[st[i]]);
        }
    }
    if (write(ufd, &dev, sizeof(dev)) < 0) { perror("uinput write"); return -1; }
    if (ioctl(ufd, UI_DEV_CREATE) < 0) { perror("UI_DEV_CREATE"); return -1; }
    return 0;
}

static int setup_uinput_keys(void)
{
    kfd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (kfd < 0) kfd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (kfd < 0) return -1;
    ioctl(kfd, UI_SET_EVBIT, EV_KEY);
    ioctl(kfd, UI_SET_EVBIT, EV_SYN);
    static const int keys[] = { KEY_BACK, KEY_HOME, KEY_MENU, KEY_VOLUMEUP, KEY_VOLUMEDOWN, KEY_POWER, KEY_PAGEUP, KEY_PAGEDOWN };
    for (unsigned i = 0; i < sizeof(keys) / sizeof(keys[0]); i++) ioctl(kfd, UI_SET_KEYBIT, keys[i]);
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    snprintf(dev.name, UINPUT_MAX_NAME_SIZE, "veradroid-keys");
    dev.id.bustype = BUS_VIRTUAL; dev.id.vendor = 0x1d6b; dev.id.product = 0x0002; dev.id.version = 1;
    if (write(kfd, &dev, sizeof(dev)) < 0) return -1;
    return ioctl(kfd, UI_DEV_CREATE);
}


/* "reset [why]" (--inject FIFO, the host around freezing / thawing the container) and SIGUSR1 / SIGUSR2: Android gets
   every contact lifted; a touch still on the panel is swallowed to its end (Android never saw it go down) */
static void touch_reset(const char *why)
{
    n_reset++;
    if (scroll_sent == 1) epd_cmd("SCROLL 0\n");
    scroll_sent = 0;
    prev_any = 0;
    dz_on = 0;
    int n = release_android(1, why);
    nheld = 0;
    md = count_active() ? SWALLOW : IDLE;
    logf_("reset (%s): %d contact(s) lifted in Android, %d finger(s) on the panel%s", why, n, count_active(), md == SWALLOW ? " (swallowed to the lift)" : "");
}
/* watchdog (see the header): runs on the poll tick */
static long long wd_last = 0;
static void stuck_watchdog(void)
{
    if (STUCK_MS <= 0 || paused) return;
    if (!out_any() && !count_active()) return;
    long long now = now_ms();
    if (now - last_hw_ms < STUCK_MS || now - wd_last < STUCK_MS) return;
    wd_last = now;
    n_wd++;
    resync_slots(2);            /* fingers the kernel no longer has: lifted here (and in Android when it had them) */
    android_check();            /* Android's contacts: none while a gesture holds the touch, else only the panel's */
}

/* ---- test hook: "tap x y [hold_ms]" / "swipe x1 y1 x2 y2 ms" / "down x y" "move x y" "up" written to the --inject FIFO is fed through exactly the same
   code path as a real touch (gesture window, held events, forwarding to Android), so latency and behaviour can be tested
   without a finger ---- */
static void mkev(struct input_event *e, int type, int code, int value)
{
    memset(e, 0, sizeof(*e)); e->type = type; e->code = code; e->value = value;
}
/* a move of a finger that is already down: position only, no tracking id / BTN_TOUCH (the driver resets the start point on those) */
static void inj_move(int x, int y)
{
    struct input_event fr[4];
    int n = 0;
    mkev(&fr[n++], EV_ABS, ABS_MT_SLOT, 0);
    mkev(&fr[n++], EV_ABS, ABS_MT_POSITION_X, x);
    mkev(&fr[n++], EV_ABS, ABS_MT_POSITION_Y, y);
    mkev(&fr[n++], EV_SYN, SYN_REPORT, 0);
    rot_frame(fr, n);
    handle_frame(fr, n);
}
static void inj_frame(int tid, int x, int y)
{
    struct input_event fr[8];
    int n = 0;
    mkev(&fr[n++], EV_ABS, ABS_MT_SLOT, 0);
    mkev(&fr[n++], EV_ABS, ABS_MT_TRACKING_ID, tid);
    if (tid >= 0) { mkev(&fr[n++], EV_ABS, ABS_MT_POSITION_X, x); mkev(&fr[n++], EV_ABS, ABS_MT_POSITION_Y, y); }
    mkev(&fr[n++], EV_KEY, BTN_TOUCH, tid >= 0 ? 1 : 0);
    mkev(&fr[n++], EV_SYN, SYN_REPORT, 0);
    rot_frame(fr, n);
    handle_frame(fr, n);
}
static void inject_cmd(int fd)
{
    char buf[256];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    if (n <= 0) return;
    buf[n] = 0;
    char *save = NULL;
    for (char *line = strtok_r(buf, "\n", &save); line; line = strtok_r(NULL, "\n", &save)) {
        int x1, y1, x2, y2, ms;
        /* an injected finger is not in the kernel's slots: without this the stuck watchdog lifted a "down" injected more
           than --stuck-ms after the last real panel event within one tick, and down / move / up tests never got a move */
        last_hw_ms = now_ms();
        if (!strncmp(line, "reset", 5) && (line[5] == 0 || line[5] == ' ')) {
            touch_reset(line[5] ? line + 6 : "host");
        } else if (sscanf(line, "down %d %d", &x1, &y1) == 2) {
            logf_("inject down %d,%d", x1, y1);
            inj_frame(77, x1, y1);          /* returns at once: the poll tick decides when the press is handed to Android */
        } else if (sscanf(line, "move %d %d", &x1, &y1) == 2) {
            inj_move(x1, y1);               /* down / move ... / up: a drag that can stop and hold (swipe-up-and-hold) */
        } else if (!strcmp(line, "up")) {
            logf_("inject up");
            inj_frame(-1, 0, 0);
        } else if (sscanf(line, "tap %d %d %d", &x1, &y1, &ms) >= 2) {
            if (sscanf(line, "tap %*d %*d %d", &ms) != 1) ms = 60;
            if (ms < 0) { logf_("inject tap ignored: invalid ms=%d", ms); continue; }
            if (ms > 30000) ms = 30000;
            logf_("inject tap %d,%d", x1, y1);
            inj_frame(77, x1, y1);
            usleep((useconds_t)ms * 1000);
            inj_frame(-1, 0, 0);
        } else if (sscanf(line, "swipe %d %d %d %d %d", &x1, &y1, &x2, &y2, &ms) == 5) {
            if (ms < 0) { logf_("inject swipe ignored: invalid ms=%d", ms); continue; }
            if (ms > 30000) ms = 30000;
            int steps = 12;      /* optional 6th number: steps (a real panel reports every ~8-16 ms; 12 steps is a coarse 24 Hz) */
            if (sscanf(line, "swipe %*d %*d %*d %*d %*d %d", &steps) != 1 || steps < 1 || steps > 500) steps = 12;
            logf_("inject swipe %d,%d -> %d,%d", x1, y1, x2, y2);
            for (int i = 0; i <= steps; i++) {
                int x = x1 + (x2 - x1) * i / steps, y = y1 + (y2 - y1) * i / steps;
                if (i == 0) inj_frame(78, x, y);            /* the tracking id is sent once, at touch-down, like the real panel */
                else inj_move(x, y);
                usleep((useconds_t)(ms * 1000 / steps));
            }
            inj_frame(-1, 0, 0);
        }
    }
}

/* gesture_tap (see the header): every key press is one bezel double tap */
static void tap_read(void)
{
    struct input_event ev;
    for (;;) {
        ssize_t n = read(tapfd, &ev, sizeof(ev));
        if (n == (ssize_t)sizeof(ev)) {
            if (ev.type != EV_KEY || ev.value != 1) continue;
            gconf_load();
            if (paused || !G_TAP || (FLAGF && access(FLAGF, F_OK) == 0)) { logf_("tap key %d ignored", ev.code); continue; }
            logf_("bezel tap (key %d) -> key %d", ev.code, G_TAP_KEY);
            idle_thaw();
            epd_cmd("TOUCH\n");
            key_tap(G_TAP_KEY);
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) return;
        logf_("tapdev gone (%s), bezel tap off", n < 0 ? strerror(errno) : "eof");   /* gesturetapd restarted: rescan on the next start */
        close(tapfd); tapfd = -1;
        return;
    }
}

int main(int argc, char **argv)
{
    const char *dev = NULL;
    int pos = 0;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--w") && i + 1 < argc) W = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--h") && i + 1 < argc) H = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--edge") && i + 1 < argc) EDGE = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--edge-top") && i + 1 < argc) EDGE_TOP = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--debug")) TOUCH_DEBUG = 1;
        else if (!strcmp(argv[i], "--window") && i + 1 < argc) WINDOW_MS = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--corner") && i + 1 < argc) CORNER = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--corner-ms") && i + 1 < argc) CORNER_MS = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--edge-hold") && i + 1 < argc) EDGE_HOLD_MS = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--edge-swipe") && i + 1 < argc) EDGE_SWIPE = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--edge-back") && i + 1 < argc) EDGE_BACK = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--no-gestures")) GESTURES = 0;
        else if (!strcmp(argv[i], "--flag") && i + 1 < argc) FLAGF = argv[++i];
        else if (!strcmp(argv[i], "--inject") && i + 1 < argc) INJECT = argv[++i];
        else if (!strcmp(argv[i], "--gconf") && i + 1 < argc) GCONF = argv[++i];
        else if (!strcmp(argv[i], "--fg") && i + 1 < argc) FGF = argv[++i];
        else if (!strcmp(argv[i], "--rotate") && i + 1 < argc) { ROT = atoi(argv[++i]); ROT_ON = 1; }
        else if (!strcmp(argv[i], "--vw") && i + 1 < argc) VW = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--tapdev") && i + 1 < argc) TAPDEV = argv[++i];
        else if (!strcmp(argv[i], "--epd-fifo") && i + 1 < argc) EPDF = argv[++i];
        else if (!strcmp(argv[i], "--rt") && i + 1 < argc) RT_PRIO = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--stuck-ms") && i + 1 < argc) STUCK_MS = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--idle-mark") && i + 1 < argc) IDLE_MARK = argv[++i];
        else if (!strcmp(argv[i], "--idle-freezer") && i + 1 < argc) IDLE_FZ = argv[++i];
        else if (!strcmp(argv[i], "--slirp-pid") && i + 1 < argc) SLIRP_PID = argv[++i];
        else if (!strcmp(argv[i], "--touch-stamp") && i + 1 < argc) TOUCH_STAMP = argv[++i];
        else if (!strcmp(argv[i], "--cpufreq") && i + 1 < argc) {   /* cpufreq dir of the CPU(s) to boost, e.g. /sys/devices/system/cpu/cpu0/cpufreq */
            const char *d = argv[++i];
            snprintf(CPUFREQ_MIN, sizeof(CPUFREQ_MIN), "%s/scaling_min_freq", d);
            snprintf(CPUFREQ_MAX, sizeof(CPUFREQ_MAX), "%s/scaling_max_freq", d);
        }
        else if (!strcmp(argv[i], "--proto") && i + 1 < argc) {
            const char *v = argv[++i];
            proto_forced = !strcmp(v, "a") || !strcmp(v, "A") ? PROTO_A : !strcmp(v, "st") || !strcmp(v, "ST") ? PROTO_ST : PROTO_B;
        }
        else if (pos == 0) { dev = argv[i]; pos++; }
        else if (pos == 1) { LOGF = argv[i]; pos++; }
        else if (pos == 2) { ACT = argv[i]; pos++; }
    }
    if (!dev) { fprintf(stderr, "usage: %s <touch-event-dev> <log-file> <action-script> [opts]\n", argv[0]); return 2; }
    PW = W; PH = H;
    if (VW <= 0 || VW > PW) VW = PW;
    if (ROT_ON) {
        if (ROT != 90 && ROT != 180 && ROT != 270) ROT = 0;
        if (ROT == 90 || ROT == 270) { W = PH; H = VW; }   /* from here on W / H are the logical (Android) size */
    }
    signal(SIGINT, on_sig); signal(SIGTERM, on_sig); signal(SIGCHLD, SIG_IGN); signal(SIGPIPE, SIG_IGN);
    signal(SIGUSR1, on_usr1); signal(SIGUSR2, on_usr2);

    tfd = open(dev, O_RDONLY | O_CLOEXEC);
    if (tfd < 0) { perror("open touch"); return 1; }
    {
        unsigned long keybits[(KEY_MAX / (8 * sizeof(unsigned long))) + 2];
        memset(keybits, 0, sizeof(keybits));
        if (ioctl(tfd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) >= 0)
            src_btn_touch = (keybits[BTN_TOUCH / (8 * sizeof(unsigned long))] >> (BTN_TOUCH % (8 * sizeof(unsigned long)))) & 1;
        /* which multitouch protocol (see the header): ABS_MT_SLOT -> B, MT positions only -> A, ABS_X / ABS_Y only -> ST */
        unsigned long absbits[(ABS_MAX / (8 * sizeof(unsigned long))) + 2];
        memset(absbits, 0, sizeof(absbits));
#define HASABS(c) ((absbits[(c) / (8 * sizeof(unsigned long))] >> ((c) % (8 * sizeof(unsigned long)))) & 1)
        if (ioctl(tfd, EVIOCGBIT(EV_ABS, sizeof(absbits)), absbits) >= 0 && !HASABS(ABS_MT_SLOT))
            src_proto = HASABS(ABS_MT_POSITION_X) ? PROTO_A : HASABS(ABS_X) ? PROTO_ST : PROTO_B;
#undef HASABS
        if (proto_forced >= 0) src_proto = proto_forced;
    }
    if (setup_uinput_touch() < 0) return 1;
    if (setup_uinput_keys() < 0) fprintf(stderr, "warning: no keys device\n");
    set_rt(RT_GCONF >= 0 ? RT_GCONF : RT_PRIO, "veradroid-input"); started = 1;
    if (ioctl(tfd, EVIOCGRAB, 1) < 0) perror("EVIOCGRAB");     /* from now on only we see the touches */
    if (TAPDEV && TAPDEV[0]) {
        tapfd = open(TAPDEV, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (tapfd < 0) fprintf(stderr, "tapdev %s: %s\n", TAPDEV, strerror(errno));
        else if (ioctl(tapfd, EVIOCGRAB, 1) < 0) perror("EVIOCGRAB tapdev");
    }
    {
        struct input_absinfo ai;
        if (src_proto == PROTO_B && ioctl(tfd, EVIOCGABS(ABS_MT_SLOT), &ai) == 0 && ai.value >= 0 && ai.value < MAXSLOT) hw_slot = cur_slot = ai.value;
    }
    if (EDGE_TOP < 0) EDGE_TOP = EDGE;
    EDGE_BASE = EDGE; EDGE_TOP_BASE = EDGE_TOP; EDGE_SWIPE_BASE = EDGE_SWIPE;
    int ifd = -1;
    if (INJECT) { mkfifo(INJECT, 0600); ifd = open(INJECT, O_RDWR | O_NONBLOCK | O_CLOEXEC); }
    gconf_load();
    logf_("started: dev=%s %dx%d edge=%d window=%dms corner=%d/%dms gestures=%d", dev, W, H, EDGE, WINDOW_MS, CORNER, CORNER_MS, GESTURES);
    if (src_proto != PROTO_B) logf_("source is %s: converted to slots", src_proto == PROTO_A ? "multitouch protocol A" : "single touch");
    if (ROT_ON) logf_("rotate %d: panel %dx%d (visible width %d) -> logical %dx%d", ROT, PW, PH, VW, W, H);

    struct input_event frame[MAXEV];
    int nf = 0, dropping = 0;
    while (!quit) {
        if (want_release) {
            want_release = 0;
            boost_end();
            if (scroll_sent == 1) epd_cmd("SCROLL 0\n");
            scroll_sent = 0;
            prev_any = 0;
            dz_on = 0;
            if (!paused) { release_android(1, "released to the Kindle"); ioctl(tfd, EVIOCGRAB, 0); if (tapfd >= 0) ioctl(tapfd, EVIOCGRAB, 0); paused = 1; md = IDLE; nheld = 0; nf = 0; memset(sl, 0, sizeof(sl)); memset(as_, 0, sizeof(as_)); st_btn = 0; logf_("released"); }
        }
        if (want_grab) {
            want_grab = 0;
            if (paused) {
                ioctl(tfd, EVIOCGRAB, 1); if (tapfd >= 0) ioctl(tapfd, EVIOCGRAB, 1); paused = 0; md = IDLE; prev_any = 0; scroll_sent = 0; dz_on = 0; nheld = 0; nf = 0; memset(sl, 0, sizeof(sl)); memset(as_, 0, sizeof(as_)); st_btn = 0;
                struct input_absinfo ai;
                if (src_proto == PROTO_B && ioctl(tfd, EVIOCGABS(ABS_MT_SLOT), &ai) == 0 && ai.value >= 0 && ai.value < MAXSLOT) hw_slot = cur_slot = ai.value;
                release_android(1, "grabbed again");
                logf_("grabbed again");
            }
        }
        long long now_t = now_ms();
        if (boost_until && now_t >= boost_until) boost_end();
        else if (boost_on && !boost_until && boost_t0 && now_t - boost_t0 >= BOOST_HOLD_MAX_MS) boost_end();
        struct pollfd pf[3] = { { tfd, POLLIN, 0 }, { ifd, POLLIN, 0 }, { tapfd, POLLIN, 0 } };
        /* Exact deadline poll timeout: avoids 100 Hz wakeups during touch hold and removes up to 10 ms jitter on WINDOW_MS / EDGE_HOLD_MS */
        int pto = -1;
        long long dl = -1;
        if (md == PENDING) dl = t_down + WINDOW_MS;
        else if ((md == EDGE_B || md == EDGE_T) && !corner_fired && count_active() == 1) dl = t_down + EDGE_HOLD_MS;
        else if (md == EDGE_ARM) dl = now_t + 10;
        else if (md == TWO && !two_done && G_TWO_LONG) dl = two_t0 + 2000;
        else if ((md == PASS || md == EDGE_FWD) && !corner_fired && CORNER_MS > 0 && count_active() == 1) dl = t_down + CORNER_MS;
        if (dl >= 0) {
            long long left = dl - now_t;
            pto = left > 0 ? (int)(left > 60000 ? 60000 : left) : 1;
        }
        if (STUCK_MS > 0 && (out_any() || count_active())) { int w = STUCK_MS / 4 > 10 ? STUCK_MS / 4 : 10; if (pto < 0 || w < pto) pto = w; }
        if (boost_until) { long long left = boost_until - now_t; if (left < 1) left = 1; if (pto < 0 || left < pto) pto = (int)left; }
        else if (boost_on && boost_t0) { long long left = (boost_t0 + BOOST_HOLD_MAX_MS) - now_t; if (left < 1) left = 1; if (pto < 0 || left < pto) pto = (int)left; }
        int pr = poll(pf, 3, pto);   /* a negative fd is skipped by poll */
        if (pr > 0 && tapfd >= 0 && (pf[2].revents & (POLLIN | POLLERR | POLLHUP))) {
            tap_read();
            if (!(pf[0].revents & POLLIN) && !(ifd >= 0 && (pf[1].revents & POLLIN))) continue;
        }
        if (pr > 0 && ifd >= 0 && (pf[1].revents & POLLIN)) { inject_cmd(ifd); if (!(pf[0].revents & POLLIN)) continue; }   /* nothing to time while idle: sleep until a touch or a signal */
        if (pr == 0) {
            /* idle tick: lets PENDING expire and long presses fire without new events */
            if (md == PENDING && now_ms() - t_down >= WINDOW_MS) {
                if (count_active() >= 2 && two_any()) {
                    nheld = 0; md = TWO; two_t0 = now_ms();
                    two_begin();
                } else { flush_held(); md = PASS; }
            }
            if ((md == EDGE_B || md == EDGE_T) && !corner_fired && count_active() == 1 && now_ms() - t_down >= EDGE_HOLD_MS) {
                flush_held();                    /* held still in the edge strip (long press there): let Android have it now */
                edge_kind = md;
                md = EDGE_FWD;
            }
            if (md == EDGE_ARM) eb_armed(count_active(), 0);   /* swipe up and hold: a still finger sends nothing */
            if (md == TWO && !two_done && G_TWO_LONG && now_ms() - two_t0 >= 2000) {
                two_done = 1;                    /* evaluated once: never re-arm expired deadline (prevents 1000 Hz poll spin) */
                if (count_active() >= 2 && sl[two_i[0]].active && sl[two_i[1]].active
                    && abs(sl[two_i[0]].x - two_sx[0]) < 60 && abs(sl[two_i[0]].y - two_sy[0]) < 60
                    && abs(sl[two_i[1]].x - two_sx[1]) < 60 && abs(sl[two_i[1]].y - two_sy[1]) < 60) action("invert", NULL);
            }
            if (md == PASS && !corner_fired && CORNER_MS > 0 && count_active() == 1 && now_ms() - t_down >= CORNER_MS) {
                corner_fired = 1;                /* evaluated once: non-corner hold must not spin poll(1ms) forever */
                int as = active_slot();
                const char *cn;
                int sx = sl[as].sx >= 0 ? sl[as].sx : sl[as].x, sy = sl[as].sy >= 0 ? sl[as].sy : sl[as].y;
                if (sl[as].active && in_corner(sl[as].x, sl[as].y, &cn) && abs(sl[as].x - sx) < 40 && abs(sl[as].y - sy) < 40) {
                    cancel_to_android(); action(cn, NULL); md = SWALLOW;
                }
            }
            /* the same for a touch that started in the top / bottom edge strip and was handed to Android (EDGE_FWD): a finger
               held still sends no events, so only this tick sees the time run out. Same rule as handle_frame's EDGE_FWD
               branch (moved < 40 px) */
            if (md == EDGE_FWD && !corner_fired && CORNER_MS > 0 && count_active() == 1 && now_ms() - t_down >= CORNER_MS) {
                corner_fired = 1;
                int as = active_slot();
                const char *cn;
                int sx = sl[as].sx >= 0 ? sl[as].sx : sl[as].x, sy = sl[as].sy >= 0 ? sl[as].sy : sl[as].y;
                if (sl[as].active && in_corner(sl[as].x, sl[as].y, &cn) && abs(sl[as].x - sx) < 40 && abs(sl[as].y - sy) < 40) {
                    cancel_to_android(); action(cn, NULL); md = SWALLOW;
                }
            }
            stuck_watchdog();
            continue;
        }
        if (pr < 0) { if (errno == EINTR) continue; break; }
        /* read everything that is queued in one call: one event per read() kept a starved single-core reader even further
           behind the 64-event evdev buffer of the 3.0 kernels */
        static struct input_event rb[64];
        ssize_t n = read(tfd, rb, sizeof(rb));
        if (n < (ssize_t)sizeof(rb[0])) { if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue; break; }
        last_hw_ms = now_ms();
        if (paused) { nf = 0; dropping = 0; continue; }
        for (int k = 0; k < (int)(n / (ssize_t)sizeof(rb[0])); k++) {
            struct input_event e = rb[k];
            if (e.type == EV_SYN && e.code == SYN_DROPPED) {
                /* the kernel lost events: throw away the partial frame and everything up to the next SYN_REPORT,
                   then take the finger state from the kernel (evdev's documented recovery) */
                nf = 0; dropping = 1; drops++;
                continue;
            }
            if (e.type == EV_SYN && e.code == SYN_REPORT) {
                if (dropping) { dropping = 0; nf = 0; resync_slots(1); continue; }
                if (nf < MAXEV) frame[nf++] = e;
                if (src_proto == PROTO_B) {
                    /* name the slot: the panel only sends ABS_MT_SLOT when it changes, so a frame without one belongs to
                       the slot of the last one it sent. When a gesture swallowed that frame, Android's slot pointer stayed
                       elsewhere and this frame's contact landed in the wrong slot there (see the header) */
                    for (int k = 0; k < nf; k++) {
                        if (frame[k].type != EV_ABS) continue;
                        if (frame[k].code == ABS_MT_SLOT) break;
                        if (frame[k].code >= ABS_MT_TOUCH_MAJOR && frame[k].code <= ABS_MT_TOOL_Y && nf < MAXEV) {
                            memmove(&frame[1], &frame[0], nf * sizeof(frame[0]));
                            frame[0] = frame[1]; frame[0].type = EV_ABS; frame[0].code = ABS_MT_SLOT; frame[0].value = hw_slot; nf++;
                            break;
                        }
                    }
                    for (int k = 0; k < nf; k++)
                        if (frame[k].type == EV_ABS && frame[k].code == ABS_MT_SLOT) hw_slot = frame[k].value;
                    if (new_touch_over_active(frame, nf)) resync_slots(0);
                    rot_frame(frame, nf);
                    handle_frame(frame, nf);
                } else {
                    static struct input_event conv[MAXEV];
                    int nc = normalize_frame(frame, nf, conv, MAXEV);
                    if (nc) { rot_frame(conv, nc); handle_frame(conv, nc); }
                }
                nf = 0;
            } else if (!dropping && nf < MAXEV - 1) frame[nf++] = e;
        }
    }
    boost_end();
    ioctl(tfd, EVIOCGRAB, 0);
    if (tapfd >= 0) ioctl(tapfd, EVIOCGRAB, 0);
    ioctl(ufd, UI_DEV_DESTROY);
    if (kfd >= 0) ioctl(kfd, UI_DEV_DESTROY);
    logf_("boost: %lu times, scroll hints: %lu, idle thaws: %lu", n_boost, n_scroll, n_thaw);
    logf_("stuck contacts: %lu lifted in Android, %lu tracking id changes, %lu watchdog checks, %lu resets", n_released, n_tidchg, n_wd, n_reset);
    logf_("stopped, forwarded %lu events, %lu drops, %lu stuck fingers lifted, %lu BTN_TOUCH fixed, %lu pressure fixed", fwd_count, drops, resync_lifts, btn_fixes, pres_fixes);
    return 0;
}
