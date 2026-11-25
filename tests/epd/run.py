#!/usr/bin/env python3
"""Native veradroid-epd test (tests/epd-test.sh): display/epd.c built for the host with tests/epd/fbinkstub.c (no panel;
the stub's wait sleeps like a PW6 panel). Frames are written into the shared buffer and posted on the FIFO the way
hwcomposer.kindle does; the checks read the bridge's own refresh log (ctl/timing on).
Checks: touch scroll mode (DU, or A2 with SCROLLWFM 1, during the drag, one REAGL FULL cleanup after, a drag without frames leaves the next page
alone, style 2 holds everything until the end), page counting only after input (FLIPINPUT / FLIPCOUNT), FAST, FREEZE,
the H hint, NEWWIN, GCN, the page-turn merge window (TURNHOLD), and that unknown lines are ignored.
usage: run.py EPD_BINARY"""
import os, re, subprocess, sys, tempfile, time

W, H = 1272, 1696
PAGE = W * H * 4
REF = re.compile(r'^\[(\d+)\] epd: (\d+)x(\d+)\+(\d+)\+(\d+) mode=(\d+) wfm=(\d+) full=(\d+) flash=(\d+)')
WFM_DU, WFM_GC16, WFM_A2, WFM_REAGL = 1, 2, 4, 6
fails = 0


def check(cond, msg):
    global fails
    print(('  ok   ' if cond else '  FAIL ') + msg)
    if not cond: fails += 1


class Bridge:
    def __init__(self, binary, extra=()):
        self.d = tempfile.mkdtemp(prefix='epdt-')
        self.shm = os.path.join(self.d, 'fb'); self.fifo = os.path.join(self.d, 'epd.fifo'); self.ctl = os.path.join(self.d, 'ctl')
        os.mkdir(self.ctl); open(os.path.join(self.ctl, 'timing'), 'w').close()
        with open(self.shm, 'wb') as f: f.write(b'\xff' * PAGE * 2)
        os.mkfifo(self.fifo)
        self.log = open(os.path.join(self.d, 'log'), 'w+')
        args = [binary, 'run', self.shm, self.fifo, self.ctl, '--w', str(W), '--h', str(H), '--contrast', '1.0', '--dither', '0',
                '--flash', '0', '--mode', '3', '--settle', '120', '--hold', '70', '--reagl-min', '30', '--anim-mode', '0'] + list(extra)
        self.p = subprocess.Popen(args, stderr=self.log, stdout=self.log)
        self.w = os.open(self.fifo, os.O_WRONLY)
        self.step = 0
        time.sleep(0.4)
        self.page(0, full=True); time.sleep(0.6)     # the first frame is drawn whole

    def send(self, *lines):
        os.write(self.w, ''.join(l + '\n' for l in lines).encode())

    def fill(self, y0, y1, x0=0, x1=W):
        """new content in rows y0..y1: text-like stripes that move every step (pixels change both ways)"""
        self.step += 1
        with open(self.shm, 'r+b') as f:
            for y in range(y0, y1):
                v = 0 if (y + self.step * 7) % 24 < 6 else 255
                row = bytes([v, v, v, 255]) * (x1 - x0)
                f.seek(y * W * 4 + x0 * 4); f.write(row)

    def page(self, n=0, full=False, small=False):
        if small: self.fill(800, 850, 600, 660)
        else: self.fill(0, H)
        self.send('P %d' % n)

    def mark(self):
        self.log.flush(); return os.path.getsize(self.log.name)

    def refreshes(self, since):
        self.log.flush()
        with open(self.log.name) as f:
            f.seek(since); txt = f.read()
        out = []
        for l in txt.splitlines():
            m = REF.match(l)
            if m: out.append(dict(t=int(m[1]), w=int(m[2]), h=int(m[3]), wfm=int(m[7]), full=int(m[8]), flash=int(m[9])))
        return out, txt

    def close(self):
        try: self.send('QUIT')
        except OSError: pass
        try: self.p.wait(timeout=3)
        except subprocess.TimeoutExpired: self.p.kill()
        if '-k' not in sys.argv: subprocess.call(['rm', '-rf', self.d])


def scroll(b, frames=6, gap=0.06, after=0.0):
    b.send('SCROLL 1'); time.sleep(0.02)
    for _ in range(frames): b.page(); time.sleep(gap)
    b.send('SCROLL 0')
    time.sleep(after)


