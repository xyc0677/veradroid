# VeraDroid

在越狱的 Kindle 上运行 Android 7.1 的开源项目。Android 和 Kindle 系统共用同一个 Linux 内核，跑在独立的根文件系统和容器里；
不刷机、不替换内核、不改写 Kindle 的系统分区，随时可以回到 Kindle 原来的界面。

- 网站、安装说明和更新日志：<https://veradroid.wenjin.jp>
- 作者：ajie
- 许可：GPL-3.0-or-later（见 [`LICENSE`](LICENSE)、[`THIRD_PARTY.md`](THIRD_PARTY.md)）

> 本项目为独立开源项目，与 Amazon 没有任何关联，也未获得其认可或赞助；与 Véra 越狱项目及其作者亦无关联。“Kindle”是 Amazon.com, Inc. 或其关联公司的商标。
> 仓库里没有 Amazon 的固件、内核镜像、内核模块或从 Amazon 软件中提取的任何文件。越狱、安装第三方软件有风险，请自行判断。

## 支持的机型

| 机型 | 安装包 | 当前版本 | 状态 |
|---|---|---|---|
| Kindle Paperwhite 6（2024） | `pw6` | 1.48 | 正式支持 |
| Paperwhite 5、入门款 2022（KT5）、入门款 2024（KT6） | `k49` | 1.56 | 实验性，已有真实用户在用 |
| Scribe、Colorsoft | `k49` | 1.56 | 实验性，只在模拟器里验证过；Colorsoft 按灰度显示，Scribe 不支持手写笔 |
| Paperwhite 2 / 3 / 4、Voyage、Oasis 2 / 3 等老机型 | `legacy` | 0.8.57 | 测试阶段 |

固件版本、越狱方式等细节以网站上的说明为准。

## 功能

- 完整的 Android 7.1.1 运行环境：可以安装 APK，运行阅读、笔记、浏览器等应用；
  与 Kindle 共享书库（`documents`）和 Kindle 自带字体。
- 墨水屏显示：按脏区域局部刷新，多种刷新模式和波形，可选抖动、浅色过滤、旋转（竖 / 横 / 倒置）。
- 触摸和按键：多点触摸、全局边缘手势（返回、下拉面板、亮度 / 色温）、四角长按、机身双击翻页、蓝牙翻页器和键盘。
- 自带桌面 VeraHome：方块 / 经典两种界面风格、下拉面板、书库、应用管理、使用统计、屏保、锁屏。
- 自带输入法 VeraIME（拼音）。
- 局域网传书 / 管理网页（PIN 保护，只在打开时可用）。
- 与 Kindle 的配合：前光、电源键休眠唤醒、Wi-Fi、时区、电量；低内存机型的冻结 / 精简 / 预编译策略。
- 在线更新和离线整包；安装失败或启动失败时自动回到 Kindle。

## 架构

```
┌──────────────────────────── Kindle（Linux 内核，Amazon 固件）────────────────────────────┐
│  Kindle 界面（暂停 / 恢复）                                                               │
│                                                                                          │
│  宿主脚本 host/<机型>/boot（busybox sh，root）                                            │
│    ├─ 容器：unshare 出 mount / UTS / IPC / net / PID 命名空间，pivot_root 后执行 /init       │
│    │   （3.0 内核没有命名空间：chroot + 共用 Kindle 网络）                                   │
│    ├─ 网络：slirp4netns（Kindle 内核没有 veth）                                           │
│    ├─ 显示桥 display/epd.c（veradroid-epd，静态链接 FBInk）                               │
│    ├─ 输入前端 input/vinput.c（veradroid-input，uinput）                                  │
│    └─ 设置、手势、休眠、更新、诊断（boot/lib/*.sh）                                       │
│                                                                                          │
│  Android 7.1.1（AOSP 编译，android/）                                                     │
│    ├─ gralloc / hwcomposer：渲染到共享缓冲，脏矩形经 FIFO 交给显示桥                       │
│    ├─ memfd 代替 ashmem、无 GPU 的软件渲染、binder 协议适配                               │
│    └─ VeraHome（桌面）、VeraIME（输入法）                                                 │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

| 目录 | 内容 |
|---|---|
| `host/pw6`、`host/k49`、`host/legacy` | Kindle 上运行的宿主脚本（启动、停止、容器、显示、输入、休眠、设置、更新），按安装包分三套 |
| `display/` | 显示桥：RGBA 共享缓冲 → 灰度 → 墨水屏刷新（FBInk / 驱动 ioctl） |
| `input/` | 输入前端：读取 Kindle 的触摸 / 按键设备，坐标变换后经 uinput 交给 Android，识别全局手势 |
| `android/` | AOSP 7.1.1 的设备配置（`device/veradroid/`）、整文件覆盖（`overlay/`）、小补丁（`small-patches.py`）、hwcomposer、VeraHome 和 VeraIME 源码 |
| `bluetooth/` | 蓝牙翻页器 / 键盘桥 |
| `cloud/static/` | 安装器和更新脚本（`bootstrap.sh` 等）、书库入口脚本 |
| `kpm/`、`kual/`、`documents/` | KPM 包、KUAL 扩展、书库入口 |
| `tools/` | 交叉编译（`cross/`）、老内核的外挂内核模块（`kmod/`）、系统镜像构建（`build/`）、测量工具（`bench/`）、小程序源码（`src/`） |
| `diagnostics/` | 设备调查、兼容性检查、冒烟测试 |
| `tests/` | 宿主脚本和安装器的静态测试（全部：`sh tests/run-static.sh`；单个：`sh tests/<名字>-test.sh`） |
| `docs/` | 设计说明：宿主设置接口、打包与更新、系统镜像、蓝牙、旋转、界面等 |

## 构建

仓库只有源码，不含预编译二进制和 APK。

### 1. Android 系统镜像（AOSP android-7.1.1_r61）

```sh
# 构建环境（Ubuntu 16.04 + OpenJDK 8）
docker build -t vdaosp:7.1 -f tools/build/Dockerfile.aosp tools/build

