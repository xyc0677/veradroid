#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
# Offline integrity test for KPM packages/repo. Does not install anything.
set -u
HERE=$(cd "$(dirname "$0")" && pwd); REPO=$(dirname "$HERE")
fail=0
# Only packages that are part of the source tree. A checkout can hold ignored leftovers under kpm/ (e.g. the removed
# kpm/pkg/veradroid-ssh with only a host key in it, see .gitignore): those are not packages and must not fail the test.
# In a git checkout the tracked files decide; without git (exported tree, busybox) every veradroid* directory counts.
pkg_dirs() {
    if command -v git >/dev/null 2>&1 && git -C "$REPO" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
        git -C "$REPO" ls-files -- 'kpm/pkg/veradroid*' | sed -n 's|^\(kpm/pkg/veradroid[^/]*\)/.*|\1|p' | sort -u | sed "s|^|$REPO/|"
    else
        for d in "$REPO"/kpm/pkg/veradroid*; do [ -d "$d" ] && echo "$d"; done
    fi
}
tracked() { # file: tracked by git (or no git to ask)
    command -v git >/dev/null 2>&1 && git -C "$REPO" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 0
    [ -n "$(git -C "$REPO" ls-files -- "${1#$REPO/}")" ]
}
PKGS=$(pkg_dirs)
while IFS= read -r d; do
    [ -n "$d" ] || continue
    [ -d "$d" ] || { echo "FAIL tracked package dir missing: $d"; fail=1; continue; }
    name=$(basename "$d")
    python3 - "$d/manifest.json" <<'PY' || fail=1
import json,sys
p=sys.argv[1]; d=json.load(open(p))
assert d['manifest_version']==2, p
assert d['id'] and d['name'] and d['version'], p
assert isinstance(d['supported_platforms'],list) and d['supported_platforms'], p
print('manifest ok', p)
PY
    sh -n "$d/install.sh" || { echo "FAIL syntax $d/install.sh"; fail=1; }
    if [ "$name" = veradroid ]; then
        grep -q 'Never factory-wipe' "$d/install.sh" || { echo 'FAIL upgrade factory guard'; fail=1; }
        grep -q 'export VD_FACTORY=1' "$d/install.sh" && { echo 'FAIL auto factory export'; fail=1; }
    fi
    sh -n "$d/launch.sh" || { echo "FAIL syntax $d/launch.sh"; fail=1; }
    pkg="$REPO/kpm/${name}_${VER:-}.kpkg"
    # Find the matching artifact by prefix rather than hard-coding a version.
    found=0
    for f in "$REPO"/kpm/"$name"_*.kpkg; do
        [ -f "$f" ] || continue
        tracked "$f" || continue
        found=1
        tar -tzf "$f" | grep -qx manifest.json || { echo "FAIL tar manifest $f"; fail=1; }
        tar -tzf "$f" | grep -qx install.sh || { echo "FAIL tar install $f"; fail=1; }
        tar -tzf "$f" | grep -qx launch.sh || { echo "FAIL tar launch $f"; fail=1; }
    done
    if [ "$found" = 0 ]; then
        # Source-only tree (the public repository ships no built .kpkg): pack the package the way it is published
        # (manifest.json / install.sh / launch.sh at the archive root) and check that instead.
        KT=$(mktemp -d "${TMPDIR:-/tmp}/vdkpm.XXXXXX")
        f=$KT/${name}_src.kpkg
        if tar -czf "$f" -C "$d" manifest.json install.sh launch.sh 2>/dev/null; then
            for m in manifest.json install.sh launch.sh; do
                tar -tzf "$f" | grep -qx "$m" || { echo "FAIL packed $name has no $m"; fail=1; }
            done
            echo "no built kpkg for $name: packed from source ok"
        else
            echo "FAIL cannot pack $name from source"; fail=1
        fi
        rm -rf "$KT"
    fi
    # every package must be listed in the KPM repository manifest
    python3 -c 'import json,sys; sys.exit(0 if sys.argv[2] in json.load(open(sys.argv[1]))["packages"] else 1)' \
        "$REPO/kpm/manifest.v2.json" "$name" || { echo "FAIL $name not listed in kpm/manifest.v2.json"; fail=1; }
done <<EOF
$PKGS
EOF
[ -n "$PKGS" ] || { echo "FAIL no KPM packages found"; fail=1; }
cmp -s "$REPO/kpm/manifest.v2.json" "$REPO/cloud/static/kpm/manifest.v2.json" || { echo "FAIL kpm/manifest.v2.json and cloud/static/kpm/manifest.v2.json differ"; fail=1; }
python3 - "$REPO" $PKGS <<'PY' || fail=1
import json, os, sys
repo = sys.argv[1]
pkg_dirs = sys.argv[2:]
m = json.load(open(os.path.join(repo, 'kpm', 'manifest.v2.json')))
pkgs = m.get('packages') or {}
for d in pkg_dirs:
    name = os.path.basename(d)
    assert name in pkgs, f'missing {name} in kpm/manifest.v2.json'
    pm = json.load(open(os.path.join(d, 'manifest.json')))
    arts = pkgs[name].get('artifacts') or []
    assert arts, f'no artifacts for {name}'
    art = arts[0]
    ver = '.'.join(str(x) for x in art.get('version') or [])
    assert ver == pm['version'], f'{name} version mismatch: repo={ver} pkg={pm["version"]}'
    assert f'{name}_{ver}_' in (art.get('url') or ''), f'{name} url version mismatch: {art.get("url")}'
print('repo manifest sync and versions ok')
PY
[ "$fail" = 0 ] && echo "ALL KPM PACKAGES OK" || echo "KPM PACKAGE FAILURES"
exit "$fail"
