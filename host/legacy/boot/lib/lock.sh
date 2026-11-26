# 锁屏密码（VeraHome 的 LockScreen.java，docs/host-settings.md 第 9 节）。密码本身只在 Android 里（VeraHome 私有目录，加盐哈希）；
# 宿主只做两件事：
#   lock_request      屏保前 / 醒来后让桌面盖上锁屏（ctl/act.lock；没设密码时桌面什么都不做）
#   lock_reset_check  用户忘了密码：USB 里在 veradroid/ 放 reset-pin.txt（或 reset-pin、RESET-PIN.txt），
#                     下次启动 / 解冻 / 屏保醒来时删掉它并写 ctl/lock.reset，桌面清掉密码。没有账号，这是唯一的找回办法。
lock_request() { [ -d $X/ctl ] && { : > $X/ctl/.act.lock.$$ && chmod 644 $X/ctl/.act.lock.$$ && mv -f $X/ctl/.act.lock.$$ $X/ctl/act.lock; } 2>/dev/null; return 0; }
lock_reset_check() {
    for f in "$VD/reset-pin.txt" "$VD/reset-pin" "$VD/RESET-PIN.txt" "$VD/reset-pin.txt.txt"; do
        [ -e "$f" ] || continue
        rm -f "$f"
        [ -d $X/ctl ] && { : > $X/ctl/.lock.reset.$$ && chmod 644 $X/ctl/.lock.reset.$$ && mv -f $X/ctl/.lock.reset.$$ $X/ctl/lock.reset; } 2>/dev/null
        t "lock: reset file found ($f), asking VeraHome to clear the PIN"
        return 0
    done
    return 0
}
