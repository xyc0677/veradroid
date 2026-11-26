#!/system/bin/sh
# VeraDroid: /system/bin/cmd inside the container (bound over the real one by container.sh, the real binary is
# /dev/vdbin/cmd). An app (uid >= 10000) may not run "cmd package compile / bg-dexopt-job / force-dex-opt": Android 7.1
# does not check who sends a shell command, and apps use it to recompile themselves.
# Why: 番茄免费小说 7.4.5 runs Runtime.exec("cmd package compile -m speed-profile -f com.dragon.read") a few minutes
# after every start ("TTBoostSpeedProfile"). On a 1 GB Kindle that dex2oat needs ~350 MB next to the app's ~400 MB and
# takes ~6 min; installd recreates base.odex first, so until it is done (and for good when the app or dex2oat gets
# killed on the way, which is what happened on a test PW6 every time) the app starts without a usable odex: ART unpacks all
# 25 dex files into anonymous memory (~250 MB more) and interprets everything - 书城 / 书架 blank, main thread busy,
# swap thrash, lmkd kills the foreground app, next start the same again.
# The host (compile.sh) compiles apps itself as root; this only turns the app's own request into a no-op.
case "$1 $2" in
"package compile"|"package bg-dexopt-job"|"package force-dex-opt")
    u=$(id -u 2>/dev/null)
    if [ -n "$u" ] && [ "$u" -ge 10000 ] 2>/dev/null; then
        log -t veradroid "cmd $1 $2 from uid $u ignored (VeraDroid compiles apps itself)" 2>/dev/null
        echo Success
        exit 0
    fi ;;
esac
exec /dev/vdbin/cmd "$@"
