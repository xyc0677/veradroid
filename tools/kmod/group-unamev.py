#!/usr/bin/env python3
"""group-unamev.py <kernels.tsv> <built.tsv> <published uname-v...> -> tools/kmod/sets-4.1.15/<set>.uname-v

A 4.1.15 module set is built for one firmware (the last one of its group). Every firmware of the same
model family whose kernel has the same config and the same export-CRC fingerprint (kernels.tsv: one line per firmware kernel,
column 5) can load it; they differ only in the build time inside `uname -v`. This writes, per set, the `uname -v` of all
of them (one per line, the built one first), which kmod.sh / the installer match line by line.
A `uname -v` already used by a published set (e.g. oa2-5162 = Oasis 2/3 5.16.2.1.1) is left out: one kernel, one set.
built.tsv: "<set>\t<uname -v of the firmware it was built for>".
"""
import os, sys
fam = {'pw4': ['kindle-paperwhite-4'], 'kt4': ['kindle-kt4'], 'ko3': ['kindle-oasis-3', 'kindle-oasis-2']}
rows = [l.rstrip('\n').split('\t') for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
built = [l.rstrip('\n').split('\t') for l in open(sys.argv[2], encoding='utf-8') if l.strip()]
published = set()
for f in sys.argv[3:]:
    published.update(x.strip() for x in open(f, encoding='utf-8') if x.strip())
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'sets-4.1.15')
seen = {}
for name, uv in built:
    models = fam[name.split('-')[0]]
    fp = {r[4] for r in rows if r[0] in models and r[2] == uv}
    assert len(fp) == 1, (name, uv, fp)
    fp = fp.pop()
    group = [(r[0], r[1], r[2]) for r in rows if r[0] in models and r[4] == fp]
    lines = [uv] + sorted({g[2] for g in group} - {uv} - published)
    assert uv not in published, name
    for l in lines:
        assert l not in seen, (l, name, seen.get(l))
        seen[l] = name
    open(os.path.join(out, name + '.uname-v'), 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    fws = sorted({'%s %s' % (g[0].replace('kindle-', ''), g[1]) for g in group if g[2] in lines})
    print('%-12s fp %s  %2d kernels: %s' % (name, fp, len(lines), ', '.join(fws)))
