# SPDX-License-Identifier: GPL-3.0-or-later
# 状态：桌面和面板要显示的东西（电量、前光、色温、Wi-Fi、反色），写进 $X/ctl/status。只在被问时写，不定时轮询（省电）。
status_write() {
    p=com.lab126.powerd
    bat=$(lipc-get-prop $p battLevel 2>/dev/null)
    chg=$(lipc-get-prop $p isCharging 2>/dev/null)
    fl=$(lipc-get-prop $p flIntensity 2>/dev/null)
    flmax=$(lipc-get-prop $p flMaxIntensity 2>/dev/null)
    warm=$(lipc-get-prop $p currentAmberLevel 2>/dev/null)
    wifi=$(wifi_state)
    slp=$(lipc-get-prop $p screenSaverTimeout 2>/dev/null)
    lan=$(ifconfig wlan0 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]*\).*/\1/p')
    # QEMU/USB-net and some jailbreaks expose eth0 instead of wlan0.
    [ -n "$lan" ] || lan=$(ifconfig eth0 2>/dev/null | sed -n 's/.*inet addr:\([0-9.]*\).*/\1/p')
    [ -n "$lan" ] || lan=$(ifconfig eth0 2>/dev/null | sed -n 's/.*inet \([0-9.]*\).*/\1/p')
    inv=$(sed -n 's/^KFB_INVERT=//p' $X/settings 2>/dev/null)
    _sp=$$; read -r _sp _ < /proc/self/stat 2>/dev/null || true
    {
        echo "battery=${bat:-0}"; echo "charging=${chg:-0}"
        echo "light=${fl:-0}"; echo "lightmax=${flmax:-24}"; echo "warmth=${warm:-0}"
        echo "wifi=${wifi:-1}"; echo "invert=${inv:-0}"; echo "sleep=${slp:-600}"; echo "lanip=$lan"
        # 机型 / 固件能力（vd-fwcheck 实测）：只有 PW6 有暖光（currentAmberLevel）；5.16 及更老的固件没有
        # screenSaverTimeout，自动休眠时间改不了。桌面据此隐藏色温、自动休眠设置。
        # 低内存模式（lib/settings.sh）：lowmem = 现在是否生效，lowmem_setting = 用户的选择（auto / on / off），
        # kindleui = Android 在前台时 Kindle 界面的处置（keep 只暂停 / freeze 后台进程冻住 / stop 框架停掉：返回 Kindle 要约一分钟）
        lowmem && echo "lowmem=1" || echo "lowmem=0"
        echo "lowmem_setting=${LOWMEM:-auto}"; echo "kindleui=$(kindle_ui_mode)"
        # 系统更新（桌面的“检查系统更新”和“自动更新”设置用；容器里看不到 $VD）：装的版本、按类别编的系统、
        # 自动更新方式（1 自动安装 / notify 只提醒 / 0 关闭）、只提醒时宿主发现的新版本
        echo "sysversion=$(head -1 $VD/version 2>/dev/null | tr -cd '0-9.')"; echo "imageclass=$(head -1 $VD/image-class 2>/dev/null | tr -cd 'A-Za-z0-9_-')"
        case "$(cat $VD/autoupdate 2>/dev/null)" in 0) echo "autoupdate=0" ;; notify) echo "autoupdate=notify" ;; *) echo "autoupdate=1" ;; esac
        echo "update_available=$(sed -n 's/^version=//p' $VD/update.available 2>/dev/null | head -1 | tr -cd '0-9.')"
        echo "memtotal_mb=$(awk '/^MemTotal:/{print int($2/1024)}' /proc/meminfo)"; echo "memavail_mb=$(awk '/^MemAvailable:/{print int($2/1024)}' /proc/meminfo)"
        [ -n "$warm" ] && echo "haswarmth=1" || echo "haswarmth=0"
        [ -n "$slp" ] && echo "hassleeptime=1" || echo "hassleeptime=0"
        [ -n "$fl" ] && echo "haslight=1" || echo "haslight=0"
        if [ "$(cat "$SYS/model" 2>/dev/null)" = cs ] || [ "$(cat /sys/class/graphics/fb0/bits_per_pixel 2>/dev/null)" = 32 ]; then echo "hascolor=1"; else echo "hascolor=0"; fi
        # 自动亮度：powerd 有 flAuto，而且机器上真有光线传感器才显示这个开关。
        # 传感器的判断：iio 设备里有 in_illuminance*，或者输入设备 / sensors 类里有名字带 als / lux / light 的。
        # （PW6 普通版实测没有传感器：flAuto 属性在，但没有任何这类设备。签名版 / Scribe 没有真机验证过。）
        fla=$(lipc-get-prop $p flAuto 2>/dev/null); als=
        [ "$fla" = 1 ] && [ -n "${W:-}" ] && rm -f "$W/fl.auto_off"
        ls /sys/bus/iio/devices/*/in_illuminance* >/dev/null 2>&1 && als=1
        # powerd 自己的读数：alsLux >= 0 = 有传感器（没有传感器时是 -1，PW5 5.16.2 模拟器实测）
        [ -z "$als" ] && ls_l=$(lipc-get-prop $p alsLux 2>/dev/null) && [ "$ls_l" -ge 0 ] 2>/dev/null && als=1
        [ -z "$als" ] && cat /sys/class/input/*/device/name /sys/class/sensors/*/name /sys/bus/iio/devices/*/name 2>/dev/null | grep -qiE "(^|[^a-z])(als|lux|light)" && als=1
        if [ -n "$fla" ] && [ -n "$als" ]; then echo "haslightauto=1"; echo "lightauto=$fla"; else echo "haslightauto=0"; fi
        # 蓝牙翻页器桥（bt.sh）：bt=1 桥在跑，btdev=当前接管的设备
        type bt_status_lines >/dev/null 2>&1 && bt_status_lines
        # 电量统计（energy.sh）：batstart=Android 启动时 / 上次充电后的电量，batmins=过了几分钟，batdrain=每小时掉几 %
        type bat_status_lines >/dev/null 2>&1 && bat_status_lines
        # Wi-Fi 省电现在的状态（on / off，读不到就不写）和设置
        ps=$(type wifi_ps_state >/dev/null 2>&1 && wifi_ps_state); [ -n "$ps" ] && echo "wifips=$ps"
        echo "wifipsmode=${WIFI_PS:-auto}"
    } > "$X/ctl/status.tmp.$_sp" && mv -f "$X/ctl/status.tmp.$_sp" $X/ctl/status
}