# AOSP 源码
export AOSP_WORK=$HOME/veradroid-aosp
mkdir -p "$AOSP_WORK/src" && cd "$AOSP_WORK/src"
repo init -u https://android.googlesource.com/platform/manifest -b android-7.1.1_r61 --depth=1
repo sync -c -j8
cd -

# 打补丁并编译两种镜像：pw6（64 位 binder 协议）和 k49（32 位 binder 协议）
sh tools/build/build_rootfs.sh prepare
sh tools/build/build_rootfs.sh pw6
sh tools/build/build_rootfs.sh k49
```

`android/apply-patches.sh` 把 `android/` 下的设备配置、覆盖文件和补丁应用到 AOSP 源码树（可重复运行）；
`build_rootfs.sh` 在此基础上编译、检查并打包 `rootfs-<镜像>-<git>.tar.gz`，同时输出同一次编译的 VeraHome / VeraIME。
细节见 [`docs/system-image.md`](docs/system-image.md)、[`docs/packaging-and-ota.md`](docs/packaging-and-ota.md)。
老机型（3.0 / 4.1 内核，Cortex-A9）用另一个产品 `veradroid_kindle32`。

### 2. 宿主程序（ARM 交叉编译）

```sh
# 新机型：Ubuntu 的 gcc-arm-linux-gnueabihf，静态链接
git clone https://github.com/NiLuJe/FBInk && (cd FBInk && git checkout 886f25f && git apply ../patches/fbink-state-dump-args.patch)
make -C FBInk CROSS_TC=arm-linux-gnueabihf KINDLE=1 MINIMAL=1 IMAGE=1 DRAW=1 staticlib
arm-linux-gnueabihf-gcc -O2 -static -I FBInk display/epd.c FBInk/Release/libfbink.a -lm -o veradroid-epd
arm-linux-gnueabihf-gcc -O2 -static input/vinput.c -o veradroid-input

# 老机型（glibc 2.19）：tools/cross/Dockerfile.legacy + tools/cross/build-legacy-bin.sh
```

`tools/src/` 里的小程序（`pivot_init`、`slirpctl`、`vdlmk` 等）同样静态编译；slirp4netns 用上游发布的 armv7l 静态二进制（见 `third_party/README.md`）。
没有 binder / tun / uinput 的老内核需要外挂模块：`tools/kmod/`（需要自行下载 Amazon 公开的对应内核 GPL 源码）。

### 3. 打包

安装包 = `host/<机型>/` 的脚本 + 上面编出的宿主程序（`bin/`）+ 系统镜像 `rootfs.tar.gz` + APK + 字体
（霞鹜文楷 `LXGWWenKai-Regular.ttf`，从 <https://github.com/lxgw/LxgwWenKai> 下载，OFL 1.1）。
包结构和安装器的约定见 [`docs/packaging-and-ota.md`](docs/packaging-and-ota.md) 和 `cloud/static/bootstrap.sh`。

## 安装

请按网站上的说明操作：<https://veradroid.wenjin.jp>（需要先越狱）。

## 参与

见 [`CONTRIBUTING.md`](CONTRIBUTING.md)；安全问题见 [`SECURITY.md`](SECURITY.md)。

## 许可与开源来源

VeraDroid 自己的代码按 GNU General Public License 第 3 版或（由你选择）任何更新版本授权（GPL-3.0-or-later），
文件头有 `SPDX-License-Identifier`。AOSP 覆盖文件沿用原文件的许可。

本项目 **100% 基于自由与开源生态构建**，不包含、不链接任何闭源商业软件的代码或二进制文件。全部第三方开源组件、数据、上游地址、版本号、SHA-256 及技术/交互参考来源均完整列在 [`THIRD_PARTY.md`](THIRD_PARTY.md) 和 [`NOTICE`](NOTICE)。

---

## English summary

VeraDroid runs Android 7.1.1 (built from AOSP `android-7.1.1_r61`) on jailbroken Kindle e-readers. Android lives in its own
root file system inside a container on the Kindle's own Linux kernel; nothing is flashed and the Kindle system partition is
not modified, so you can always return to the stock Kindle UI.

- Supported: Kindle Paperwhite 6 (package `pw6` 1.48, official); Paperwhite 5, Kindle 2022 / 2024 (`k49` 1.56, experimental,
  used by real users); Scribe and Colorsoft (`k49` 1.56, experimental, verified on an emulator only; Colorsoft runs in grayscale,
  no pen on Scribe); older models PW2 / PW3 / PW4 / Voyage / Oasis 2 / 3 (`legacy` 0.8.57, testing).
- Components: host shell scripts (`host/`), an e-ink display bridge (`display/`, uses FBInk), an input front-end (`input/`),
  AOSP device config and patches (`android/`), the VeraHome launcher and VeraIME input method, installer scripts (`cloud/static/`).
- Build: see "构建" above (Docker image `tools/build/Dockerfile.aosp`, `tools/build/build_rootfs.sh`, ARM cross-compilation of
  the host programs). Install instructions: <https://veradroid.wenjin.jp>.
- License: GPL-3.0-or-later. Every dependency, toolchain and technical reference comes from open-source projects; full list with upstream links, hashes and licences in `THIRD_PARTY.md` and `NOTICE`. Author: ajie.
- Not affiliated with, endorsed or sponsored by Amazon, nor by the Véra jailbreak project or its authors. "Kindle" is a trademark of Amazon.com, Inc. or its affiliates.
