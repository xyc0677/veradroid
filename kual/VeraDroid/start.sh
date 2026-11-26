#!/bin/sh
# KUAL launcher for VeraDroid. Works on jailbreaks that expose KUAL but do not
# display .sh files in the native library.
# 和书库入口（VeraDroid-Launch.sh）一样：每点一次先在 veradroid/logs/start.log 记一行（以前 KUAL 这条路什么都不记，
# 社区报告“KUAL 点启动进不去”时看不出这一下到没到我们这里）。另外两点不同：
#  - 立刻脱离 KUAL（setsid 后台）：菜单项是 exitmenu，KUAL 关菜单 / 框架重启时可能连带结束它启动的进程；
#    veradroid.sh 自己也会 setsid，但那之前还要挂系统盘、跑 e2fsck，有好几秒还在 KUAL 的进程组里。
#  - 不是 root（有的越狱 KUAL 以框架用户跑命令）时先试越狱带的 su，不行就在屏幕和 start.log 里说清楚。
trap '' PIPE HUP
PATH=/usr/bin:/bin:/usr/sbin:/sbin:$PATH; export PATH
SL=/mnt/base-us/veradroid/logs/start.log
mkdir -p /mnt/base-us/veradroid/logs 2>/dev/null
echo "[$(date '+%Y-%m-%d %H:%M:%S')] KUAL entry tapped (uid $(id -u 2>/dev/null), $0)" >> $SL 2>/dev/null
if [ ! -f /mnt/base-us/veradroid/boot/veradroid.sh ]; then
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] NOT STARTED: /mnt/base-us/veradroid/boot/veradroid.sh is missing (program folder deleted?)" >> $SL 2>/dev/null
    eips -c >/dev/null 2>&1
    eips 1 3 "VeraDroid is not installed (veradroid/boot missing)." >/dev/null 2>&1
    eips 1 5 "Run the VeraDroid installer again (KUAL menu)." >/dev/null 2>&1
    exit 1
fi
if [ "$(id -u 2>/dev/null)" != 0 ]; then
    for su in /var/local/kmc/bin/su /usr/bin/su /bin/su; do
        [ -x "$su" ] || continue
        echo "[$(date '+%Y-%m-%d %H:%M:%S')] KUAL: not root, retrying through $su" >> $SL 2>/dev/null
        exec "$su" -c "sh /mnt/us/extensions/VeraDroid/start.sh" </dev/null >/dev/null 2>&1
    done
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] NOT STARTED: KUAL runs this as uid $(id -u 2>/dev/null), not root, and there is no su" >> $SL 2>/dev/null
    eips -c >/dev/null 2>&1
    eips 1 3 "VeraDroid needs root: KUAL runs it as a normal user." >/dev/null 2>&1
    eips 1 5 "Use the library entry \"VeraDroid\" or re-run the jailbreak hotfix." >/dev/null 2>&1
    exit 1
fi
eips 1 2 "Starting VeraDroid..." >/dev/null 2>&1
cd /
if command -v setsid >/dev/null 2>&1; then
    setsid sh /mnt/base-us/veradroid/boot/veradroid.sh home </dev/null >/dev/null 2>&1 &
else
    sh /mnt/base-us/veradroid/boot/veradroid.sh home </dev/null >/dev/null 2>&1 &
fi
exit 0