def main():
    binary = sys.argv[1]
    print('== touch scroll mode')
    b = Bridge(binary, ['--scroll-delay', '1000'])
    m = b.mark(); scroll(b, 6, 0.06); time.sleep(1.6)
    r, txt = b.refreshes(m)
    du = [x for x in r if x['wfm'] == WFM_DU]
    clean = [x for x in r if x['wfm'] == WFM_REAGL and x['full']]
    check(len(du) >= 2 and all(x['wfm'] == WFM_DU for x in r[:len(du)]), f'drag frames go out as DU ({[x["wfm"] for x in r]})')
    check(len(clean) == 1 and r[-1] is clean[0] and 'scroll cleanup' in txt, 'one REAGL FULL cleanup after the drag, last')
    check(clean and clean[0]['t'] - du[-1]['t'] >= 1000, f'cleanup about SCROLLDELAY (1000 ms) after the last DU frame ({clean[0]["t"] - du[-1]["t"] if clean and du else "-"} ms)')
    check('flip new' not in txt and r and not any(x['flash'] for x in r), 'scroll frames are not page flips, no flash')
    # a drag during which the app drew nothing: the next page keeps its normal waveform
    m = b.mark(); b.send('SCROLL 1'); time.sleep(0.05); b.send('SCROLL 0'); time.sleep(0.1); b.page(); time.sleep(1.4)
    r, txt = b.refreshes(m)
    check(r and r[0]['wfm'] != WFM_DU and 'scroll cleanup' not in txt, f'drag without frames: the page after it is not DU ({[x["wfm"] for x in r]})')
    # SCROLLDELAY 0: hints ignored
    b.send('SCROLLDELAY 0'); time.sleep(0.05)
    m = b.mark(); scroll(b, 4, 0.3); time.sleep(1.4)
    r, txt = b.refreshes(m)
    check(r and not any(x['wfm'] == WFM_DU for x in r) and 'scroll' not in txt.replace('SCROLLDELAY', ''), f'SCROLLDELAY 0: no DU, no scroll handling ({[x["wfm"] for x in r]})')
    # SCROLLDU (burst fast mode, per-app) still works without input before the frames (FLIPINPUT must not hide them)
    b.send('SET 1.0 0 10 0 3 120', 'SCROLLDU 300'); time.sleep(0.05)     # KFB_FLASH 10 as on the devices
    m = b.mark()
    for _ in range(4): b.page(); time.sleep(0.3)
    time.sleep(1.0)
    r, txt = b.refreshes(m)
    check('burst: DU' in txt and any(x['wfm'] == WFM_DU for x in r), f'SCROLLDU 300 without SCROLL hints: burst frames in DU ({[x["wfm"] for x in r]})')
    b.send('SCROLLDU 0', 'SCROLLDELAY 1000', 'SET 1.0 0 0 0 3 120')
    # style 2: nothing during the drag, one REAGL after
    b.send('SCROLLSTYLE 2'); time.sleep(0.05)
    m = b.mark(); b.send('SCROLL 1'); time.sleep(0.02)
    for _ in range(5): b.page(); time.sleep(0.08)
    time.sleep(0.3); r1, _ = b.refreshes(m)
    b.send('SCROLL 0'); time.sleep(1.0)
    r, txt = b.refreshes(m)
    check(not r1, f'style 2: nothing drawn while the finger drags ({len(r1)} refreshes)')
    check(len(r) == 1 and r[0]['wfm'] == WFM_REAGL, f'style 2: one REAGL after the lift ({[x["wfm"] for x in r]})')
    b.send('SCROLLSTYLE 1')
    # SCROLLWFM 1: the drag frames go out as A2, the cleanup is still one REAGL FULL; RT is accepted and changes nothing visible
    b.send('SCROLLWFM 1', 'RT 0'); time.sleep(0.05)
    m = b.mark(); scroll(b, 6, 0.06); time.sleep(1.6)
    r, txt = b.refreshes(m)
    a2 = [x for x in r if x['wfm'] == WFM_A2]
    check(len(a2) >= 2 and not any(x['wfm'] == WFM_DU for x in r), f'SCROLLWFM 1: drag frames in A2, none in DU ({[x["wfm"] for x in r]})')
    check(r and r[-1]['wfm'] == WFM_REAGL and r[-1]['full'], 'SCROLLWFM 1: still one REAGL FULL cleanup at the end')
    b.send('SCROLLWFM 0'); time.sleep(0.05)
    m = b.mark(); b.page(); time.sleep(0.5)
    r, txt = b.refreshes(m)
    check(r and not any(x['wfm'] == WFM_A2 for x in r), f'SCROLLWFM 1 only applies to touch scrolls: a page outside a drag is not A2 ({[x["wfm"] for x in r]})')
    b.close()

    print('== page counting only after input (KFB_FLASH 2)')
    b = Bridge(binary, ['--flash', '2'])
    m = b.mark()
    for _ in range(4): b.page(); time.sleep(1.2)
    r, txt = b.refreshes(m)
    check(not any(x['flash'] for x in r) and txt.count('flip not counted (no input') >= 3, 'large changes with no input before them: not counted, no flash')
    m = b.mark()
    for _ in range(4): b.send('TOUCH'); time.sleep(0.1); b.page(); time.sleep(1.2)
    r, txt = b.refreshes(m)
    check(sum(x['flash'] for x in r) >= 1, f'page turns after TOUCH: counted, flash on the 2nd ({sum(x["flash"] for x in r)} flashes)')
    b.send('FLIPCOUNT 0'); m = b.mark()
    for _ in range(4): b.send('DIR 2'); time.sleep(0.1); b.page(); time.sleep(1.2)
    r, txt = b.refreshes(m)
    check(not any(x['flash'] for x in r), 'FLIPCOUNT 0 (home screen): no flash')
    b.send('FLIPCOUNT 1', 'FLIPINPUT 0'); m = b.mark()
    for _ in range(4): b.page(); time.sleep(1.2)
    r, txt = b.refreshes(m)
    check(sum(x['flash'] for x in r) >= 1, 'FLIPINPUT 0: every large change counts again')
    b.close()

    print('== FAST / FREEZE / H / NEWWIN / GCN')
    b = Bridge(binary)
    m = b.mark(); b.send('FAST 600'); time.sleep(0.02)
    for _ in range(4): b.page(); time.sleep(0.12)
    time.sleep(0.4); b.send('FAST 0'); time.sleep(1.6)
    r, txt = b.refreshes(m)
    check(r and r[0]['wfm'] == WFM_DU and r[-1]['wfm'] == WFM_REAGL and r[-1]['full'] and 'scroll cleanup' in txt,
          f'FAST: DU frames, then one cleanup ({[x["wfm"] for x in r]})')
    m = b.mark(); b.send('FREEZE 600'); time.sleep(0.02)
    for _ in range(4): b.page(); time.sleep(0.1)
    r1, _ = b.refreshes(m); time.sleep(0.8)
    r, txt = b.refreshes(m)
    check(not r1 and len(r) == 1, f'FREEZE: nothing during it, one refresh after ({len(r1)} / {len(r)})')
    m = b.mark(); b.send('FREEZE 5000'); b.page(); time.sleep(0.2); b.send('FREEZE 0'); time.sleep(0.6)
    r, _ = b.refreshes(m)
    check(len(r) == 1, f'FREEZE 0 ends it at once ({len(r)} refresh)')
    m = b.mark(); b.send('H D', 'H R'); b.page(); time.sleep(0.8)
    r, _ = b.refreshes(m)
    check(r and r[0]['wfm'] == WFM_REAGL, f'H D + H R before a frame: REAGL wins ({[x["wfm"] for x in r]})')
    m = b.mark(); b.send('H F'); b.page(small=True); time.sleep(0.8)
    r, _ = b.refreshes(m)
    check(r and r[0]['flash'] == 1, f'H F: the frame is a full flash ({[(x["wfm"], x["flash"]) for x in r]})')
    m = b.mark(); b.send('H D 0 0 400 300'); b.page(small=True); time.sleep(0.8)
    r, _ = b.refreshes(m)
    covers = any(x['w'] >= 400 and x['h'] >= 300 for x in r) or (len(r) >= 2)
    check(r and all(x['wfm'] == WFM_DU for x in r) and covers, f'H D with a rectangle: DU, the rectangle refreshed too ({[(x["w"], x["h"], x["wfm"]) for x in r]})')
    m = b.mark(); b.send('H D'); time.sleep(0.7); b.page(); time.sleep(0.8)
    r, _ = b.refreshes(m)
    check(r and r[0]['wfm'] != WFM_DU, f'a hint older than 500 ms is dropped ({[x["wfm"] for x in r]})')
    m = b.mark(); b.send('NEWWIN'); time.sleep(0.05); b.page(); time.sleep(0.8)
    r, _ = b.refreshes(m)
    check(r and r[0]['flash'] == 1, 'NEWWIN: the next large frame is a full flash')
    b.send('GCN 3'); m = b.mark()
    for _ in range(4): b.fill(300, 600); b.send('P 0'); time.sleep(0.9)
    r, txt = b.refreshes(m)
    check(sum(x['flash'] for x in r) == 1 and 'GCN 3 reached' in txt, f'GCN 3: one flash after 3 non-page refreshes ({sum(x["flash"] for x in r)})')
    m = b.mark(); b.send('NOSUCHCOMMAND 1', 'H', 'FREEZE x', 'FAST'); b.page(); time.sleep(0.8)
    r, _ = b.refreshes(m)
    check(r and b.p.poll() is None, 'unknown / malformed lines are ignored, frames still drawn')
    b.close()

    print('== page-turn merge window (TURNHOLD)')
    gotp = lambda txt: [int(x) for x in re.findall(r'^\[(\d+)\] epd: got P', txt, re.M)]
    def anim_turn(b, frames=5, gap=0.06):
        for _ in range(frames): b.page(); time.sleep(gap)
    b = Bridge(binary)
    time.sleep(0.5); m = b.mark(); anim_turn(b); time.sleep(1.2)
    r, _ = b.refreshes(m)
    check(len(r) >= 2, f'TURNHOLD 0 (default): an animated turn whose first frame is a whole page is drawn more than once ({len(r)})')
    b.send('TURNHOLD 150'); time.sleep(0.6)
    m = b.mark(); anim_turn(b); time.sleep(1.2)
    r, txt = b.refreshes(m); p = gotp(txt)
    check(len(r) == 1, f'TURNHOLD 150: the same animated turn is one refresh ({len(r)})')
    check(r and p and r[0]['t'] - p[-1] >= 140, f'TURNHOLD 150: drawn once the frames stopped for 150 ms ({r[0]["t"] - p[-1] if r and p else "-"} ms after the last frame)')
    time.sleep(0.6); m = b.mark(); b.page(); time.sleep(0.8)
    r, txt = b.refreshes(m); p = gotp(txt)
    check(len(r) == 1 and p and r[0]['t'] - p[0] >= 140, f'TURNHOLD 150: a turn without animation waits the window ({r[0]["t"] - p[0] if r and p else "-"} ms)')
    time.sleep(0.6); m = b.mark(); b.page(small=True); time.sleep(0.6)
    r, txt = b.refreshes(m); p = gotp(txt)
    check(r and p and r[0]['t'] - p[0] < 140, f'TURNHOLD 150: a small change is not held by it ({r[0]["t"] - p[0] if r and p else "-"} ms)')
    b.send('TURNHOLD 0'); time.sleep(0.6)
    m = b.mark(); b.page(); time.sleep(0.8)
    r, txt = b.refreshes(m); p = gotp(txt)
    check(len(r) == 1 and p and r[0]['t'] - p[0] < 140, f'TURNHOLD 0: a page goes out at once again ({r[0]["t"] - p[0] if r and p else "-"} ms)')
    b.close()

    print('== text edges (docs/text-sharpness.md)')
    stub = lambda txt: [tuple(int(v) for v in re.match(r'stub: wfm=(\d+) dither=(\d+) full=(\d+)', l).groups()) for l in txt.splitlines() if l.startswith('stub: wfm=')]
    b = Bridge(binary)
    b.send('SET 1.0 3 0 0 1 40'); time.sleep(0.8)      # 灰度最准, hardware dithering on
    m = b.mark(); b.page(); time.sleep(1.0)
    _, txt = b.refreshes(m); s = stub(txt)
    check(s and all(d == 0 for _, d, _ in s), f'a text page goes out without hardware dithering ({s})')
    with open(b.shm, 'r+b') as f:      # a 320x320 mid-gray block: a picture
        for y in range(400, 720):
            f.seek(y * W * 4 + 400 * 4); f.write(bytes([128, 128, 128, 255]) * 320)
    m = b.mark(); b.send('P 0 400 720'); time.sleep(1.0)
    _, txt = b.refreshes(m); s = stub(txt)
    check(s and all(d != 0 for _, d, _ in s), f'a picture keeps the hardware dithering ({s})')
    b.send('SET 1.0 0 0 0 0 40', 'CLEAN 300'); time.sleep(0.8)   # 均衡: small changes DU, CLEAN pass after them
    m = b.mark(); b.page(small=True); time.sleep(1.2)
    r, txt = b.refreshes(m); s = stub(txt)
    cl = [x for x in s if x[0] == 5]
    check(r and r[0]['wfm'] == 1 and cl and all(f == 1 for _, _, f in cl), f'CLEAN after a DU change is GL16 FULL ({s})')
    b.close()
    print('== hardware swipe guard (KFB_SWIPE / SWIPEOK, automatic switch-off)')
    b = Bridge(binary)
    time.sleep(0.5); m = b.mark(); b.send('SWIPE 2 2'); b.page(); time.sleep(0.8)
    _, txt = b.refreshes(m)
    check('stub: swipe refresh' in txt, 'SWIPE one-shot: the next page is a swipe')
    # Issue #33: a clean single-frame swipe of an illustrated page must not trigger a second QPASS refresh 300 ms later
    def illus_page(b, shade=128):
        b.fill(0, H)
        with open(b.shm, 'r+b') as f:
            for y in range(400, 720):
                f.seek(y * W * 4 + 400 * 4); f.write(bytes([shade, shade, shade, 255]) * 320)
        b.send('P 0')
    b.send('SET 1.0 3 0 0 0 40', 'ANIM 2 2 12 45', 'QPASS 300'); time.sleep(0.5)
    m = b.mark(); illus_page(b, 128); time.sleep(0.9)
    r, txt = b.refreshes(m)
    check(len(r) == 1 and 'stub: swipe refresh' in txt and 'quality pass' not in txt,
          f'clean animated turn of an illustrated page: one swipe refresh, no extra QPASS ({len(r)})')
    # Multi-step illustration loading after a swipe (qp_n >= 2) still triggers QPASS; QPASS 0 disables it
    time.sleep(2.1); m = b.mark(); illus_page(b, 112); time.sleep(0.25)
    with open(b.shm, 'r+b') as f:
        for y in range(420, 700):
            f.seek(y * W * 4 + 420 * 4); f.write(bytes([160, 160, 160, 255]) * 280)
    b.send('P 0 420 700'); time.sleep(0.9)
    r, txt = b.refreshes(m)
    check('quality pass' in txt, 'multi-step picture loading after a swipe still gets QPASS')
    b.send('QPASS 0', 'ANIM 0 2 12 45', 'SET 1.0 3 0 0 2 40'); time.sleep(0.4)
    m = b.mark(); illus_page(b, 136); time.sleep(0.9)
    r, txt = b.refreshes(m)
    check('quality pass' not in txt and len(r) == 1, f'QPASS 0 disables the picture quality pass even in 1-bit mode ({len(r)})')
    b.send('QPASS 300', 'SET 1.0 0 0 0 3 120')
    b.send('SWIPEOK 0'); time.sleep(0.3)
    m = b.mark(); b.send('SWIPE 2 2'); b.page(); time.sleep(0.8)
    _, txt = b.refreshes(m)
    check('stub: swipe refresh' not in txt and re.search(r'epd: \d+x\d+', txt), 'SWIPEOK 0: SWIPE ignored, the page is a normal refresh')
    b.send('ANIM 2 2 12 45'); time.sleep(0.3)
    m = b.mark(); b.page(); time.sleep(0.8)
    _, txt = b.refreshes(m)
    check('stub: swipe refresh' not in txt, 'SWIPEOK 0: ANIM page turns are not swiped either')
    b.close()
    b = Bridge(binary, ['--swipe', '0'])
    time.sleep(0.5); m = b.mark(); b.send('SWIPE 2 2'); b.page(); time.sleep(0.8)
    _, txt = b.refreshes(m)
    check('stub: swipe refresh' not in txt, '--swipe 0: SWIPE ignored from the start')
    b.close()
    os.environ['STUB_SWIPE_HANG'] = '1'
    b = Bridge(binary)
    time.sleep(0.5); m = b.mark(); b.send('SWIPE 2 2'); b.page(); time.sleep(0.3); b.page(small=True); time.sleep(3.6)
    r, txt = b.refreshes(m)
    check('swipe did not complete' in txt, 'a swipe that does not complete within 2.5 s is noticed')
    check(any(x['flash'] for x in r), f'... and the panel gets one full refresh ({[(x["wfm"], x["full"], x["flash"]) for x in r]})')
    m = b.mark(); b.send('SWIPE 2 2'); b.page(); time.sleep(0.8)
    _, txt = b.refreshes(m)
    check('stub: swipe refresh' not in txt, 'after that, swipes stay off for the run')
    b.close()
    del os.environ['STUB_SWIPE_HANG']
    print('FAILED: %d' % fails if fails else 'all passed')
    sys.exit(1 if fails else 0)


main()
