#!/usr/bin/env python3
"""Recover /proc/kallsyms from a raw 32-bit ARM kernel Image (Linux 3.x / 4.1, CONFIG_KALLSYMS_ALL, absolute
kallsyms_addresses) - no device and no boot needed.

    kimgsyms.py <Image> [load-address]   -> "addr type name" lines like /proc/kallsyms (sorted by address)

load-address defaults to the address of the first byte of the Image (_text), taken from the decoded table
itself (the lowest 'T _text' entry). Only the kernel's own symbols are produced (no module symbols).

How: kallsyms.S lays out, 4-byte aligned and in this order, kallsyms_addresses[N], kallsyms_num_syms,
kallsyms_names (len byte + token indices), kallsyms_markers[(N+255)/256], kallsyms_token_table (256
NUL-terminated strings) and kallsyms_token_index[256] (u16 offsets into the token table). We find the
token index (0 first, strictly increasing, each offset just after a NUL in the bytes before it), then
walk back: markers end at the token table, the names end at the markers, num_syms precedes the names.
"""
import struct
import sys


def find_token_index(img):
    n = len(img)
    for p in range(0, n - 512, 4):
        if img[p] or img[p + 1] or not (1 < img[p + 2] < 40) or img[p + 3]:
            continue
        v = struct.unpack_from('<256H', img, p)
        if any(v[i] >= v[i + 1] for i in range(255)):
            continue
        for s in range(p - v[255] - 70, p - v[255] - 1):
            if s < 0:
                continue
            if any(img[s + o - 1] != 0 for o in v[1:]):
                continue
            tail = img[s + v[255]:p]
            k = tail.find(b'\0')
            if k > 0 and not tail[k:].strip(b'\0') and len(tail) - k <= 16 and all(b'\0' not in img[s + v[i]:s + v[i + 1] - 1] for i in range(255)):
                return s, p, v
    return None


def up(x, a):
    return (x + a - 1) & ~(a - 1)


def main():
    img = open(sys.argv[1], 'rb').read()
    r = find_token_index(img)
    if not r:
        sys.exit('token table not found')
    tts, tip, tidx = r
    tokens = []
    for i in range(256):
        o = tts + tidx[i]
        tokens.append(img[o:img.index(b'\0', o)].decode('latin1'))
    for A in (16, 4):                      # ARM's ".align 4" is 16 bytes; other arches pad to 4
        for M in range(1, 4096):
            ms = tts - up(4 * M, A)
            mk = struct.unpack_from('<%dI' % M, img, ms)
            if mk[0] != 0 or any(mk[i] >= mk[i + 1] for i in range(M - 1)):
                continue
            hi_ns = ms - mk[-1] - 1
            for ns in range(hi_ns - hi_ns % A, max(hi_ns - 256 * 80, 0), -A):
                off = 0; ok = True
                for i in range(256 * (M - 1)):
                    if i % 256 == 0 and mk[i // 256] != off:
                        ok = False; break
                    off += 1 + img[ns + off]
                if not ok:
                    continue
                # the last block: count entries until the padded end meets the markers
                q = ns - A if A == 16 else ns - 4
                N = struct.unpack_from('<I', img, q)[0]
                if not (256 * (M - 1) < N <= 256 * M):
                    continue
                off = 0
                for i in range(N):
                    off += 1 + img[ns + off]
                if up(ns + off, A) != ms:
                    continue
                names = []
                off = 0
                for i in range(N):
                    L = img[ns + off]
                    names.append(''.join(tokens[b] for b in img[ns + off + 1:ns + off + 1 + L]))
                    off += 1 + L
                addrs = struct.unpack_from('<%dI' % N, img, q - up(4 * N, A))
                sys.stdout.write(''.join('%08x %s %s\n' % (a, s[0], s[1:]) for a, s in zip(addrs, names)))
                return
    sys.exit('kallsyms_names not found')


if __name__ == '__main__':
    main()
