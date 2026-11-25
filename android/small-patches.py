#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""VeraDroid 对 AOSP 7.1.1 的小改动：精确字符串替换，找不到旧文本就报错（说明源码版本变了）。"""
import sys, os
SRC = sys.argv[1]
PATCHES = [
    # 没有 GPU：所有应用用 CPU 绘制（不走 hwui 的 GLES2），系统界面合成用 libagl（GLES1 软件）
    ("frameworks/base/core/java/android/view/ThreadedRenderer.java",
     "    public static boolean isAvailable() {\n        return DisplayListCanvas.isAvailable();\n    }",
     "    public static boolean isAvailable() {\n        return false; // VeraDroid: no GPU, software rendering only\n    }"),
    ("frameworks/base/core/java/android/view/ThreadedRenderer.java",
     "        if (DisplayListCanvas.isAvailable()) {\n            renderer = new ThreadedRenderer(context, translucent);",
     "        if (false && DisplayListCanvas.isAvailable()) { // VeraDroid: software rendering only\n            renderer = new ThreadedRenderer(context, translucent);"),
    # 软件绘制时 7.1 的“脏区不透明”优化：不透明子 View（如书封 ImageView）单独重绘时，祖先全部标 PFLAG_DIRTY_OPAQUE、跳过自己的背景；
    # 但途经 clipChildren=false 的 ViewGroup（多看书城的 SmartRefreshLayout）时脏区被扩成整个 ViewGroup，而跳过背景的标记还在 →
    # 整块区域被 drawSoftware 清成透明（我们的窗口缓冲区是 RGBA，层是不透明的）= 黑底，只剩子 View 的字和图（多看书城黑底）。
    # 硬件绘制不走这里，AOSP 8.0 起也删了这个优化。这里永远画背景。
    ("frameworks/base/core/java/android/view/View.java",
     "        final boolean dirtyOpaque = (privateFlags & PFLAG_DIRTY_MASK) == PFLAG_DIRTY_OPAQUE &&\n                (mAttachInfo == null || !mAttachInfo.mIgnoreDirtyState);",
     "        final boolean dirtyOpaque = false; // VeraDroid: software rendering, never skip the background (PFLAG_DIRTY_OPAQUE)"),
    # 宿主内核编进了 SELinux 但没加载策略：给属性文件设 security.selinux 会失败。N 里 init 把这个失败当致命错误（返回 -2 -> exit(1)），
    # 这里让它不致命——我们本来就不用 SELinux。
    ("bionic/libc/bionic/system_properties.cpp",
     "    return fsetxattr_failed ? -2 : 0;\n}",
     "    (void)fsetxattr_failed; // VeraDroid: no SELinux policy on the host kernel, setting labels is expected to fail\n    return 0;\n}"),
    # 没有 vold：init 的 init_user0 内建命令会 exec vdc 并一直等 vold，开机卡死
    ("system/core/rootdir/init.rc",
     "\n    init_user0\n",
     "\n    # VeraDroid: no vold, skip init_user0\n"),
    # 宿主内核编进了 SELinux 但没加载策略，系统里一律当作“未启用”：init 起服务时会 getfilecon / getcon，没有策略必然失败
    ("external/selinux/libselinux/src/enabled.c",
     "#ifdef ANDROID\n\treturn (selinux_mnt ? 1 : 0);",
     "#ifdef ANDROID\n\treturn 0; /* VeraDroid: SELinux is never enabled inside the container */"),
    # init 起服务时无条件计算 SELinux 上下文（getcon / getfilecon / security_compute_create），没有策略必然失败：只在启用时做
    ("system/core/init/service.cpp",
     "    } else {\n        char* mycon = nullptr;\n        char* fcon = nullptr;",
     "    } else if (is_selinux_enabled() > 0) {   // VeraDroid: never in the container\n        char* mycon = nullptr;\n        char* fcon = nullptr;"),
    ("system/core/init/service.cpp",
     "                !si.socketcon.empty() ? si.socketcon.c_str() : scon.c_str();",
     "                !si.socketcon.empty() ? si.socketcon.c_str() : (scon.empty() ? nullptr : scon.c_str());"),
    ("system/core/init/service.cpp",
     "        if (!seclabel_.empty()) {\n            if (setexeccon(seclabel_.c_str()) < 0) {",
     "        if (!seclabel_.empty() && is_selinux_enabled() > 0) {\n            if (setexeccon(seclabel_.c_str()) < 0) {"),
    # 没有 GPU：EGL 加载器在非模拟器上永远跳过软件渲染器 libGLES_android.so，直接 LOG_ALWAYS_FATAL。这里始终用它
    ("frameworks/native/opengl/libs/EGL/Loader.cpp",
     "            int emulationStatus = checkGlesEmulationStatus();",
     "            int emulationStatus = 0; // VeraDroid: always the software renderer (libGLES_android)"),
    # AlarmManagerService 的 ALARM_EVENT 处理对没有 PendingIntent 的（监听器型）闹钟直接 send()，NPE 会让 system_server 反复崩溃
    ("frameworks/base/services/core/java/com/android/server/AlarmManagerService.java",
     "                        try {\n                            alarm.operation.send();\n                        } catch (PendingIntent.CanceledException e) {\n                            if (alarm.repeatInterval > 0) {",
     "                        try {\n                            if (alarm.operation != null) alarm.operation.send(); // VeraDroid: listener alarms have no PendingIntent\n                        } catch (PendingIntent.CanceledException e) {\n                            if (alarm.repeatInterval > 0) {"),
    # netd (Android N) 用 Android 专有的 FRA_UID_START/FRA_UID_END (18/19) 建按 UID 路由的规则；主线内核 4.10+ 的 18/19 另有含义，
    # 5.15 内核会返回 EINVAL，结果 netd 加不了网络接口、没有路由。改用主线的 FRA_UID_RANGE (20，{start,end} 一个属性)。
    ("system/netd/server/RouteController.cpp",
     "rtattr FRATTR_UID_START = { U16_RTA_LENGTH(sizeof(uid_t)),    FRA_UID_START };\nrtattr FRATTR_UID_END   = { U16_RTA_LENGTH(sizeof(uid_t)),    FRA_UID_END };",
     "rtattr FRATTR_UID_RANGE = { U16_RTA_LENGTH(2 * sizeof(uint32_t)), 20 /* mainline FRA_UID_RANGE */ };   // VeraDroid"),
    ("system/netd/server/RouteController.cpp",
     "const uint16_t FRA_UID_START = 18;\nconst uint16_t FRA_UID_END   = 19;\nstatic_assert(FRA_UID_START > FRA_MAX,\n             \"Android-specific FRA_UID_{START,END} values also assigned in Linux uapi. \"\n             \"Check that these values match what the kernel does and then update this assertion.\");\n",
     "// VeraDroid: the kernel here is mainline 5.15, uid rules use FRA_UID_RANGE (20) instead of Android's FRA_UID_START/END\n"),
    ("system/netd/server/RouteController.cpp",
     "    rtattr fraIifName = { U16_RTA_LENGTH(iifLength), FRA_IIFNAME };\n    rtattr fraOifName = { U16_RTA_LENGTH(oifLength), FRA_OIFNAME };\n\n    iovec iov[] = {",
     "    rtattr fraIifName = { U16_RTA_LENGTH(iifLength), FRA_IIFNAME };\n    rtattr fraOifName = { U16_RTA_LENGTH(oifLength), FRA_OIFNAME };\n    uint32_t uidRange[2] = { (uint32_t) uidStart, (uint32_t) uidEnd };\n\n    iovec iov[] = {"),
    ("system/netd/server/RouteController.cpp",
     "        { &FRATTR_UID_START, isUidRule ? sizeof(FRATTR_UID_START) : 0 },\n        { &uidStart,         isUidRule ? sizeof(uidStart) : 0 },\n        { &FRATTR_UID_END,   isUidRule ? sizeof(FRATTR_UID_END) : 0 },\n        { &uidEnd,           isUidRule ? sizeof(uidEnd) : 0 },\n",
     "        { &FRATTR_UID_RANGE, isUidRule ? sizeof(FRATTR_UID_RANGE) : 0 },\n        { uidRange,          isUidRule ? sizeof(uidRange) : 0 },\n"),
    # 属性服务的权限检查完全靠 SELinux：先 getpeercon() 取对端上下文，取不到就拒绝。PW6 等内核编进了 SELinux（没加载策略），
    # getpeercon 能返回东西，selinux_check_access 在“未启用”时放行；3.0.35 的老 Kindle（PW2 / PW3）内核根本没有 SELinux，
    # getpeercon 失败 → 所有 setprop 都被拒（dmesg "sys_prop: permission denied"），包括 sys.boot_completed：
    # 宿主等不到开机完成，显示桥一直挡着（PW3 白屏），init.kindle.rc 里 boot_completed 触发的设置也不跑。
    # 容器里 SELinux 一律“未启用”，这时直接放行（PW6 上结果和以前一样，因为 selinux_check_access 本来就放行）。
    ("system/core/init/property_service.cpp",
     "    property_audit_data audit_data;\n\n    if (!sctx)\n        goto err;",
     "    property_audit_data audit_data;\n\n    if (is_selinux_enabled() <= 0)\n        return 1;   // VeraDroid: no SELinux in the container (3.0 kernels: getpeercon fails)\n\n    if (!sctx)\n        goto err;"),
    # 老 Kindle 内核（3.0.35 / 4.1.15）没有 /proc/sys/vm/mmap_rnd_bits（4.5 才有）：N 的 init 把“文件打不开”当安全失败，
    # 直接重启进 recovery。内核没有这个设置就没有可调的，按成功处理；文件存在时行为不变。
    ("system/core/init/init.cpp",
     "    std::ifstream inf(path, std::fstream::in);\n    if (!inf) {\n        return false;\n    }",
     "    std::ifstream inf(path, std::fstream::in);\n    if (!inf) {\n        return access(path.c_str(), F_OK) != 0; // VeraDroid: kernel < 4.5 has no such knob, nothing to raise\n    }"),
    # 宿主命名空间（chroot）模式：3.0 内核没有 PID / mount 命名空间，reboot() 会重启整台 Kindle，
    # remount_ro 会把 Kindle 的分区改成只读。宿主在这种模式下建 /dev/vdchroot：这时不碰内核，改为请宿主停掉 VeraDroid。
    # PW6 等有命名空间的机型没有这个文件，行为不变（命名空间里的 reboot() 只结束容器）。
    ("system/core/libcutils/android_reboot.c",
     "    int ret;\n    remount_ro(cb_on_remount);\n    switch (cmd) {",
     "    int ret;\n    if (access(\"/dev/vdchroot\", F_OK) == 0) { /* VeraDroid: never reboot / remount the Kindle itself */\n        int fd = open(\"/dev/kfb/ctl/cmd\", O_WRONLY | O_NONBLOCK | O_CLOEXEC);\n        if (fd >= 0) {\n            const char *c = (cmd == (int)ANDROID_RB_RESTART) ? \"restart\\n\" : \"stop\\n\";\n            if (write(fd, c, strlen(c)) < 0) { /* the host may be gone */ }\n            close(fd);\n        }\n        return -1;\n    }\n    remount_ro(cb_on_remount);\n    switch (cmd) {"),
    # 共用宿主网络（hostnet）模式：3.0 内核的老 Kindle 没有 net 命名空间，Android 和 Kindle 用同一个网络栈。
    # 宿主在 cmdline 里给 androidboot.vdhostnet=1。这时 netd 不许碰 Kindle 的网络：iptables / iptables-restore、
    # 策略路由、网卡地址和开关全部变成空操作（DNS 代理等照常）。有 net 命名空间的机型没有这个属性，行为不变。
    ("system/netd/server/NetdConstants.cpp",
     "#include <cutils/log.h>\n#include <logwrap/logwrap.h>\n",
     "#include <cutils/log.h>\n#include <cutils/properties.h>\n#include <logwrap/logwrap.h>\n"),
    ("system/netd/server/NetdConstants.cpp",
     "static int execIptablesCommand(int argc, const char *argv[], bool silent) {\n    int res;\n    int status;\n",
     "// VeraDroid: true when Android shares the Kindle's own network stack (no net namespace, see small-patches.py)\nbool vdHostNet() {\n    static int cached = -1;\n    if (cached < 0) {\n        char v[PROPERTY_VALUE_MAX];\n        property_get(\"ro.boot.vdhostnet\", v, \"0\");\n        cached = !strcmp(v, \"1\");\n    }\n    return cached;\n}\n\nstatic int execIptablesCommand(int argc, const char *argv[], bool silent) {\n    if (vdHostNet()) return 0;  // VeraDroid: never touch the Kindle's firewall\n    int res;\n    int status;\n"),
    ("system/netd/server/NetdConstants.cpp",
     "static int execIptablesRestoreCommand(const char *cmd, const std::string& commands) {\n",
     "static int execIptablesRestoreCommand(const char *cmd, const std::string& commands) {\n    if (vdHostNet()) return 0;  // VeraDroid: never touch the Kindle's firewall\n"),
    ("system/netd/server/NetdConstants.h",
     "bool isIfaceName(const char *name);\n",
     "bool isIfaceName(const char *name);\nbool vdHostNet();  // VeraDroid: Android shares the Kindle's network stack\n"),
    ("system/netd/server/RouteController.cpp",
     "int RouteController::Init(unsigned localNetId) {\n    if (int ret = flushRules()) {",
     "int RouteController::Init(unsigned localNetId) {\n    if (vdHostNet()) return 0;  // VeraDroid: leave the Kindle's routing alone\n    if (int ret = flushRules()) {"),
    ("system/netd/server/RouteController.cpp",
     "                                    const char* oif, uid_t uidStart, uid_t uidEnd) {\n    // Ensure that if you set a bit in the fwmark",
     "                                    const char* oif, uid_t uidStart, uid_t uidEnd) {\n    if (vdHostNet()) return 0;  // VeraDroid: leave the Kindle's routing alone\n    // Ensure that if you set a bit in the fwmark"),
    ("system/netd/server/RouteController.cpp",
     "                                     const char* destination, const char* nexthop) {\n    // At least the destination must be non-null.",
     "                                     const char* destination, const char* nexthop) {\n    if (vdHostNet()) return 0;  // VeraDroid: leave the Kindle's routing alone\n    // At least the destination must be non-null."),
    # hostnet：上面把 modifyIpRule 改成了“什么都不做、返回 0”，可 clearTetheringRules 是 while (ret == 0) 一直删到 ENOENT 为止——
    # 永远等不到，死循环，而且拿着 netd 的全局锁。Kindle 的 wlan0 一断（关 Wi-Fi、休眠时驱动卸载、换网络），ConnectivityService
    # 让 netd 把它移出网络（network interface remove），netd 就卡死在这里、占满一个核：之后所有 netd 命令超时，网络注册不回来，
    # DNS 也不通，只有重启 Android 才好（用户：“休眠后网络没了，退出重进才好”、“读着读着断网”；pw5-516211 模拟器复现）。
    ("system/netd/server/RouteController.cpp",
     "WARN_UNUSED_RESULT int clearTetheringRules(const char* inputInterface) {\n    int ret = 0;\n",
     "WARN_UNUSED_RESULT int clearTetheringRules(const char* inputInterface) {\n    if (vdHostNet()) return 0;  // VeraDroid vdhostnet-v2: modifyIpRule is a no-op here, the loop below would never end\n    int ret = 0;\n"),
    ("system/netd/server/RouteController.cpp",
     "#include \"Fwmark.h\"\n",
     "#include \"Fwmark.h\"\n#include \"NetdConstants.h\"\n"),
    ("system/netd/server/CommandListener.cpp",
     "    if (!strcmp(argv[1], \"list\")) {\n        DIR *d;\n        struct dirent *de;\n",
     "    // VeraDroid hostnet: the interfaces are the Kindle's own (wlan0): reading is fine, changing is not\n    if (vdHostNet() && strcmp(argv[1], \"list\") && strcmp(argv[1], \"getcfg\")) {\n        cli->sendMsg(ResponseCode::CommandOkay, \"VeraDroid hostnet: interface left unchanged\", false);\n        return 0;\n    }\n\n    if (!strcmp(argv[1], \"list\")) {\n        DIR *d;\n        struct dirent *de;\n"),
    # 以太网服务：hostnet 时只跟踪宿主写在 /dev/kfb/hostnet 里的网卡，不跑 DHCP / 静态配置（那会改 Kindle 的网卡），
    # 直接用宿主写好的地址、网关、DNS 注册网络（VdHostNet.java）。
    ("frameworks/opt/net/ethernet/java/com/android/server/ethernet/EthernetNetworkFactory.java",
     "        mIfaceMatch = context.getResources().getString(\n                com.android.internal.R.string.config_ethernet_iface_regex);\n",
     "        mIfaceMatch = context.getResources().getString(\n                com.android.internal.R.string.config_ethernet_iface_regex);\n        final String vdHostIface = VdHostNet.iface();  // VeraDroid: shared Kindle network\n        if (vdHostIface != null) mIfaceMatch = java.util.regex.Pattern.quote(vdHostIface);\n"),
    # 以前的中间版本（只有 VdHostNet.linkProperties 那一行）打过的源码树：升级成最终版本。干净的源码树里没有这段，跳过。
    # 必须排在下面“原版 → 最终版”那一条前面：中间版本的树里没有原版那行，那一条会报 text not found。
    ('frameworks/opt/net/ethernet/java/com/android/server/ethernet/EthernetNetworkFactory.java',
     '                final LinkProperties vdHostLp = VdHostNet.linkProperties(mIface);  // VeraDroid\n                if (vdHostLp != null) {\n                    linkProperties = vdHostLp;\n                } else if (config.getIpAssignment() == IpAssignment.STATIC) {',
     '                final LinkProperties vdHostLp = VdHostNet.enabled() ? VdHostNet.waitForKindle(mIface) : null;  // VeraDroid: never DHCP in hostnet\n                if (VdHostNet.enabled() && vdHostLp == null) {\n                    Log.i(TAG, "VeraDroid hostnet: provisioning of " + mIface + " stopped before the Kindle configured it");\n                    synchronized(EthernetNetworkFactory.this) { mIpProvisioningThread = null; }\n                    return;\n                } else if (vdHostLp != null) {\n                    linkProperties = vdHostLp;\n                } else if (config.getIpAssignment() == IpAssignment.STATIC) {', "optional"),
    ('frameworks/opt/net/ethernet/java/com/android/server/ethernet/EthernetNetworkFactory.java',
     '                if (config.getIpAssignment() == IpAssignment.STATIC) {',
     '                final LinkProperties vdHostLp = VdHostNet.enabled() ? VdHostNet.waitForKindle(mIface) : null;  // VeraDroid: never DHCP in hostnet\n                if (VdHostNet.enabled() && vdHostLp == null) {\n                    Log.i(TAG, "VeraDroid hostnet: provisioning of " + mIface + " stopped before the Kindle configured it");\n                    synchronized(EthernetNetworkFactory.this) { mIpProvisioningThread = null; }\n                    return;\n                } else if (vdHostLp != null) {\n                    linkProperties = vdHostLp;\n                } else if (config.getIpAssignment() == IpAssignment.STATIC) {'),
    # 系统里没有 FusedLocation 应用：LocationManagerService.systemRunning() 在这里抛 IllegalStateException，
    # SystemServer 记一条 "BOOT FAILURE Notifying Location Service running"（每次开机一个 wtf + dropbox 记录），
    # 而且位置服务的初始化就停在半路（后面的 passive 之外的提供者、设置监听、用户切换监听都没装上），
    # 应用调 LocationManager 时可能在 system_server 里踩到没初始化的字段。阅读器不需要定位：记一行日志，接着初始化完。
    ("frameworks/base/services/core/java/com/android/server/LocationManagerService.java",
     "        throw new IllegalStateException(\"Unable to find a fused location provider that is in the \"",
     "        if (true) { Log.w(TAG, \"VeraDroid: no fused location provider in the system image, continuing without one\"); return; }\n        throw new IllegalStateException(\"Unable to find a fused location provider that is in the \""),
    # ---- 2026-11-13 事故（PW6 5.20.2 真机：/data/data 全部被删、Kindle 的 documents 被清空）----
    # 链条：PackageManager 开机时“距上次 fstrim 超过 3 天”就强制 fstrim → vold 的 TrimTask 读不到 fstab（容器里没有）解引用空指针、段错误
    # → vold 重启的几秒里 PackageManager 准备用户 0 的存储（prepareUserStorage）失败 "missing output stream"
    # → prepareUserDataLI 的 catch 调 destroyUserDataLI → installd destroy_user_data 递归删 /data/user_de/0（指向 /data/data 的链接）、
    #   /data/data、/data/misc/user/0、/data/media/0，并穿过 /data/media/0/KindleLibrary 上的 bind 挂载删光了 Kindle 的书。
    # 1. vold：没有 fstab 时不 trim（以前直接段错误）
    ("system/vold/TrimTask.cpp",
     "    fstab = fs_mgr_read_fstab(android::vold::DefaultFstabPath().c_str());\n    for (int i = 0; i < fstab->num_entries; i++) {",
     "    fstab = fs_mgr_read_fstab(android::vold::DefaultFstabPath().c_str());\n    if (fstab == NULL) { LOG(WARNING) << \"VeraDroid: no fstab, nothing to trim\"; return; }  // was a NULL dereference (SIGSEGV)\n    for (int i = 0; i < fstab->num_entries; i++) {"),
    # 2. PackageManager：准备用户存储失败（vold 暂时连不上等）绝不删用户的数据，只记下来、稍等再试一次
    ("frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java",
     "        } catch (Exception e) {\n            logCriticalInfo(Log.WARN, \"Destroying user \" + userId + \" on volume \" + volumeUuid\n                    + \" because we failed to prepare: \" + e);\n            destroyUserDataLI(volumeUuid, userId,\n                    StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);\n\n            if (allowRecover) {\n                // Try one last time; if we fail again we're really in trouble\n                prepareUserDataLI(volumeUuid, userId, userSerial, flags, false);\n            }\n        }",
     "        } catch (Exception e) {\n            // VeraDroid: never wipe a user because preparing its storage failed (e.g. vold restarting): that wiped\n            // /data/data and, through a bind mount, the Kindle's own books (2026-11-13). Log it and try once more.\n            logCriticalInfo(Log.WARN, \"NOT destroying user \" + userId + \" on volume \" + volumeUuid\n                    + \" although we failed to prepare (VeraDroid): \" + e);\n            if (allowRecover) {\n                SystemClock.sleep(3000);\n                prepareUserDataLI(volumeUuid, userId, userSerial, flags, false);\n            }\n        }"),
    # 3. 序列号对不上 / 找不到用户时也不删用户 0（只有用户 0，删了就是全部应用数据）
    ("frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java",
     "            if (destroyUser) {\n                synchronized (mInstallLock) {",
     "            if (destroyUser && userId == UserHandle.USER_SYSTEM) {\n                logCriticalInfo(Log.WARN, \"VeraDroid: NOT destroying user 0 directory \" + file);\n                destroyUser = false;\n            }\n            if (destroyUser) {\n                synchronized (mInstallLock) {"),
    # 3b. 准备应用数据目录失败时（createAppData 出错）不删这个系统应用的数据再重建：线上 0.88 / 0.96 的 installd 在没有 SELinux 时
    #     restorecon 失败，于是每次开机都把所有系统应用（桌面、输入法、设置存储……）的数据目录删掉重建（uiderrors.txt 里的
    #     "Failed to create app data ... trying to recover" / "Recovery failed!"，模拟器 pw6bt-5202 上每次开机 38 个）。只记日志、原样重试一次。
    ("frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java",
     "                logCriticalInfo(Log.ERROR, \"Failed to create app data for \" + packageName\n                        + \", but trying to recover: \" + e);\n                destroyAppDataLeafLIF(pkg, userId, flags);\n",
     "                logCriticalInfo(Log.ERROR, \"Failed to create app data for \" + packageName\n                        + \", retrying WITHOUT destroying its data (VeraDroid): \" + e);\n"),
    # 4. installd：递归删除不进入挂载点（bind 挂载和 data 常在同一个文件系统上，st_dev 一样，只能查 mountinfo）
    ("frameworks/native/cmds/installd/utils.cpp",
     "#include <sys/statfs.h>\n",
     "#include <sys/statfs.h>\n#include <limits.h>   // VeraDroid: vd_is_mount_point\n#include <stdio.h>\n#include <string.h>\n#include <unistd.h>\n"),
    ("frameworks/native/cmds/installd/utils.cpp",
     "static int _delete_dir_contents(DIR *d,\n                                int (*exclusion_predicate)(const char *name, const int is_dir))\n{",
     "// VeraDroid: is the directory open on fd the root of a mount (e.g. the Kindle library bound into /data)?\nstatic bool vd_is_mount_point(int fd) {\n    char lnk[64], path[PATH_MAX];\n    snprintf(lnk, sizeof(lnk), \"/proc/self/fd/%d\", fd);\n    ssize_t n = readlink(lnk, path, sizeof(path) - 1);\n    if (n <= 0) return false;\n    path[n] = 0;\n    FILE* f = fopen(\"/proc/self/mountinfo\", \"re\");\n    if (f == NULL) return false;\n    char line[1024], mp[PATH_MAX];\n    bool hit = false;\n    while (!hit && fgets(line, sizeof(line), f)) {\n        if (sscanf(line, \"%*s %*s %*s %*s %4095s\", mp) == 1 && strcmp(mp, path) == 0) hit = true;\n    }\n    fclose(f);\n    return hit;\n}\n\nstatic int _delete_dir_contents(DIR *d,\n                                int (*exclusion_predicate)(const char *name, const int is_dir))\n{"),
    ("frameworks/native/cmds/installd/utils.cpp",
     "            subfd = openat(dfd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);\n            if (subfd < 0) {\n                ALOGE(\"Couldn't openat %s: %s\\n\", name, strerror(errno));\n                result = -1;\n                continue;\n            }\n            subdir = fdopendir(subfd);",
     "            subfd = openat(dfd, name, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);\n            if (subfd < 0) {\n                ALOGE(\"Couldn't openat %s: %s\\n\", name, strerror(errno));\n                result = -1;\n                continue;\n            }\n            if (vd_is_mount_point(subfd)) {   // VeraDroid: never delete through a mount point\n                ALOGE(\"VeraDroid: not deleting inside mount point %s\\n\", name);\n                close(subfd);\n                result = -1;\n                continue;\n            }\n            subdir = fdopendir(subfd);"),
    # ---- 没有 GPU 时让 Chromium WebView 能显示网页正文（docs/webview-nogpu.md）----
    # WebView 52 只有在它的 GPU 线程拿到 GLES2 上下文时才建“同步合成器”，View.onDraw 的软件绘制（DemandDrawSw）全靠它；
    # 拿不到（libagl 只有 GLES1，或 --disable-gpu）就退回浏览器专用的合成器，网页正文永远是空白。
    # 办法：libagl 多给两个“只支持 ES2”的 RGBA8888 配置；在这种配置上建的上下文用一个什么都不画的 GLES2
    # （device/veradroid/kindle/nullgles2）。网页内容仍由 CPU 画在应用的软件画布上；SurfaceFlinger 等一切 ES1 配置不受影响。
    ("frameworks/native/opengl/libagl/egl.cpp",
     "static configs_t const gConfigs[] = {",
     "// VeraDroid: ES2-only configs. Contexts on them get the null GLES2 table (libGLESv2_vdnull, see EGL/eglApi.cpp):\n"
     "// Chromium WebView needs a GLES2 context to exist, while it draws the page with the CPU on a software canvas.\n"
     "static config_pair_t const config_8_attribute_list[] = {\n"
     "        { EGL_BUFFER_SIZE,     32 },\n        { EGL_ALPHA_SIZE,       8 },\n        { EGL_BLUE_SIZE,        8 },\n"
     "        { EGL_GREEN_SIZE,       8 },\n        { EGL_RED_SIZE,         8 },\n        { EGL_DEPTH_SIZE,       0 },\n"
     "        { EGL_CONFIG_ID,        8 },\n        { EGL_NATIVE_VISUAL_ID, GGL_PIXEL_FORMAT_RGBA_8888 },\n"
     "        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },\n"
     "        { EGL_RENDERABLE_TYPE,  EGL_OPENGL_ES2_BIT },\n};\n\n"
     "static config_pair_t const config_9_attribute_list[] = {\n"
     "        { EGL_BUFFER_SIZE,     32 },\n        { EGL_ALPHA_SIZE,       8 },\n        { EGL_BLUE_SIZE,        8 },\n"
     "        { EGL_GREEN_SIZE,       8 },\n        { EGL_RED_SIZE,         8 },\n        { EGL_DEPTH_SIZE,      16 },\n"
     "        { EGL_CONFIG_ID,        9 },\n        { EGL_NATIVE_VISUAL_ID, GGL_PIXEL_FORMAT_RGBA_8888 },\n"
     "        { EGL_SURFACE_TYPE,     EGL_WINDOW_BIT|EGL_PBUFFER_BIT|EGL_PIXMAP_BIT },\n"
     "        { EGL_RENDERABLE_TYPE,  EGL_OPENGL_ES2_BIT },\n};\n\n"
     "static configs_t const gConfigs[] = {"),
    ("frameworks/native/opengl/libagl/egl.cpp",
     "        { config_7_attribute_list, NELEM(config_7_attribute_list) },\n};",
     "        { config_7_attribute_list, NELEM(config_7_attribute_list) },\n"
     "        { config_8_attribute_list, NELEM(config_8_attribute_list) },  // VeraDroid: ES2-only (null GLES2)\n"
     "        { config_9_attribute_list, NELEM(config_9_attribute_list) },\n};"),
    ("frameworks/native/opengl/libagl/egl.cpp",
     "    case 7:\n        pixelFormat = GGL_PIXEL_FORMAT_A_8;\n        depthFormat = GGL_PIXEL_FORMAT_Z_16;\n        break;\n    default:",
     "    case 7:\n        pixelFormat = GGL_PIXEL_FORMAT_A_8;\n        depthFormat = GGL_PIXEL_FORMAT_Z_16;\n        break;\n"
     "    case 8:     // VeraDroid: ES2-only configs\n        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888;\n        depthFormat = 0;\n        break;\n"
     "    case 9:\n        pixelFormat = GGL_PIXEL_FORMAT_RGBA_8888;\n        depthFormat = GGL_PIXEL_FORMAT_Z_16;\n        break;\n    default:"),
    # 只有 ES2-only 配置上的上下文用 GLESv2 表（空 GLES2）；其余（libagl 原有配置，SurfaceFlinger 也在其中）一律用 GLESv1 表，
    # 两张表原来都从 libagl 按同一份函数名填，所以这些上下文的行为和以前完全一样。
    ("frameworks/native/opengl/libs/EGL/eglApi.cpp",
     "            egl_context_t* c = new egl_context_t(dpy, context, config, cnx,\n                    version);",
     "            if (version == egl_connection_t::GLESv2_INDEX) {\n"
     "                // VeraDroid: no GPU. GLESv2 table = null GLES2, only for libagl's ES2-only configs\n"
     "                EGLint rt = 0;\n"
     "                if (cnx->egl.eglGetConfigAttrib(dp->disp.dpy, config, EGL_RENDERABLE_TYPE, &rt) &&\n"
     "                        (rt & EGL_OPENGL_ES_BIT)) {\n"
     "                    version = egl_connection_t::GLESv1_INDEX;\n"
     "                }\n"
     "            }\n"
     "            egl_context_t* c = new egl_context_t(dpy, context, config, cnx,\n                    version);"),
    ("frameworks/native/opengl/libs/EGL/Loader.cpp",
     "    if (mask & GLESv2) {\n      init_api(dso, gl_names,\n            (__eglMustCastToProperFunctionPointerType*)\n"
     "                &cnx->hooks[egl_connection_t::GLESv2_INDEX]->gl,\n            getProcAddress);\n    }",
     "    if (mask & GLESv2) {\n"
     "      // VeraDroid: no GPU. The GLESv2 table is a null GLES2 (libGLESv2_vdnull) when it is installed;\n"
     "      // eglCreateContext only gives it to contexts on libagl's ES2-only configs (Chromium WebView's GPU thread).\n"
     "      void* vdnull = dlopen(\"libGLESv2_vdnull.so\", RTLD_NOW | RTLD_LOCAL);\n"
     "      getProcAddressType vdget = vdnull ? (getProcAddressType)dlsym(vdnull, \"vdnull_gles2_get_proc\") : NULL;\n"
     "      init_api(vdget ? vdnull : dso, gl_names,\n            (__eglMustCastToProperFunctionPointerType*)\n"
     "                &cnx->hooks[egl_connection_t::GLESv2_INDEX]->gl,\n            vdget ? vdget : getProcAddress);\n    }"),
    # SurfaceFlinger 先找 ES2 配置：现在 libagl 有了 ES2-only（空 GLES2）配置，必须先要 ES1，否则合成什么都画不出来
    ("frameworks/native/services/surfaceflinger/RenderEngine/RenderEngine.cpp",
     "    // First try to get an ES2 config\n    err = selectEGLConfig(display, format, EGL_OPENGL_ES2_BIT, &config);\n    if (err != NO_ERROR) {\n"
     "        // If ES2 fails, try ES1\n        err = selectEGLConfig(display, format, EGL_OPENGL_ES_BIT, &config);",
     "    // VeraDroid: libagl's real configs are ES1; its ES2-only configs draw nothing (null GLES2 for WebView)\n"
     "    err = selectEGLConfig(display, format, EGL_OPENGL_ES_BIT, &config);\n    if (err != NO_ERROR) {\n"
     "        err = selectEGLConfig(display, format, EGL_OPENGL_ES2_BIT, &config);"),
    # ---- 早先只在构建机源码树里、没进 git 的两个补丁（2026-11-13 补进来，镜像要能从 git 重现）----
    # zygote 的 fd 白名单：5.17 等老内核会多出不认识的描述符（ashmem / binder），7.1 直接 abort zygote。跳过它们。
    # （线上包另外带一份打了这个补丁的 libandroid_runtime.so，安装器覆盖进镜像。）
    # installd：容器里没有 SELinux 策略，getfilecon / restorecon 失败不能让应用数据目录创建失败。
    ('frameworks/base/core/jni/fd_utils-inl.h',
     '\n      FileDescriptorInfo* info = FileDescriptorInfo::createFromFd(fd);\n      if (info == NULL) {\n        if (closedir(d) == -1) {\n          ALOGE("Unable to close directory : %s", strerror(errno));\n        }\n        return NULL;\n      }\n      open_fd_map[fd] = info;\n    }',
     '\n      FileDescriptorInfo* info = FileDescriptorInfo::createFromFd(fd);\n      if (info == NULL) {\n        // VeraDroid: old Kindle kernels expose extra fds (ashmem/binder)\n        // that Android 7.1\'s whitelist does not know. Skip them instead of\n        // aborting zygote, then let the child inherit them as before.\n        ALOGW("VeraDroid: skipping non-whitelisted zygote fd %d", fd);\n        continue;\n      }\n      open_fd_map[fd] = info;\n    }'),
    ('frameworks/base/core/jni/fd_utils-inl.h',
     "          delete it->second;\n          it->second = FileDescriptorInfo::createFromFd(*element);\n          if (it->second == NULL) {\n            // The descriptor no longer no longer refers to a whitelisted file.\n            // We flag an error and remove it from the list of files we're\n            // tracking.\n            error = true;\n            it = open_fd_map_.erase(it);\n          } else {\n            // Successfully restatted the file, move on to the next open FD.",
     '          delete it->second;\n          it->second = FileDescriptorInfo::createFromFd(*element);\n          if (it->second == NULL) {\n            ALOGW("VeraDroid: restat ignoring non-whitelisted fd %d", *element);\n            it = open_fd_map_.erase(it);\n          } else {\n            // Successfully restatted the file, move on to the next open FD.'),
    ('frameworks/base/core/jni/fd_utils-inl.h',
     '        const int fd = (*it);\n        FileDescriptorInfo* info = FileDescriptorInfo::createFromFd(fd);\n        if (info == NULL) {\n          // A newly opened file is not on the whitelist. Flag an error and\n          // continue.\n          error = true;\n        } else {\n          // Track the newly opened file.\n          open_fd_map_[fd] = info;',
     '        const int fd = (*it);\n        FileDescriptorInfo* info = FileDescriptorInfo::createFromFd(fd);\n        if (info == NULL) {\n          ALOGW("VeraDroid: restat ignoring new non-whitelisted fd %d", fd);\n          continue;\n        } else {\n          // Track the newly opened file.\n          open_fd_map_[fd] = info;'),
    ('frameworks/native/cmds/installd/commands.cpp',
     '    // libselinux. Not needed here.\n\n    if (lgetfilecon(path.c_str(), &before) < 0) {\n        PLOG(ERROR) << "Failed before getfilecon for " << path;\n        goto fail;\n    }\n    if (selinux_android_restorecon_pkgdir(path.c_str(), seinfo, uid, 0) < 0) {\n        PLOG(ERROR) << "Failed top-level restorecon for " << path;\n        goto fail;\n    }\n    if (lgetfilecon(path.c_str(), &after) < 0) {\n        PLOG(ERROR) << "Failed after getfilecon for " << path;\n        goto fail;\n    }\n\n    // If the initial top-level restorecon above changed the label, then go',
     '    // libselinux. Not needed here.\n\n    if (lgetfilecon(path.c_str(), &before) < 0) {\n        if (errno == ENODATA || errno == ENOTSUP || !is_selinux_enabled()) {\n            // VeraDroid: SELinux is disabled in the container; getfilecon\n            // returns ENOTSUP and must not fail app-data creation.\n            before = strdup("");\n        } else {\n            PLOG(ERROR) << "Failed before getfilecon for " << path;\n            goto fail;\n        }\n    }\n    if (selinux_android_restorecon_pkgdir(path.c_str(), seinfo, uid, 0) < 0) {\n        // This container has no SELinux policy mounted; restorecon cannot\n        // label anything. Treat that as a no-op instead of failing boot data.\n        if (is_selinux_enabled() > 0) {\n            PLOG(ERROR) << "Failed top-level restorecon for " << path;\n            goto fail;\n        }\n    }\n    if (lgetfilecon(path.c_str(), &after) < 0) {\n        if (errno == ENODATA || errno == ENOTSUP || !is_selinux_enabled()) {\n            after = strdup("");\n        } else {\n            PLOG(ERROR) << "Failed after getfilecon for " << path;\n            goto fail;\n        }\n    }\n\n    // If the initial top-level restorecon above changed the label, then go'),
    # 应用崩溃 / 无响应对话框：宿主写了 settings global hide_error_dialogs=1（afterboot.sh），但这个设置是 Android 8 才有的，
    # 7.1 的 AppErrors 只看 mShowDialogs（按键盘 / 触摸屏配置算）和 anr_show_background，于是多看阅读 :eink 进程反复崩溃时
    # 一直弹“屡次停止运行”。这里让 7.1 也认这个设置，语义同 Android 8+：不弹对话框，按“设备不能显示对话框”处理——
    # 崩溃照常记录、进程照常被杀 / 按原规则重启；无响应（ANR）照常记录 traces，然后直接结束该应用（相当于点了“关闭应用”）。
    ("frameworks/base/services/core/java/com/android/server/am/AppErrors.java",
     "            if ((mService.canShowErrorDialogs() || showBackground) && !crashSilenced) {\n                proc.crashDialog = new AppErrorDialog(mContext, mService, data);",
     "            if ((mService.canShowErrorDialogs() || showBackground) && !crashSilenced\n                    && Settings.Global.getInt(mContext.getContentResolver(), \"hide_error_dialogs\", 0) == 0) { // VeraDroid\n                proc.crashDialog = new AppErrorDialog(mContext, mService, data);"),
    ("frameworks/base/services/core/java/com/android/server/am/AppErrors.java",
     "            if (mService.canShowErrorDialogs() || showBackground) {\n                d = new AppNotRespondingDialog(mService,",
     "            if ((mService.canShowErrorDialogs() || showBackground)\n                    && Settings.Global.getInt(mContext.getContentResolver(), \"hide_error_dialogs\", 0) == 0) { // VeraDroid\n                d = new AppNotRespondingDialog(mService,"),
    # hostnet：Kindle 的 Wi-Fi 在 Android 开机时还没连上（真机 PW4 第一份报告），宿主写的 hostnet 里没有地址，上面那段返回 null，
    # 原来的代码就在 Kindle 自己的 wlan0 上跑 DHCP（拿到第二个租约，应用照样上不了网）。hostnet 模式下一律等 Kindle 自己把网卡配好
    # （VdHostNet.waitForKindle，地址 / 网关直接读内核），绝不跑 DHCP、不改网卡。
    # 老 Kindle 内核（3.0.35 / 4.1.15-lab126）没有 IPv6：java.net 的每个 socket 都按 AF_INET6 建（双栈），内核回 EAFNOSUPPORT，
    # 所有 Java 联网（HttpURLConnection / OkHttp：商店、在线更新、大多数应用）都失败，“Address family not supported by protocol”
    # （真机 PW4 第一份报告：商店的请求一个都没到服务器；PW4 模拟器复现）。浏览器内核（Chromium）用自己的 AF_INET socket，所以 EinkBro 能上网。
    # 没有 IPv6 时退回 AF_INET；connect / bind / sendto 的地址转换本来就在 EAFNOSUPPORT 时改用 IPv4 sockaddr（NET_IPV4_FALLBACK）。
    ("libcore/luni/src/main/java/libcore/io/IoBridge.java",
     "            fd = Libcore.os.socket(AF_INET6, stream ? SOCK_STREAM : SOCK_DGRAM, 0);\n",
     "            try {\n"
     "                fd = Libcore.os.socket(AF_INET6, stream ? SOCK_STREAM : SOCK_DGRAM, 0);\n"
     "            } catch (ErrnoException e) {\n"
     "                if (e.errno != EAFNOSUPPORT) throw e;\n"
     "                // VeraDroid: kernel without IPv6 (old Kindles): plain IPv4 socket\n"
     "                return Libcore.os.socket(AF_INET, stream ? SOCK_STREAM : SOCK_DGRAM, 0);\n"
     "            }\n"),
    ("libcore/luni/src/main/java/libcore/io/IoBridge.java",
     "            Libcore.os.setsockoptInt(fd, IPPROTO_IP, IP_TOS, (Integer) value);\n            Libcore.os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_TCLASS, (Integer) value);\n",
     "            Libcore.os.setsockoptInt(fd, IPPROTO_IP, IP_TOS, (Integer) value);\n"
     "            try {\n                Libcore.os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_TCLASS, (Integer) value);\n"
     "            } catch (ErrnoException e) {\n"
     "                if (e.errno != ENOPROTOOPT && e.errno != EOPNOTSUPP && e.errno != EINVAL) throw e;  // VeraDroid: IPv4-only socket\n"
     "            }\n"),
    ("libcore/luni/src/main/java/libcore/io/IoBridge.java",
     "            Libcore.os.setsockoptByte(fd, IPPROTO_IP, IP_MULTICAST_TTL, (Integer) value);\n            Libcore.os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, (Integer) value);\n",
     "            Libcore.os.setsockoptByte(fd, IPPROTO_IP, IP_MULTICAST_TTL, (Integer) value);\n"
     "            try {\n                Libcore.os.setsockoptInt(fd, IPPROTO_IPV6, IPV6_MULTICAST_HOPS, (Integer) value);\n"
     "            } catch (ErrnoException e) {\n"
     "                if (e.errno != ENOPROTOOPT && e.errno != EOPNOTSUPP && e.errno != EINVAL) throw e;  // VeraDroid: IPv4-only socket\n"
     "            }\n"),
    # 上面 IoBridge 管的是 libcore 自己的 socket；java.net.Socket / DatagramSocket（ojluni PlainSocketImpl）按 ipv6_available() 选 AF_INET6 / AF_INET，
    # 而 Android 7.1 把 IPv6_supported() 里的探测全注释掉了、永远 TRUE：没有 IPv6 的内核上照样 EAFNOSUPPORT
    # （PW4 模拟器：只打 IoBridge 补丁后商店仍然“Address family not supported by protocol”）。这里真的探一次：内核不支持 AF_INET6 就是 FALSE。
    # 只在 EAFNOSUPPORT 时判 FALSE（没有联网权限的进程建 socket 是 EACCES，不能因此关掉 IPv6）。zygote 里算一次，所有应用共用。
    ("libcore/ojluni/src/main/native/net_util_md.c",
     "#include <errno.h>\n#include <string.h>\n",
     "#include <errno.h>\n#include <string.h>\n#include <unistd.h>  /* VeraDroid: close() for the IPv6 probe */\n"),
    ("libcore/ojluni/src/main/native/net_util_md.c",
     "    // This one below is problematic, will fail without proper permissions\n",
     "    /* VeraDroid: old Kindle kernels (3.0 / 4.1) have no IPv6 at all: say so, java.net then uses AF_INET sockets */\n"
     "    fd = socket(AF_INET6, SOCK_DGRAM | SOCK_CLOEXEC, 0);\n"
     "    if (fd < 0) {\n        if (errno == EAFNOSUPPORT) return JNI_FALSE;\n    } else {\n        close(fd);\n    }\n"
     "    // This one below is problematic, will fail without proper permissions\n"),
    # AlarmManager：7.1 的 zygote 不给 system_server CAP_WAKE_ALARM（8.0 才加），CLOCK_REALTIME_ALARM / CLOCK_BOOTTIME_ALARM 的
    # timerfd_create 返回 EPERM，整个 timerfd 实现放弃，退回“Falling back to a handler”：闹钟按 uptime 的 Handler 消息计时、
    # 唤醒和非唤醒共用一条消息（后排的顶掉前面的），TIME_TICK 晚几分钟到几小时（1.00 用户反馈“时间还是不对”，docs/host-settings.md 第 5 节“时钟”）。
    # 没有这个权限时换成不要权限的 REALTIME / BOOTTIME（休眠归 Kindle 的 powerd，Android 不需要叫醒机器），
    # epoll 也不加 EPOLLWAKEUP（Android 冻结时到期的定时器不在内核里挂着唤醒源）。宿主 container.sh 的 alarmfix 是同一个改法的二进制版。
    ("frameworks/base/services/core/jni/com_android_server_AlarmManagerService.cpp",
     "        fds[i] = timerfd_create(android_alarm_to_clockid[i], 0);\n        if (fds[i] < 0) {\n",
     "        fds[i] = timerfd_create(android_alarm_to_clockid[i], 0);\n"
     "        if (fds[i] < 0 && errno == EPERM) {   // VeraDroid: no CAP_WAKE_ALARM, use the clock without the wakeup\n"
     "            if (android_alarm_to_clockid[i] == CLOCK_REALTIME_ALARM) fds[i] = timerfd_create(CLOCK_REALTIME, 0);\n"
     "            else if (android_alarm_to_clockid[i] == CLOCK_BOOTTIME_ALARM) fds[i] = timerfd_create(CLOCK_BOOTTIME, 0);\n"
     "        }\n"
     "        if (fds[i] < 0) {\n"),
    ("frameworks/base/services/core/jni/com_android_server_AlarmManagerService.cpp",
     "        event.events = EPOLLIN | EPOLLWAKEUP;\n",
     "        event.events = EPOLLIN;   // VeraDroid: no wakeup source (Android may be frozen when a timer expires)\n"),
    # 卸载 / 安装应用时若 netd 短暂超时（NativeDaemonTimeoutException -> IllegalStateException），PermissionMonitor 只抓 RemoteException，
    # 未捕获异常直接击穿主线程拖垮整个 system_server（真实用户诊断 PW5 1.11 实录）。同时兜底 IllegalStateException。
    ("frameworks/base/services/core/java/com/android/server/connectivity/PermissionMonitor.java",
     "        } catch (RemoteException e) {\n            loge(\"Exception when updating permissions: \" + e);\n        }",
     "        } catch (RemoteException | IllegalStateException e) { // VeraDroid: never crash system_server on netd timeout\n            loge(\"Exception when updating permissions: \" + e);\n        }"),
    # Android 7.1 经典 Toast$TN.handleShow BadTokenException（微信读书等应用在真实用户诊断 中实录，AOSP 8.0+ 已官方修复）。
    ("frameworks/base/core/java/android/widget/Toast.java",
     "                mWM.addView(mView, mParams);\n                trySendAccessibilityEvent();",
     "                try {\n                    mWM.addView(mView, mParams);\n                    trySendAccessibilityEvent();\n                } catch (WindowManager.BadTokenException e) { // VeraDroid: backport Android 8.0+ Toast BadTokenException guard\n                    Log.w(TAG, \"Ignoring BadTokenException in Toast.handleShow\", e);\n                }"),
    # 墨水屏惯性滚动阻尼（借鉴文石/墨水屏阅读器）：支持通过 persist.vd.fling_friction（<=31字符，Android 7.1 SystemProperties 上限）调节滚动惯性，
    # 避免松手后列表长时间滑行拖慢刷新并留残影；未设置时保持 AOSP 默认 SCROLL_FRICTION (0.015f)。
    ("frameworks/base/core/java/android/view/ViewConfiguration.java",
     "    public static float getScrollFriction() {\n        return SCROLL_FRICTION;\n    }",
     "    public static float getScrollFriction() {\n"
     "        String s = android.os.SystemProperties.get(\"persist.vd.fling_friction\", \"\");\n"
     "        if (!s.isEmpty()) {\n"
     "            try {\n"
     "                float f = Float.parseFloat(s);\n"
     "                if (f >= 0.005f && f <= 0.25f) return f;\n"
     "            } catch (NumberFormatException ignored) {}\n"
     "        }\n"
     "        return SCROLL_FRICTION;\n"
     "    }"),
    # 墨水屏列表拉边光晕静音（文石/海信/大我标配）：列表滑到顶/底不再绘制半透明拉扯光晕，消除末尾 3-5 帧多余刷新与灰斑残影。
    ("frameworks/base/core/java/android/widget/EdgeEffect.java",
     "    public boolean draw(Canvas canvas) {\n        update();",
     "    public boolean draw(Canvas canvas) {\n"
     "        if (android.os.SystemProperties.getBoolean(\"persist.vd.no_edge_glow\", true)) { // VeraDroid e-ink\n"
     "            mState = STATE_IDLE;\n"
     "            return false;\n"
     "        }\n"
     "        update();"),
    # healthd: drop EPOLLWAKEUP and fall back to CLOCK_BOOTTIME when CAP_WAKE_ALARM is stripped by vdnocap
    ("system/core/healthd/healthd.cpp",
     "    if (wakeup == EVENT_WAKEUP_FD)\n        ev.events |= EPOLLWAKEUP;\n",
     "    (void)wakeup;  // VeraDroid: no wakeup source (Android may be frozen when a battery event / timer arrives)\n"),
    ("system/core/healthd/healthd.cpp",
     "    wakealarm_fd = timerfd_create(CLOCK_BOOTTIME_ALARM, TFD_NONBLOCK);\n",
     "    wakealarm_fd = timerfd_create(CLOCK_BOOTTIME_ALARM, TFD_NONBLOCK);\n"
     "    if (wakealarm_fd == -1 && errno == EPERM)   // VeraDroid: no CAP_WAKE_ALARM under vdnocap\n"
     "        wakealarm_fd = timerfd_create(CLOCK_BOOTTIME, TFD_NONBLOCK);\n"),
    # Clamp >100% capacity (max77796-battery reports 105 when full) and preserve mV voltage_now on i.MX7D Kindles
    ("system/core/healthd/BatteryMonitor.cpp",
     "    props.batteryLevel = mBatteryFixedCapacity ?\n"
     "        mBatteryFixedCapacity :\n"
     "        getIntField(mHealthdConfig->batteryCapacityPath);\n"
     "    props.batteryVoltage = getIntField(mHealthdConfig->batteryVoltagePath) / 1000;\n",
     "    props.batteryLevel = mBatteryFixedCapacity ?\n"
     "        mBatteryFixedCapacity :\n"
     "        getIntField(mHealthdConfig->batteryCapacityPath);\n"
     "    if (props.batteryLevel > 100) props.batteryLevel = 100; // VeraDroid: max77796-battery reports 105 when full\n"
     "    int rawMv = getIntField(mHealthdConfig->batteryVoltagePath);\n"
     "    props.batteryVoltage = (rawMv > 0 && rawMv < 20000) ? rawMv : rawMv / 1000; // VeraDroid: max77796-battery reports mV\n"),
    ("system/core/healthd/BatteryMonitor.cpp",
     "    case BATTERY_PROP_CAPACITY:\n"
     "        if (!mHealthdConfig->batteryCapacityPath.isEmpty()) {\n"
     "            val->valueInt64 =\n"
     "                getIntField(mHealthdConfig->batteryCapacityPath);\n"
     "            ret = NO_ERROR;\n",
     "    case BATTERY_PROP_CAPACITY:\n"
     "        if (!mHealthdConfig->batteryCapacityPath.isEmpty()) {\n"
     "            val->valueInt64 =\n"
     "                getIntField(mHealthdConfig->batteryCapacityPath);\n"
     "            if (val->valueInt64 > 100) val->valueInt64 = 100; // VeraDroid: max77796-battery reports 105 when full\n"
     "            ret = NO_ERROR;\n"),
    # hostns: ignore host udevd userspace uevent rebroadcasts (nl_pid != 0, uid == 0) and normalize chroot /rootfs/dev/null
    ("system/core/libsysutils/src/NetlinkListener.cpp",
     "        SLOGE(\"recvmsg failed (%s)\", strerror(errno));\n",
     "        if (errno != EIO || uid > 0) SLOGE(\"recvmsg failed (%s)\", strerror(errno)); // VeraDroid: ignore host udevd rebroadcasts\n"),
    ("frameworks/base/core/jni/fd_utils-inl.h",
     "    std::string file_path;\n"
     "    if (!Readlink(fd, &file_path)) {\n"
     "      return NULL;\n"
     "    }\n",
     "    std::string file_path;\n"
     "    if (!Readlink(fd, &file_path)) {\n"
     "      return NULL;\n"
     "    }\n"
     "    if (file_path.size() > 16 &&\n"
     "        file_path.compare(file_path.size() - 16, 16, \"/rootfs/dev/null\") == 0) {\n"
     "      file_path = \"/dev/null\"; // VeraDroid: normalize hostns chroot /dev/null\n"
     "    }\n"),
    # Deduplicate redundant RTM_NEWLINK "wlan0 link up" notifications in hostnet mode while still syncing real IP/route changes
    ("frameworks/opt/net/ethernet/java/com/android/server/ethernet/EthernetNetworkFactory.java",
     "    private void updateInterfaceState(String iface, boolean up) {\n"
     "        if (!mIface.equals(iface)) {\n"
     "            return;\n"
     "        }\n"
     "        Log.d(TAG, \"updateInterface: \" + iface + \" link \" + (up ? \"up\" : \"down\"));",
     "    private void updateInterfaceState(String iface, boolean up) {\n"
     "        if (!mIface.equals(iface)) {\n"
     "            return;\n"
     "        }\n"
     "        synchronized (this) {\n"
     "            if (up && mLinkUp && mNetworkAgent != null) {\n"
     "                if (VdHostNet.enabled()) {\n"
     "                    LinkProperties vdLp = VdHostNet.linkPropertiesQuiet(iface);\n"
     "                    if (vdLp != null && !vdLp.equals(mLinkProperties)) {\n"
     "                        mLinkProperties = vdLp;\n"
     "                        Log.i(TAG, \"VeraDroid hostnet: updated link properties: \" + vdLp);\n"
     "                        updateAgent();\n"
     "                    }\n"
     "                }\n"
     "                return;\n"
     "            }\n"
     "        }\n"
     "        Log.d(TAG, \"updateInterface: \" + iface + \" link \" + (up ? \"up\" : \"down\"));"),
    # Skip /sys/kernel/ipv4/tcp_* writes once proven absent/unwritable on Kindle kernels
    ("frameworks/base/services/core/java/com/android/server/ConnectivityService.java",
     "        if (values == null || values.length != 6) {\n"
     "            if (DBG) log(\"Invalid tcpBufferSizes string: \" + tcpBufferSizes +\", using defaults\");\n"
     "            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;\n"
     "            values = tcpBufferSizes.split(\",\");\n"
     "        }\n"
     "\n"
     "        if (tcpBufferSizes.equals(mCurrentTcpBufferSizes)) return;\n"
     "\n"
     "        try {\n"
     "            if (VDBG) Slog.d(TAG, \"Setting tx/rx TCP buffers to \" + tcpBufferSizes);\n"
     "\n"
     "            final String prefix = \"/sys/kernel/ipv4/tcp_\";",
     "        if (values == null || values.length != 6) {\n"
     "            if (DBG && tcpBufferSizes != null) log(\"Invalid tcpBufferSizes string: \" + tcpBufferSizes +\", using defaults\");\n"
     "            tcpBufferSizes = DEFAULT_TCP_BUFFER_SIZES;\n"
     "            values = tcpBufferSizes.split(\",\");\n"
     "        }\n"
     "\n"
     "        if (tcpBufferSizes.equals(mCurrentTcpBufferSizes)) return;\n"
     "        if (!new java.io.File(\"/sys/kernel/ipv4/tcp_rmem_min\").canWrite()) {\n"
     "            mCurrentTcpBufferSizes = tcpBufferSizes;\n"
     "            return;\n"
     "        }\n"
     "\n"
     "        try {\n"
     "            if (VDBG) Slog.d(TAG, \"Setting tx/rx TCP buffers to \" + tcpBufferSizes);\n"
     "\n"
     "            final String prefix = \"/sys/kernel/ipv4/tcp_\";"),
    # Negative-cache absent /proc/uid_cputime/show_uid_stat and Telecom/ContentProvider lookups; silence cutils-trace ENOENT
    ("frameworks/base/core/java/com/android/internal/os/KernelUidCpuTimeReader.java",
     "    private long mLastTimeReadUs = 0;\n"
     "\n"
     "    /**\n"
     "     * Reads the proc file, calling into the callback with a delta of time for each UID.",
     "    private long mLastTimeReadUs = 0;\n"
     "    private static boolean sVdNoUidCpuTime = false;\n"
     "\n"
     "    /**\n"
     "     * Reads the proc file, calling into the callback with a delta of time for each UID."),
    ("frameworks/base/core/java/com/android/internal/os/KernelUidCpuTimeReader.java",
     "    public void readDelta(@Nullable Callback callback) {\n"
     "        long nowUs = SystemClock.elapsedRealtime() * 1000;",
     "    public void readDelta(@Nullable Callback callback) {\n"
     "        if (sVdNoUidCpuTime) return;\n"
     "        long nowUs = SystemClock.elapsedRealtime() * 1000;"),
    ("frameworks/base/core/java/com/android/internal/os/KernelUidCpuTimeReader.java",
     "        } catch (IOException e) {\n"
     "            Slog.e(TAG, \"Failed to read uid_cputime: \" + e.getMessage());\n"
     "        }",
     "        } catch (IOException e) {\n"
     "            sVdNoUidCpuTime = true;\n"
     "        }"),
    ("frameworks/base/core/java/com/android/internal/os/KernelUidCpuTimeReader.java",
     "        try (FileWriter writer = new FileWriter(sRemoveUidProcFile)) {",
     "        if (sVdNoUidCpuTime) return;\n        try (FileWriter writer = new FileWriter(sRemoveUidProcFile)) {"),
    ("system/core/libcutils/trace-dev.c",
     "    if (atrace_marker_fd == -1) {\n"
     "        ALOGE(\"Error opening trace file: %s (%d)\", strerror(errno), errno);",
     "    if (atrace_marker_fd == -1) {\n"
     "        if (errno != ENOENT) ALOGE(\"Error opening trace file: %s (%d)\", strerror(errno), errno);"),
    ("frameworks/base/telecomm/java/android/telecom/TelecomManager.java",
     "    private ITelecomService getTelecomService() {\n"
     "        if (mTelecomServiceOverride != null) {\n"
     "            return mTelecomServiceOverride;\n"
     "        }\n"
     "        return ITelecomService.Stub.asInterface(ServiceManager.getService(Context.TELECOM_SERVICE));\n"
     "    }\n"
     "\n"
     "    private boolean isServiceConnected() {\n"
     "        boolean isConnected = getTelecomService() != null;\n"
     "        if (!isConnected) {\n"
     "            Log.w(TAG, \"Telecom Service not found.\");\n"
     "        }\n"
     "        return isConnected;\n"
     "    }",
     "    private static int sVdSlimNoTelecom = -1;\n"
     "    private ITelecomService getTelecomService() {\n"
     "        if (mTelecomServiceOverride != null) {\n"
     "            return mTelecomServiceOverride;\n"
     "        }\n"
     "        if (sVdSlimNoTelecom < 0) {\n"
     "            sVdSlimNoTelecom = android.os.SystemProperties.getInt(\"ro.vd.slim\", 0) > 0 &&\n"
     "                    ServiceManager.checkService(Context.TELECOM_SERVICE) == null ? 1 : 0;\n"
     "        }\n"
     "        if (sVdSlimNoTelecom == 1) return null;\n"
     "        return ITelecomService.Stub.asInterface(ServiceManager.getService(Context.TELECOM_SERVICE));\n"
     "    }\n"
     "\n"
     "    private boolean isServiceConnected() {\n"
     "        boolean isConnected = getTelecomService() != null;\n"
     "        if (!isConnected && sVdSlimNoTelecom != 1) {\n"
     "            Log.w(TAG, \"Telecom Service not found.\");\n"
     "        }\n"
     "        return isConnected;\n"
     "    }"),
    ("frameworks/base/core/java/android/app/ActivityThread.java",
     "    public final IContentProvider acquireProvider(\n"
     "            Context c, String auth, int userId, boolean stable) {\n"
     "        final IContentProvider provider = acquireExistingProvider(c, auth, userId, stable);\n"
     "        if (provider != null) {\n"
     "            return provider;\n"
     "        }",
     "    private final ArrayMap<String, Long> mVdMissingProviders = new ArrayMap<String, Long>();\n"
     "    public final IContentProvider acquireProvider(\n"
     "            Context c, String auth, int userId, boolean stable) {\n"
     "        final IContentProvider provider = acquireExistingProvider(c, auth, userId, stable);\n"
     "        if (provider != null) {\n"
     "            return provider;\n"
     "        }\n"
     "        if (auth != null) {\n"
     "            synchronized (mVdMissingProviders) {\n"
     "                Long ts = mVdMissingProviders.get(auth);\n"
     "                if (ts != null && SystemClock.uptimeMillis() - ts.longValue() < 60000L) {\n"
     "                    return null;\n"
     "                }\n"
     "            }\n"
     "        }"),
    ("frameworks/base/core/java/android/app/ActivityThread.java",
     "        if (holder == null) {\n"
     "            Slog.e(TAG, \"Failed to find provider info for \" + auth);\n"
     "            return null;\n"
     "        }",
     "        if (holder == null) {\n"
     "            if (auth != null) {\n"
     "                synchronized (mVdMissingProviders) {\n"
     "                    if (mVdMissingProviders.size() >= 16) mVdMissingProviders.clear();\n"
     "                    if (mVdMissingProviders.put(auth, SystemClock.uptimeMillis()) == null) {\n"
     "                        Slog.w(TAG, \"Failed to find provider info for \" + auth);\n"
     "                    }\n"
     "                }\n"
     "            }\n"
     "            return null;\n"
     "        }"),
    # Profile-guided full-speed AOT: when compiling with -m speed / everything, merge any collected runtime profile
    # and emit a pre-initialized LZ4 base.art App Image for hot startup classes while still compiling 100% of methods.
    ("frameworks/native/cmds/installd/commands.cpp",
     "    // Public apps should not be compiled with profile information ever. Same goes for the special\n"
     "    // package '*' used for the system server.\n"
     "    Dex2oatFileWrapper<std::function<void ()>> reference_profile_fd;\n"
     "    if (!is_public && pkgname[0] != '*') {\n"
     "        // Open reference profile in read only mode as dex2oat does not get write permissions.\n"
     "        const std::string pkgname_str(pkgname);\n"
     "        reference_profile_fd.reset(open_reference_profile(uid, pkgname, /*read_write*/ false),\n"
     "                                   [pkgname_str]() {\n"
     "                                       clear_reference_profile(pkgname_str.c_str());\n"
     "                                   });\n"
     "        // Note: it's OK to not find a profile here.\n"
     "    }",
     "    const bool vd_speed_app_image = !vm_safe_mode && !debuggable && pkgname[0] != '*' &&\n"
     "            dexopt_needed == DEXOPT_DEX2OAT_NEEDED && compiler_filter != nullptr &&\n"
     "            (strcmp(compiler_filter, \"speed\") == 0 || strcmp(compiler_filter, \"everything\") == 0);\n"
     "    if (vd_speed_app_image) {\n"
     "        (void)analyse_profiles(uid, pkgname);\n"
     "    }\n"
     "    // Public apps should not be compiled with profile information ever. Same goes for the special\n"
     "    // package '*' used for the system server.\n"
     "    Dex2oatFileWrapper<std::function<void ()>> reference_profile_fd;\n"
     "    if ((!is_public || vd_speed_app_image) && pkgname[0] != '*') {\n"
     "        // Open reference profile in read only mode as dex2oat does not get write permissions.\n"
     "        const std::string pkgname_str(pkgname);\n"
     "        int ref_fd = open_reference_profile(uid, pkgname, /*read_write*/ false);\n"
     "        if (vd_speed_app_image && ref_fd >= 0) {\n"
     "            struct stat ref_st;\n"
     "            if (fstat(ref_fd, &ref_st) != 0 || ref_st.st_size <= 0) {\n"
     "                close(ref_fd);\n"
     "                ref_fd = -1;\n"
     "            }\n"
     "        }\n"
     "        if (ref_fd >= 0) {\n"
     "            reference_profile_fd.reset(ref_fd,\n"
     "                                       [pkgname_str, vd_speed_app_image]() {\n"
     "                                           if (!vd_speed_app_image) {\n"
     "                                               clear_reference_profile(pkgname_str.c_str());\n"
     "                                           }\n"
     "                                       });\n"
     "        }\n"
     "        // Note: it's OK to not find a profile here.\n"
     "    }"),
    ("frameworks/native/cmds/installd/commands.cpp",
     "        if (profile_guided && have_app_image_format) {",
     "        if ((profile_guided || (vd_speed_app_image && reference_profile_fd.get() >= 0)) && have_app_image_format) {"),
    ("art/dex2oat/dex2oat.cc",
     "  bool UseProfileGuidedCompilation() const {\n"
     "    return CompilerFilter::DependsOnProfile(compiler_options_->GetCompilerFilter());\n"
     "  }\n"
     "\n"
     "  bool LoadProfile() {\n"
     "    DCHECK(UseProfileGuidedCompilation());",
     "  bool UseProfileGuidedCompilation() const {\n"
     "    return CompilerFilter::DependsOnProfile(compiler_options_->GetCompilerFilter());\n"
     "  }\n"
     "\n"
     "  bool HasProfileInput() const {\n"
     "    return profile_file_fd_ != -1 || !profile_file_.empty();\n"
     "  }\n"
     "\n"
     "  bool LoadProfile() {\n"
     "    DCHECK(UseProfileGuidedCompilation() || HasProfileInput());"),
    ("art/dex2oat/dex2oat.cc",
     "  if (dex2oat->UseProfileGuidedCompilation()) {\n"
     "    if (!dex2oat->LoadProfile()) {",
     "  if (dex2oat->UseProfileGuidedCompilation() || (dex2oat->IsAppImage() && dex2oat->HasProfileInput())) {\n"
     "    if (!dex2oat->LoadProfile()) {"),
    ("art/compiler/driver/compiler_driver.cc",
     "bool CompilerDriver::ShouldCompileBasedOnProfile(const MethodReference& method_ref) const {\n"
     "  if (profile_compilation_info_ == nullptr) {",
     "bool CompilerDriver::ShouldCompileBasedOnProfile(const MethodReference& method_ref) const {\n"
     "  if (!CompilerFilter::DependsOnProfile(compiler_options_->GetCompilerFilter())) {\n"
     "    return true;\n"
     "  }\n"
     "  if (profile_compilation_info_ == nullptr) {"),
    # LowMemoryMode forces HeapGrowthMultiplier() to 1.0 even for the single foreground jank-perceptible app,
    # causing back-to-back Sticky + Partial CMS GCs (~1.5-3s CPU stall) during cold start, book open, and 8.1MB
    # full-screen bitmap page turns. Keep 1.0x for background processes, and grant full 2.0x headroom to foreground.
    ("art/runtime/gc/heap.cc",
     "double Heap::HeapGrowthMultiplier() const {\n"
     "  // If we don't care about pause times we are background, so return 1.0.\n"
     "  if (!CareAboutPauseTimes() || IsLowMemoryMode()) {\n"
     "    return 1.0;\n"
     "  }\n"
     "  return foreground_heap_growth_multiplier_;\n"
     "}",
     "double Heap::HeapGrowthMultiplier() const {\n"
     "  // If we don't care about pause times we are background, so return 1.0.\n"
     "  if (!CareAboutPauseTimes()) {\n"
     "    return 1.0;\n"
     "  }\n"
     "  if (IsLowMemoryMode()) {\n"
     "    return 1.35;\n"
     "  }\n"
     "  return foreground_heap_growth_multiplier_;\n"
     "}"),
    # Disable stop-the-world HomogeneousSpaceCompact / CollectorTransition under LowMemoryMode:
    # background service processes (such as WeRead's :seg word-segmentation process) froze for 2.03 s in
    # PerformHomogeneousSpaceCompact() right when opening a book, while reserving 160-192 MB backup VM space.
    ("art/runtime/gc/heap.cc",
     "  bool support_homogeneous_space_compaction =\n"
     "      background_collector_type_ == gc::kCollectorTypeHomogeneousSpaceCompact ||\n"
     "      use_homogeneous_space_compaction_for_oom_;",
     "  if (background_collector_type_ != foreground_collector_type_) {\n"
     "    background_collector_type_ = foreground_collector_type_;\n"
     "  }\n"
     "  use_homogeneous_space_compaction_for_oom_ = false;\n"
     "  bool support_homogeneous_space_compaction =\n"
     "      background_collector_type_ == gc::kCollectorTypeHomogeneousSpaceCompact ||\n"
     "      use_homogeneous_space_compaction_for_oom_;"),
    ("art/runtime/jit/jit.cc",
     "static constexpr int kJitPoolThreadPthreadPriority = 9;",
     "static constexpr int kJitPoolThreadPthreadPriority = 19; // VeraDroid: yield CPU to UI main thread"),
    # Lock-free per-thread Single-Glyph fastpath + zero-heap stack buffers for per-character reader engines
    # (WeRead's CharElement.drawText / measureText invokes Canvas.drawText(char[], idx, 1) ~800x per page).
    ("frameworks/base/libs/hwui/hwui/Paint.h",
     "    std::string getFontFeatureSettings() const {\n"
     "        return mFontFeatureSettings;\n"
     "    }",
     "    const std::string& getFontFeatureSettings() const {\n"
     "        return mFontFeatureSettings;\n"
     "    }\n"
     "    bool hasFontFeatureSettings() const {\n"
     "        return !mFontFeatureSettings.empty();\n"
     "    }"),
    ("frameworks/base/libs/hwui/hwui/MinikinUtils.h",
     "class MinikinUtils {\n"
     "public:",
     "struct VdSingleGlyph {\n"
     "    MinikinFont* font;\n"
     "    FontFakery fakery;\n"
     "    uint16_t glyphId;\n"
     "    float gx;\n"
     "    float gy;\n"
     "    float advance;\n"
     "};\n"
     "\n"
     "class MinikinUtils {\n"
     "public:\n"
     "    ANDROID_API static bool lookupSingleChar(const Paint* paint, int bidiFlags,\n"
     "            Typeface* typeface, uint16_t ch, VdSingleGlyph* out);\n"
     "    ANDROID_API static void purgeSingleCharCache();"),
    ("frameworks/base/libs/hwui/hwui/MinikinUtils.cpp",
     "#include <cutils/log.h>\n"
     "#include <string>",
     "#include <atomic>\n"
     "#include <cstdlib>\n"
     "#include <cstring>\n"
     "#include <pthread.h>\n"
     "#include <cutils/log.h>\n"
     "#include <string>"),
    ("frameworks/base/libs/hwui/hwui/MinikinUtils.cpp",
     "    minikinPaint->size = (int)paint->getTextSize();",
     "    minikinPaint->size = paint->isLinearText() ? paint->getTextSize() : (int)paint->getTextSize();"),
    ("frameworks/base/libs/hwui/hwui/MinikinUtils.cpp",
     "float MinikinUtils::measureText(const Paint* paint, int bidiFlags, Typeface* typeface,\n"
     "        const uint16_t* buf, size_t start, size_t count, size_t bufSize, float *advances) {\n"
     "    FontCollection *font;\n"
     "    MinikinPaint minikinPaint;\n"
     "    FontStyle minikinStyle = prepareMinikinPaint(&minikinPaint, &font, paint, typeface);\n"
     "    return Layout::measureText(buf, start, count, bufSize, bidiFlags, minikinStyle, minikinPaint,\n"
     "            font, advances);\n"
     "}",
     "namespace {\n"
     "struct VdSingleGlyphSlot {\n"
     "    uint32_t gen;\n"
     "    uint32_t collectionId;\n"
     "    FontStyle style;\n"
     "    uint32_t sizeBits;\n"
     "    uint32_t scaleXBits;\n"
     "    uint32_t skewXBits;\n"
     "    uint32_t letterSpacingBits;\n"
     "    uint32_t paintFlags;\n"
     "    uint32_t hyphenEdit;\n"
     "    uint16_t ch;\n"
     "    uint16_t bidi;\n"
     "    VdSingleGlyph val;\n"
     "};\n"
     "struct VdSingleGlyphCache {\n"
     "    VdSingleGlyphSlot slots[2048];\n"
     "};\n"
     "static std::atomic<uint32_t> gVdSingleGlyphGen(1);\n"
     "static pthread_key_t gVdSingleGlyphKey;\n"
     "static pthread_once_t gVdSingleGlyphOnce = PTHREAD_ONCE_INIT;\n"
     "static void destroyVdSingleGlyphCache(void* p) { std::free(p); }\n"
     "static void initVdSingleGlyphKey() { pthread_key_create(&gVdSingleGlyphKey, destroyVdSingleGlyphCache); }\n"
     "static inline bool isSimpleSingleBmp(uint16_t ch) {\n"
     "    return (ch >= 0x0020 && ch <= 0x024F) ||\n"
     "           (ch >= 0x2000 && ch <= 0x206F) ||\n"
     "           (ch >= 0x3000 && ch <= 0x9FFF) ||\n"
     "           (ch >= 0xFF00 && ch <= 0xFFEF);\n"
     "}\n"
     "static inline uint32_t f2u32(float v) { uint32_t u; std::memcpy(&u, &v, sizeof(u)); return u; }\n"
     "} // namespace\n"
     "\n"
     "void MinikinUtils::purgeSingleCharCache() {\n"
     "    gVdSingleGlyphGen.fetch_add(1, std::memory_order_relaxed);\n"
     "}\n"
     "\n"
     "bool MinikinUtils::lookupSingleChar(const Paint* paint, int bidiFlags,\n"
     "        Typeface* typeface, uint16_t ch, VdSingleGlyph* out) {\n"
     "    if (!isSimpleSingleBmp(ch) || paint->hasFontFeatureSettings()) return false;\n"
     "    const Typeface* resolvedFace = Typeface::resolveDefault(typeface);\n"
     "    FontCollection* font = resolvedFace->fFontCollection;\n"
     "    if (!font) return false;\n"
     "    const uint32_t collectionId = font->getId();\n"
     "    FontStyle resolved = resolvedFace->fStyle;\n"
     "    FontVariant variant = (paint->getFontVariant() == VARIANT_ELEGANT) ? VARIANT_ELEGANT : VARIANT_COMPACT;\n"
     "    FontStyle minikinStyle(paint->getMinikinLangListId(), variant, resolved.getWeight(), resolved.getItalic());\n"
     "    const float size = paint->isLinearText() ? paint->getTextSize() : (int)paint->getTextSize();\n"
     "    const uint32_t sizeBits = f2u32(size);\n"
     "    const uint32_t scaleXBits = f2u32(paint->getTextScaleX());\n"
     "    const uint32_t skewXBits = f2u32(paint->getTextSkewX());\n"
     "    const uint32_t lsBits = f2u32(paint->getLetterSpacing());\n"
     "    const uint32_t paintFlags = MinikinFontSkia::packPaintFlags(paint);\n"
     "    const uint32_t hyphenEdit = paint->getHyphenEdit();\n"
     "    const uint16_t bidi = (uint16_t)(bidiFlags & 0x7);\n"
     "    pthread_once(&gVdSingleGlyphOnce, initVdSingleGlyphKey);\n"
     "    VdSingleGlyphCache* cache = static_cast<VdSingleGlyphCache*>(pthread_getspecific(gVdSingleGlyphKey));\n"
     "    if (CC_UNLIKELY(!cache)) {\n"
     "        cache = static_cast<VdSingleGlyphCache*>(std::calloc(1, sizeof(VdSingleGlyphCache)));\n"
     "        if (!cache) return false;\n"
     "        pthread_setspecific(gVdSingleGlyphKey, cache);\n"
     "    }\n"
     "    const uint32_t gen = gVdSingleGlyphGen.load(std::memory_order_relaxed);\n"
     "    const uint32_t idx = (((uint32_t)ch * 2654435761u) ^ (collectionId * 2246822519u) ^\n"
     "                          (sizeBits * 3266489917u) ^ paintFlags ^ (uint32_t)minikinStyle.hash()) & 2047u;\n"
     "    VdSingleGlyphSlot& slot = cache->slots[idx];\n"
     "    if (CC_LIKELY(slot.gen == gen && slot.ch == ch && slot.collectionId == collectionId &&\n"
     "                  slot.sizeBits == sizeBits && slot.paintFlags == paintFlags && slot.style == minikinStyle &&\n"
     "                  slot.scaleXBits == scaleXBits && slot.skewXBits == skewXBits &&\n"
     "                  slot.letterSpacingBits == lsBits && slot.hyphenEdit == hyphenEdit && slot.bidi == bidi)) {\n"
     "        *out = slot.val;\n"
     "        return true;\n"
     "    }\n"
     "    MinikinPaint minikinPaint;\n"
     "    minikinPaint.size = size;\n"
     "    minikinPaint.scaleX = paint->getTextScaleX();\n"
     "    minikinPaint.skewX = paint->getTextSkewX();\n"
     "    minikinPaint.letterSpacing = paint->getLetterSpacing();\n"
     "    minikinPaint.paintFlags = paintFlags;\n"
     "    minikinPaint.hyphenEdit = HyphenEdit(hyphenEdit);\n"
     "    Layout layout;\n"
     "    layout.setFontCollection(font);\n"
     "    layout.doLayout(&ch, 0, 1, 1, bidiFlags, minikinStyle, minikinPaint);\n"
     "    if (layout.nGlyphs() != 1) return false;\n"
     "    VdSingleGlyph v;\n"
     "    v.font = layout.getFont(0);\n"
     "    v.fakery = layout.getFakery(0);\n"
     "    v.glyphId = static_cast<uint16_t>(layout.getGlyphId(0));\n"
     "    v.gx = layout.getX(0);\n"
     "    v.gy = layout.getY(0);\n"
     "    v.advance = layout.getAdvance();\n"
     "    slot.gen = gen;\n"
     "    slot.collectionId = collectionId;\n"
     "    slot.style = minikinStyle;\n"
     "    slot.sizeBits = sizeBits;\n"
     "    slot.scaleXBits = scaleXBits;\n"
     "    slot.skewXBits = skewXBits;\n"
     "    slot.letterSpacingBits = lsBits;\n"
     "    slot.paintFlags = paintFlags;\n"
     "    slot.hyphenEdit = hyphenEdit;\n"
     "    slot.ch = ch;\n"
     "    slot.bidi = bidi;\n"
     "    slot.val = v;\n"
     "    *out = v;\n"
     "    return true;\n"
     "}\n"
     "\n"
     "float MinikinUtils::measureText(const Paint* paint, int bidiFlags, Typeface* typeface,\n"
     "        const uint16_t* buf, size_t start, size_t count, size_t bufSize, float *advances) {\n"
     "    if (count == 1 && bufSize == 1 && start == 0) {\n"
     "        VdSingleGlyph sg;\n"
     "        if (lookupSingleChar(paint, bidiFlags, typeface, buf[0], &sg)) {\n"
     "            if (advances) advances[0] = sg.advance;\n"
     "            return sg.advance;\n"
     "        }\n"
     "    }\n"
     "    FontCollection *font;\n"
     "    MinikinPaint minikinPaint;\n"
     "    FontStyle minikinStyle = prepareMinikinPaint(&minikinPaint, &font, paint, typeface);\n"
     "    return Layout::measureText(buf, start, count, bufSize, bidiFlags, minikinStyle, minikinPaint,\n"
     "            font, advances);\n"
     "}"),
    ("frameworks/base/libs/hwui/hwui/Canvas.cpp",
     "void Canvas::drawText(const uint16_t* text, int start, int count, int contextCount,\n"
     "        float x, float y, int bidiFlags, const Paint& origPaint, Typeface* typeface) {\n"
     "    // minikin may modify the original paint\n"
     "    Paint paint(origPaint);\n"
     "\n"
     "    Layout layout;\n"
     "    MinikinUtils::doLayout(&layout, &paint, bidiFlags, typeface, text, start, count, contextCount);\n"
     "\n"
     "    size_t nGlyphs = layout.nGlyphs();\n"
     "    std::unique_ptr<uint16_t[]> glyphs(new uint16_t[nGlyphs]);\n"
     "    std::unique_ptr<float[]> pos(new float[nGlyphs * 2]);\n"
     "\n"
     "    x += MinikinUtils::xOffsetForTextAlign(&paint, layout);\n"
     "\n"
     "    MinikinRect bounds;\n"
     "    layout.getBounds(&bounds);\n"
     "    if (!drawTextAbsolutePos()) {\n"
     "        bounds.offset(x, y);\n"
     "    }",
     "void Canvas::drawText(const uint16_t* text, int start, int count, int contextCount,\n"
     "        float x, float y, int bidiFlags, const Paint& origPaint, Typeface* typeface) {\n"
     "    if (count <= 0) return;\n"
     "    if (drawTextAbsolutePos() && count == 1 && contextCount == 1 && !isHighContrastText()) {\n"
     "        VdSingleGlyph sg;\n"
     "        if (MinikinUtils::lookupSingleChar(&origPaint, bidiFlags, typeface, text[start], &sg)) {\n"
     "            Paint tmpPaint(static_cast<const SkPaint&>(origPaint));\n"
     "            switch (tmpPaint.getTextAlign()) {\n"
     "                case Paint::kCenter_Align: x -= sg.advance * 0.5f; break;\n"
     "                case Paint::kRight_Align:  x -= sg.advance; break;\n"
     "                default: break;\n"
     "            }\n"
     "            tmpPaint.setTextAlign(Paint::kLeft_Align);\n"
     "            MinikinFontSkia::populateSkPaint(&tmpPaint, sg.font, sg.fakery);\n"
     "            float pt[2] = { x + sg.gx, y + sg.gy };\n"
     "            drawGlyphs(&sg.glyphId, pt, 1, tmpPaint, x, y, 0.0f, 0.0f, 0.0f, 0.0f, sg.advance);\n"
     "            return;\n"
     "        }\n"
     "    }\n"
     "    // minikin may modify the original paint\n"
     "    Paint paint(origPaint);\n"
     "\n"
     "    Layout layout;\n"
     "    MinikinUtils::doLayout(&layout, &paint, bidiFlags, typeface, text, start, count, contextCount);\n"
     "\n"
     "    size_t nGlyphs = layout.nGlyphs();\n"
     "    uint16_t stackGlyphs[64];\n"
     "    float stackPos[128];\n"
     "    std::unique_ptr<uint16_t[]> heapGlyphs;\n"
     "    std::unique_ptr<float[]> heapPos;\n"
     "    uint16_t* glyphPtr = stackGlyphs;\n"
     "    float* posPtr = stackPos;\n"
     "    if (CC_UNLIKELY(nGlyphs > 64)) {\n"
     "        heapGlyphs.reset(new uint16_t[nGlyphs]);\n"
     "        heapPos.reset(new float[nGlyphs * 2]);\n"
     "        glyphPtr = heapGlyphs.get();\n"
     "        posPtr = heapPos.get();\n"
     "    }\n"
     "\n"
     "    x += MinikinUtils::xOffsetForTextAlign(&paint, layout);\n"
     "\n"
     "    MinikinRect bounds;\n"
     "    if (!drawTextAbsolutePos()) {\n"
     "        layout.getBounds(&bounds);\n"
     "        bounds.offset(x, y);\n"
     "    } else {\n"
     "        bounds.setEmpty();\n"
     "    }"),
    ("frameworks/base/libs/hwui/hwui/Canvas.cpp",
     "    DrawTextFunctor f(layout, this, glyphs.get(), pos.get(),\n"
     "            paint, x, y, bounds, layout.getAdvance());",
     "    DrawTextFunctor f(layout, this, glyphPtr, posPtr,\n"
     "            paint, x, y, bounds, layout.getAdvance());"),
    ("frameworks/base/core/jni/android_graphics_Canvas.cpp",
     "#include <hwui/Canvas.h>",
     "#include <hwui/Canvas.h>\n"
     "#include <hwui/MinikinUtils.h>"),
    ("frameworks/base/core/jni/android_graphics_Canvas.cpp",
     "static void drawTextChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray text,\n"
     "                          jint index, jint count, jfloat x, jfloat y, jint bidiFlags,\n"
     "                          jlong paintHandle, jlong typefaceHandle) {\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "    jchar* jchars = env->GetCharArrayElements(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + index, 0, count, count, x, y,\n"
     "                                       bidiFlags, *paint, typeface);\n"
     "    env->ReleaseCharArrayElements(text, jchars, JNI_ABORT);\n"
     "}\n"
     "\n"
     "static void drawTextString(JNIEnv* env, jobject, jlong canvasHandle, jstring text,\n"
     "                           jint start, jint end, jfloat x, jfloat y, jint bidiFlags,\n"
     "                           jlong paintHandle, jlong typefaceHandle) {\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "    const int count = end - start;\n"
     "    const jchar* jchars = env->GetStringChars(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + start, 0, count, count, x, y,\n"
     "                                       bidiFlags, *paint, typeface);\n"
     "    env->ReleaseStringChars(text, jchars);\n"
     "}\n"
     "\n"
     "static void drawTextRunChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray text, jint index,\n"
     "                             jint count, jint contextIndex, jint contextCount, jfloat x, jfloat y,\n"
     "                             jboolean isRtl, jlong paintHandle, jlong typefaceHandle) {\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "\n"
     "    const int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;\n"
     "    jchar* jchars = env->GetCharArrayElements(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + contextIndex, index - contextIndex, count,\n"
     "                                       contextCount, x, y, bidiFlags, *paint, typeface);\n"
     "    env->ReleaseCharArrayElements(text, jchars, JNI_ABORT);\n"
     "}\n"
     "\n"
     "static void drawTextRunString(JNIEnv* env, jobject obj, jlong canvasHandle, jstring text,\n"
     "                              jint start, jint end, jint contextStart, jint contextEnd,\n"
     "                              jfloat x, jfloat y, jboolean isRtl, jlong paintHandle,\n"
     "                              jlong typefaceHandle) {\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "\n"
     "    int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;\n"
     "    jint count = end - start;\n"
     "    jint contextCount = contextEnd - contextStart;\n"
     "    const jchar* jchars = env->GetStringChars(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + contextStart, start - contextStart, count,\n"
     "                                       contextCount, x, y, bidiFlags, *paint, typeface);\n"
     "    env->ReleaseStringChars(text, jchars);\n"
     "}",
     "static void drawTextChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray text,\n"
     "                          jint index, jint count, jfloat x, jfloat y, jint bidiFlags,\n"
     "                          jlong paintHandle, jlong typefaceHandle) {\n"
     "    if (count <= 0 || text == NULL) return;\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "    if (CC_LIKELY(index >= 0 && count <= 64)) {\n"
     "        jchar buf[64];\n"
     "        env->GetCharArrayRegion(text, index, count, buf);\n"
     "        if (CC_UNLIKELY(env->ExceptionCheck())) return;\n"
     "        get_canvas(canvasHandle)->drawText(buf, 0, count, count, x, y, bidiFlags, *paint, typeface);\n"
     "        return;\n"
     "    }\n"
     "    jchar* jchars = env->GetCharArrayElements(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + index, 0, count, count, x, y,\n"
     "                                       bidiFlags, *paint, typeface);\n"
     "    env->ReleaseCharArrayElements(text, jchars, JNI_ABORT);\n"
     "}\n"
     "\n"
     "static void drawTextString(JNIEnv* env, jobject, jlong canvasHandle, jstring text,\n"
     "                           jint start, jint end, jfloat x, jfloat y, jint bidiFlags,\n"
     "                           jlong paintHandle, jlong typefaceHandle) {\n"
     "    const int count = end - start;\n"
     "    if (count <= 0 || text == NULL) return;\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "    if (CC_LIKELY(start >= 0 && count <= 64)) {\n"
     "        jchar buf[64];\n"
     "        env->GetStringRegion(text, start, count, buf);\n"
     "        if (CC_UNLIKELY(env->ExceptionCheck())) return;\n"
     "        get_canvas(canvasHandle)->drawText(buf, 0, count, count, x, y, bidiFlags, *paint, typeface);\n"
     "        return;\n"
     "    }\n"
     "    const jchar* jchars = env->GetStringChars(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + start, 0, count, count, x, y,\n"
     "                                       bidiFlags, *paint, typeface);\n"
     "    env->ReleaseStringChars(text, jchars);\n"
     "}\n"
     "\n"
     "static void drawTextRunChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray text, jint index,\n"
     "                             jint count, jint contextIndex, jint contextCount, jfloat x, jfloat y,\n"
     "                             jboolean isRtl, jlong paintHandle, jlong typefaceHandle) {\n"
     "    if (count <= 0 || text == NULL) return;\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "    const int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;\n"
     "    if (CC_LIKELY(contextIndex >= 0 && contextCount > 0 && contextCount <= 64 &&\n"
     "                  index >= contextIndex && index - contextIndex + count <= contextCount)) {\n"
     "        jchar buf[64];\n"
     "        env->GetCharArrayRegion(text, contextIndex, contextCount, buf);\n"
     "        if (CC_UNLIKELY(env->ExceptionCheck())) return;\n"
     "        get_canvas(canvasHandle)->drawText(buf, index - contextIndex, count,\n"
     "                                           contextCount, x, y, bidiFlags, *paint, typeface);\n"
     "        return;\n"
     "    }\n"
     "    jchar* jchars = env->GetCharArrayElements(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + contextIndex, index - contextIndex, count,\n"
     "                                       contextCount, x, y, bidiFlags, *paint, typeface);\n"
     "    env->ReleaseCharArrayElements(text, jchars, JNI_ABORT);\n"
     "}\n"
     "\n"
     "static void drawTextRunString(JNIEnv* env, jobject obj, jlong canvasHandle, jstring text,\n"
     "                              jint start, jint end, jint contextStart, jint contextEnd,\n"
     "                              jfloat x, jfloat y, jboolean isRtl, jlong paintHandle,\n"
     "                              jlong typefaceHandle) {\n"
     "    jint count = end - start;\n"
     "    jint contextCount = contextEnd - contextStart;\n"
     "    if (count <= 0 || text == NULL) return;\n"
     "    Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "    Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "    int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;\n"
     "    if (CC_LIKELY(contextStart >= 0 && contextCount > 0 && contextCount <= 64 &&\n"
     "                  start >= contextStart && start - contextStart + count <= contextCount)) {\n"
     "        jchar buf[64];\n"
     "        env->GetStringRegion(text, contextStart, contextCount, buf);\n"
     "        if (CC_UNLIKELY(env->ExceptionCheck())) return;\n"
     "        get_canvas(canvasHandle)->drawText(buf, start - contextStart, count,\n"
     "                                           contextCount, x, y, bidiFlags, *paint, typeface);\n"
     "        return;\n"
     "    }\n"
     "    const jchar* jchars = env->GetStringChars(text, NULL);\n"
     "    get_canvas(canvasHandle)->drawText(jchars + contextStart, start - contextStart, count,\n"
     "                                       contextCount, x, y, bidiFlags, *paint, typeface);\n"
     "    env->ReleaseStringChars(text, jchars);\n"
     "}"),
    ("frameworks/base/core/jni/android_graphics_Canvas.cpp",
     "static void freeTextLayoutCaches(JNIEnv* env, jobject) {\n"
     "    Layout::purgeCaches();\n"
     "}",
     "static void freeTextLayoutCaches(JNIEnv* env, jobject) {\n"
     "    Layout::purgeCaches();\n"
     "    MinikinUtils::purgeSingleCharCache();\n"
     "}"),
    ("frameworks/base/core/jni/android/graphics/Paint.cpp",
     "        std::unique_ptr<jfloat[]> advancesArray;\n"
     "        if (advances) {\n"
     "            advancesArray.reset(new jfloat[count]);\n"
     "        }\n"
     "        const float advance = MinikinUtils::measureText(paint, bidiFlags, typeface, text,\n"
     "                start, count, contextCount, advancesArray.get());\n"
     "        if (advances) {\n"
     "            env->SetFloatArrayRegion(advances, advancesIndex, count, advancesArray.get());\n"
     "        }\n"
     "        return advance;\n"
     "    }\n"
     "\n"
     "    static jfloat getTextAdvances___CIIIII_FI(JNIEnv* env, jobject clazz, jlong paintHandle,\n"
     "            jlong typefaceHandle,\n"
     "            jcharArray text, jint index, jint count, jint contextIndex, jint contextCount,\n"
     "            jint bidiFlags, jfloatArray advances, jint advancesIndex) {\n"
     "        Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "        Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "        jchar* textArray = env->GetCharArrayElements(text, NULL);\n"
     "        jfloat result = doTextAdvances(env, paint, typeface, textArray + contextIndex,\n"
     "                index - contextIndex, count, contextCount, bidiFlags, advances, advancesIndex);\n"
     "        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);\n"
     "        return result;\n"
     "    }\n"
     "\n"
     "    static jfloat getTextAdvances__StringIIIII_FI(JNIEnv* env, jobject clazz, jlong paintHandle,\n"
     "            jlong typefaceHandle,\n"
     "            jstring text, jint start, jint end, jint contextStart, jint contextEnd, jint bidiFlags,\n"
     "            jfloatArray advances, jint advancesIndex) {\n"
     "        Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "        Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "        const jchar* textArray = env->GetStringChars(text, NULL);\n"
     "        jfloat result = doTextAdvances(env, paint, typeface, textArray + contextStart,\n"
     "                start - contextStart, end - start, contextEnd - contextStart, bidiFlags,\n"
     "                advances, advancesIndex);\n"
     "        env->ReleaseStringChars(text, textArray);\n"
     "        return result;\n"
     "    }",
     "        jfloat stackAdv[64];\n"
     "        std::unique_ptr<jfloat[]> advancesArray;\n"
     "        jfloat* advPtr = nullptr;\n"
     "        if (advances) {\n"
     "            if (CC_LIKELY(count <= 64)) advPtr = stackAdv;\n"
     "            else { advancesArray.reset(new jfloat[count]); advPtr = advancesArray.get(); }\n"
     "        }\n"
     "        const float advance = MinikinUtils::measureText(paint, bidiFlags, typeface, text,\n"
     "                start, count, contextCount, advPtr);\n"
     "        if (advances) {\n"
     "            env->SetFloatArrayRegion(advances, advancesIndex, count, advPtr);\n"
     "        }\n"
     "        return advance;\n"
     "    }\n"
     "\n"
     "    static jfloat getTextAdvances___CIIIII_FI(JNIEnv* env, jobject clazz, jlong paintHandle,\n"
     "            jlong typefaceHandle,\n"
     "            jcharArray text, jint index, jint count, jint contextIndex, jint contextCount,\n"
     "            jint bidiFlags, jfloatArray advances, jint advancesIndex) {\n"
     "        if (text == NULL) { NPE_CHECK_RETURN_ZERO(env, text); }\n"
     "        Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "        Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "        if (CC_LIKELY(contextIndex >= 0 && contextCount >= 0 && contextCount <= 64)) {\n"
     "            jchar buf[64];\n"
     "            if (contextCount > 0) {\n"
     "                env->GetCharArrayRegion(text, contextIndex, contextCount, buf);\n"
     "                if (CC_UNLIKELY(env->ExceptionCheck())) return 0;\n"
     "            }\n"
     "            return doTextAdvances(env, paint, typeface, buf,\n"
     "                    index - contextIndex, count, contextCount, bidiFlags, advances, advancesIndex);\n"
     "        }\n"
     "        jchar* textArray = env->GetCharArrayElements(text, NULL);\n"
     "        jfloat result = doTextAdvances(env, paint, typeface, textArray + contextIndex,\n"
     "                index - contextIndex, count, contextCount, bidiFlags, advances, advancesIndex);\n"
     "        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);\n"
     "        return result;\n"
     "    }\n"
     "\n"
     "    static jfloat getTextAdvances__StringIIIII_FI(JNIEnv* env, jobject clazz, jlong paintHandle,\n"
     "            jlong typefaceHandle,\n"
     "            jstring text, jint start, jint end, jint contextStart, jint contextEnd, jint bidiFlags,\n"
     "            jfloatArray advances, jint advancesIndex) {\n"
     "        if (text == NULL) { NPE_CHECK_RETURN_ZERO(env, text); }\n"
     "        Paint* paint = reinterpret_cast<Paint*>(paintHandle);\n"
     "        Typeface* typeface = reinterpret_cast<Typeface*>(typefaceHandle);\n"
     "        jint contextCount = contextEnd - contextStart;\n"
     "        if (CC_LIKELY(contextStart >= 0 && contextCount >= 0 && contextCount <= 64)) {\n"
     "            jchar buf[64];\n"
     "            if (contextCount > 0) {\n"
     "                env->GetStringRegion(text, contextStart, contextCount, buf);\n"
     "                if (CC_UNLIKELY(env->ExceptionCheck())) return 0;\n"
     "            }\n"
     "            return doTextAdvances(env, paint, typeface, buf,\n"
     "                    start - contextStart, end - start, contextCount, bidiFlags,\n"
     "                    advances, advancesIndex);\n"
     "        }\n"
     "        const jchar* textArray = env->GetStringChars(text, NULL);\n"
     "        jfloat result = doTextAdvances(env, paint, typeface, textArray + contextStart,\n"
     "                start - contextStart, end - start, contextEnd - contextStart, bidiFlags,\n"
     "                advances, advancesIndex);\n"
     "        env->ReleaseStringChars(text, textArray);\n"
     "        return result;\n"
     "    }"),
    # Lock-free 1024-slot cache for Class.isAnnotationPresent() (eliminates thousands of JNI/Dex annotation scans during WeRead QMUISkin/FontSize tree walks).
    ("libcore/ojluni/src/main/java/java/lang/Class.java",
     "    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {\n"
     "        if (annotationType == null) {\n"
     "            throw new NullPointerException(\"annotationType == null\");\n"
     "        }\n"
     "\n"
     "        if (isDeclaredAnnotationPresent(annotationType)) {\n"
     "            return true;\n"
     "        }\n"
     "\n"
     "        if (annotationType.isDeclaredAnnotationPresent(Inherited.class)) {\n"
     "            for (Class<?> sup = getSuperclass(); sup != null; sup = sup.getSuperclass()) {\n"
     "                if (sup.isDeclaredAnnotationPresent(annotationType)) {\n"
     "                    return true;\n"
     "                }\n"
     "            }\n"
     "        }\n"
     "\n"
     "        return false;\n"
     "    }",
     "    private static final class VdAnnoHit {\n"
     "        static final VdAnnoHit[] CACHE = new VdAnnoHit[1024];\n"
     "        final java.lang.ref.WeakReference<Class<?>> cls;\n"
     "        final java.lang.ref.WeakReference<Class<?>> anno;\n"
     "        final boolean res;\n"
     "        VdAnnoHit(Class<?> c, Class<?> a, boolean r) {\n"
     "            cls = new java.lang.ref.WeakReference<Class<?>>(c);\n"
     "            anno = new java.lang.ref.WeakReference<Class<?>>(a);\n"
     "            res = r;\n"
     "        }\n"
     "    }\n"
     "\n"
     "    @Override public boolean isAnnotationPresent(Class<? extends Annotation> annotationType) {\n"
     "        if (annotationType == null) {\n"
     "            throw new NullPointerException(\"annotationType == null\");\n"
     "        }\n"
     "        final int slot = ((System.identityHashCode(this) * 31) ^ System.identityHashCode(annotationType)) & 1023;\n"
     "        final VdAnnoHit hit = VdAnnoHit.CACHE[slot];\n"
     "        if (hit != null && hit.cls.get() == this && hit.anno.get() == annotationType) {\n"
     "            return hit.res;\n"
     "        }\n"
     "        boolean found = isDeclaredAnnotationPresent(annotationType);\n"
     "        if (!found && annotationType.isDeclaredAnnotationPresent(Inherited.class)) {\n"
     "            for (Class<?> sup = getSuperclass(); sup != null; sup = sup.getSuperclass()) {\n"
     "                if (sup.isDeclaredAnnotationPresent(annotationType)) {\n"
     "                    found = true;\n"
     "                    break;\n"
     "                }\n"
     "            }\n"
     "        }\n"
     "        VdAnnoHit.CACHE[slot] = new VdAnnoHit(this, annotationType, found);\n"
     "        return found;\n"
     "    }"),
    # Bypass 3 guaranteed ClassNotFoundExceptions per <View>/<ViewStub>/<SurfaceView>/<TextureView> tag in PhoneLayoutInflater.
    ("frameworks/base/core/java/com/android/internal/policy/PhoneLayoutInflater.java",
     "    @Override protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {\n"
     "        for (String prefix : sClassPrefixList) {",
     "    @Override protected View onCreateView(String name, AttributeSet attrs) throws ClassNotFoundException {\n"
     "        if (\"View\".equals(name) || \"ViewStub\".equals(name) || \"SurfaceView\".equals(name) || \"TextureView\".equals(name)) {\n"
     "            return super.onCreateView(name, attrs);\n"
     "        }\n"
     "        for (String prefix : sClassPrefixList) {"),
    # Widen ArrayUtils.emptyArray() cache from 73 to 521 buckets so TextView.setText/getSpans stops colliding into JNI Array.newInstance.
    ("frameworks/base/core/java/com/android/internal/util/ArrayUtils.java",
     "    private static final int CACHE_SIZE = 73;",
     "    private static final int CACHE_SIZE = 521;"),
]
for patch in PATCHES:
    rel, old, new = patch[:3]
    p = os.path.join(SRC, rel)
    s = open(p, encoding="utf-8").read()
    if new in s:
        print("already patched:", rel); continue
    if old not in s and len(patch) > 3:
        print("not needed:", rel); continue
    if old not in s:
        print("ERROR: text not found in", rel); sys.exit(1)
    open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
    print("patched:", rel)
