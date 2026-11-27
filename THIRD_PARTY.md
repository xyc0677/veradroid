# 第三方组件与开源来源说明

本文列出 VeraDroid 的安装包和本仓库里带有、链接、编译或参考的**全部开源组件、公开接口规范与设计灵感来源**，以及它们的许可和源码获取方式。

- **100% 开源来源声明**：本项目构建、链接或随包分发的全部第三方代码、工具与数据均源自自由与开源软件项目，**不包含、不链接、不提取任何闭源商业软件的代码或二进制产物**。
- VeraDroid 自己的代码按 `GPL-3.0-or-later` 授权（见 `LICENSE`）。
- 本仓库只含源码：预编译二进制、APK、字体和系统镜像不入库，下表“在哪里”一栏指安装包里的位置（安装包版本：PW6 1.48 / k49 1.56 / legacy 0.8.57）。发现遗漏或错误欢迎提 issue。

## 一览

| 组件 | 版本 | 许可 | 在哪里 | 形式 |
|---|---|---|---|---|
| FBInk | v1.25.0-543-g886f25f + 本地补丁 | GPL-3.0-or-later | `bin/fbink`；静态链接进 `veradroid-epd` | 静态二进制 |
| slirp4netns | v1.3.7 | GPL-2.0-or-later | `bin/slirp4netns`（来源见 `third_party/README.md`） | 官方静态二进制，未修改 |
| GNU C 库（glibc） | 2.23（Ubuntu 16.04）/ 2.19（Ubuntu 14.04） | LGPL-2.1-or-later | 静态链接进全部自编宿主程序 | 静态链接 |
| GCC 运行库（libgcc） | GCC 5.4 / 4.8 | GPL-3.0-or-later + GCC 运行库例外 | 同上 | 静态链接 |
| AOSP | android-7.1.1_r61 | 主要是 Apache-2.0，另有多种许可 | `rootfs.tar.gz`、`libandroid_runtime.so` | 编译产物（带本项目补丁） |
| Mozilla 根证书 | 构建时的版本 | MPL-2.0 | 补进 rootfs 的 `/system/etc/security/cacerts` | 数据文件 |
| 霞鹜文楷 LXGW WenKai | 1.510 | SIL OFL 1.1 | `fonts/LXGWWenKai-Regular.ttf`（构建时从上游下载） | 字体文件，未修改 |
| libime / fcitx5 拼音词库与语言模型 | dict-20260907 / lm_sc.arpa-20260629 | LGPL-2.1-or-later | `VeraIME.apk` 内 `assets/pinyin.dat`（由 `tools/ime/gen_pinyin_dict.py` 生成） | 转换后的二进制词库 |
| Dropbear SSH | 2026.92（KOReader 构建） | MIT 式（含少量其他宽松许可） | 单独发布的 SSH 调试包 | 预编译二进制 |
| e2fsprogs（e2fsck、resize2fs） | 1.47.1 | GPL-2.0-only（库部分 LGPL-2.1 / BSD / MIT） | `bin/e2fsck`、`bin/resize2fs`（调整系统镜像大小） | 官方源码 tarball 的静态编译，未修改；构建脚本 `tools/cross/build-e2fs.sh` |
| Linux 内核外挂模块源码及头文件（`binder`、`tun`、`uinput`、`zram` / `zsmalloc`） | Linux 主线（kernel.org）；AOSP `kernel/common` `deprecated/android-3.0`；NXP `rel_imx_3.0.35_4.1.0_ga_caf`；Amazon 公开 GPL 内核源码包 | GPL-2.0-only | 老机型的外挂内核模块（由 `tools/kmod/` 构建，未入库） | 内核模块 |
| Chromium WebView（可选的新网页内核，`com.embrith.webview`） | 119.0.6045.194 | BSD-3-Clause + 第三方组件各自的许可 | 不在安装包里：用户在“显示与刷新”打开时单独下载 `webview-alt-119.0.6045.194.tar.gz`，装到 `veradroid/webview-alt/` | 社区贡献者从源码编译（去掉 Google Play 服务客户端库），贡献者签名，未修改 |

不随安装包分发、只在开发时使用的：eink-emulator（QEMU，测试用）、Docker 构建镜像里的 Ubuntu 软件包（以各自的许可为准）。

**busybox**：本项目不带 busybox。宿主脚本使用 Kindle 固件自带的 busybox / 系统工具；Android 一侧是 AOSP 自带的 toybox 和 mksh。

## 详细说明

### FBInk（GPL-3.0-or-later）

- 上游：https://github.com/NiLuJe/FBInk ，提交 `886f25f`（`v1.25.0-543-g886f25f`）。
- 本地补丁：`patches/fbink-state-dump-args.patch`。上游 `fbink_state_dump()` 在非 Kobo 构建上少传一个参数，`fbink -e` 在 Kindle 上会段错误；补丁补上这个参数。
- 用法：独立的 `fbink` 静态二进制（开机画面、状态文字），以及 `display/epd.c` 静态链接 `libfbink.a`（编译时 `MINIMAL=1 IMAGE=1 DRAW=1`）。
  因此 `veradroid-epd` 整体按 GPL-3.0-or-later 分发。
- FBInk 上游仓库内包含的第三方单头库/字体代码（如 `stb_image`、`libunibreak`、`unifont` 等）均采用 MIT / zlib / GPL 等开源许可，完整清单以 FBInk 上游仓库的 `LICENSE` 与源码头部声明为准。
- 对应源码：上游提交 + 本仓库的补丁 + `display/epd.c`。

### slirp4netns（GPL-2.0-or-later）

- 来源：https://github.com/rootless-containers/slirp4netns/releases/tag/v1.3.7 官方发布的 armv7l 静态二进制，未修改。
  SHA-256 `91b54c16e093208b40e93f8e13bcbaf932016b800895f245c1f62bd711c753f5`（见 `third_party/README.md`）。
- 对应源码：https://github.com/rootless-containers/slirp4netns/archive/refs/tags/v1.3.7.tar.gz
- 这个静态二进制还包含 libslirp（BSD-3-Clause）以及上游官方构建容器静态链接的开源基础库（`glib`、`libcap`、`libseccomp` 等，均为 LGPL-2.1-or-later 等开源许可），精确构建配方以上游 v1.3.7 发布脚本为准。

### glibc 和 GCC 运行库

宿主程序（`veradroid-epd`、`veradroid-input`、`pivot_init`、`slirpctl`、`vdlmk`、`vdshutwatch`、`vdxattr`、蓝牙相关小程序）用 Ubuntu 的交叉工具链
静态编译：新机型是 Ubuntu 16.04 的 GCC 5.4 + glibc 2.23，老机型是 `tools/cross/Dockerfile.legacy`（GCC 5 + Ubuntu 14.04 的 glibc 2.19）；
部分程序由 GCC 4.8 编译（二进制里的编译器标记）。glibc 是 LGPL-2.1-or-later：因为本项目全部源码公开，用户可以用自己修改过的 glibc 重新链接。
glibc 源码可从 Ubuntu 的源码包获取（`apt-get source glibc`，对应发行版本）或 https://www.gnu.org/software/libc/ 。

### AOSP 7.1.1（Apache-2.0 等）

- 基础：`android-7.1.1_r61`，https://android.googlesource.com/ （`repo init -b android-7.1.1_r61`）。
- 本项目的改动全部在 `android/`：设备配置 `android/device/veradroid/`、整文件覆盖 `android/overlay/`（沿用被覆盖文件原来的许可，例如
  `gralloc/framebuffer.cpp` 是 Apache-2.0）、精确字符串替换的小补丁 `android/small-patches.py`。
- 系统镜像（安装包里的 `rootfs.tar.gz`）和 `libandroid_runtime.so` 是由上述源码编译出的。AOSP 由许多开源子项目组成，许可各不相同，
  其中也包含 GPL / LGPL 用户态组件（如 `iptables` 等，完整逐模块许可清单见编译产物 `/system/etc/NOTICE.html.gz` 及各上游仓库 `MODULE_LICENSE_*` / `NOTICE`）。
  分发系统镜像时，源码提供方式是：AOSP 上游标签 `android-7.1.1_r61` + 本仓库 `android/` 下的全部补丁（`small-patches.py`、`fw-patches.py`、`oec-patches.py`、`eink-patches.py` 及 `overlay/`）。
- 镜像使用 AOSP 自带的测试签名密钥（`build/target/product/security/`），见 `SECURITY.md`。
- rootfs 里额外加入了当前的 Mozilla 根证书（MPL-2.0），因为 2016 年的根证书已过期或缺失。来源和时间点由构建者决定（`android/mk-rootfs.sh` 读取 `cacerts-extra/`）。

### Chromium WebView 119（BSD-3-Clause 等）

- 只在 1 GB 机型上、用户打开“显示与刷新 → 网页内核（实验）”时下载，不进安装包、不入库。
- 构建：社区用户 Embrith余烬 从 https://chromium.googlesource.com/chromium/src/+/refs/tags/119.0.6045.194 编译 `system_webview_apk`
  （`target_cpu="arm"`、`is_official_build=true`、`proprietary_codecs=false`、`use_official_google_api_keys=false`、
  `system_webview_package_name="com.embrith.webview"`），加了一个去掉 Google Play 服务客户端库的小补丁（定位、形状检测、
  短信验证码三处改走不依赖 Play 服务的路径）。补丁和构建说明在下载包里（`chromium-nogms.patch`、`BUILD.txt`）。
  APK 由贡献者签名（证书 CN=Embrith WebView，SHA-256 `2ca861e5b829f5aba0be12b7abfae4f38f99ac47b1204e47ca6d855f638ce58e`），本项目未修改。
- 本项目自己的部分：framework 资源覆盖 `android/webview-alt/overlay`（把这个包列为 WebView 提供者），GPL-3.0-or-later。
- 许可：Chromium 本体 BSD-3-Clause（下载包里的 `LICENSE.chromium`）；所含第三方组件的版权声明和许可全文在下载包的
  `webview_licenses.notice`（Chromium `tools/licenses/licenses.py` 生成的 credits）。不含 Google Play 服务客户端库；没有 GPL 组件
  （FFmpeg 以 Chromium 品牌、LGPL 部分编入，源码在上面的公开地址）。
- “Chrome”“Chromium”是 Google 的商标，这里只作描述性使用。

### 霞鹜文楷（SIL OFL 1.1）

- 上游：https://github.com/lxgw/LxgwWenKai ，字体内名称表写明版本 1.510（2025-01-16）。它基于 Klee One（同为 OFL 1.1）。
- 许可文本：`fonts/OFL.txt`（安装包里在 `fonts/OFL.txt`）。字体未修改，按 OFL 要求和许可文本一起分发；本仓库不带字体文件本身，构建时从上游下载。

### VeraIME 拼音数据（LGPL-2.1-or-later）

`VeraIME.apk` 内的词库 `assets/pinyin.dat` 由 `tools/ime/gen_pinyin_dict.py` 从以下数据转换生成（说明见 `android/packages/VeraIME/assets/NOTICE-pinyin.txt`）：

- libime / fcitx5 拼音词库 `dict-20260907.tar.zst`（`dict_sc.txt`）：https://download.fcitx-im.org/data/dict-20260907.tar.zst （SHA-256 `fb75a179065e690dfc4559ce1807cbaf4fbe4f0111a5005615be9f435e6b9d76`）
- libime / fcitx5 语言模型 `lm_sc.arpa-20260629.tar.zst`（`lm_sc.arpa`，源自 sunpinyin / open-gram）：https://download.fcitx-im.org/data/lm_sc.arpa-20260629.tar.zst （SHA-256 `06808333b9173e5374cf2cb5afc12d08f5625bf9abb536489cac376fc05f2e7f`）
- 上游许可说明：https://github.com/fcitx/libime （`REUSE.toml` 及 `LICENSES/LGPL-2.1-or-later.txt`，Copyright 2002-2005 Yuking, 2014-2026 CSSlayer 及 libime / sunpinyin / open-gram 贡献者）

`pinyin.dat` 按 LGPL-2.1-or-later 分发；可通过 `python3 tools/ime/gen_pinyin_dict.py` 重新生成或替换为任意兼容格式文件。

### Dropbear（MIT 式）

- SSH 调试包里的 `dropbear` 是 KOReader 为 Kindle 构建的版本（二进制标识 `dropbear_2026.92 (KOReader)`），动态链接。
- 上游：https://github.com/mkj/dropbear （许可见其 `LICENSE`：主体 MIT 式，部分文件来自 libtomcrypt / libtommath 等，属公有领域或宽松许可）；
  KOReader 的构建方式见 https://github.com/koreader/koreader-base 。
- 只在调试用的 SSH 包里，不在主安装包里；本仓库不带这个二进制。

### 老机型的内核模块（GPL-2.0-only）

`tools/kmod/`（`build.sh`、`build-binder.sh`、`build-amazon.sh`、`build-fw.sh`、`build-zram.sh`、`build-zram41.sh`）使用 kernel.org 主线 Linux 内核源码、AOSP `kernel/common` 的 binder 驱动、NXP i.MX 内核头文件以及 Amazon 官网公开的对应固件 GPL 内核源码包（`Kindle_src_*.tar.gz`），为缺少内建 `binder` / `tun` / `uinput` / `zram` 的老 Kindle 内核编译外挂模块。这些模块及胶水头文件（`tools/kmod/vd_compat.h`、`vd_uaccess.h`、`zram41/*`）均受 Linux 内核 `GPL-2.0-only` 约束并按 `GPL-2.0-only` 提供；编出的 `.ko` 未入库。

### e2fsprogs（GPL-2.0-only）

- 上游：https://mirrors.edge.kernel.org/pub/linux/kernel/people/tytso/e2fsprogs/v1.47.1/e2fsprogs-1.47.1.tar.gz ，未打补丁。
- 构建：`tools/cross/build-e2fs.sh`（Docker 交叉编译镜像 `vdcross-legacy:gcc5-glibc219`，静态链接，`-Os`）。脚本会打印 tarball 的 SHA-256。
- 对应源码 = 上面的 tarball + 这个构建脚本。

### 自编的 hwcomposer 和系统库

- 安装包里的 `bin/hwcomposer.kindle.so` 由 `android/hwcomposer/hwc_kindle.c` 编出（本项目自己的代码）；
  `libandroid_runtime.so` 是 AOSP 7.1.1 的 `libandroid_runtime` 加本项目补丁（`android/overlay/`、`android/small-patches.py`）。
- 不包含亚马逊的任何文件：仓库里没有亚马逊固件、内核镜像、内核模块（`.ko`）或从固件里提取的库。
  需要亚马逊内核 GPL 源码的步骤（`tools/kmod/build-*.sh`）要求使用者自己下载 `Kindle_src_*.tar.gz`。

## GPL 组件的源码提供方式

本项目分发的 GPL 二进制有 FBInk、显示桥 `veradroid-epd`、slirp4netns、e2fsprogs，以及（老机型的）内核模块；系统镜像里另有 AOSP 自带的 GPL 用户态组件。
做法：

1. 每个发布的安装包都对应一个公开的 git 标签，本仓库包含全部自有源码、构建脚本和补丁。
2. 上游源码地址和精确版本写在本文里；发布页面同时给出这些链接。
3. 如果上游地址失效，维护者需要在发布页面附上对应源码的归档（GPL-3.0 第 6 条、GPL-2.0 第 3 条）。

## 公开接口规范、开发工具与开源生态参考致谢

以下项目或规范未将代码打包进 VeraDroid，但本项目的系统互操作接口、容器架构、墨水屏交互设计或开发测试流程参考了这些公开开源成果，特此完整标注来源：

| 项目 / 规范 | 许可 / 性质 | 上游地址 | 本项目参考或互操作之处 |
|---|---|---|---|
| **openlipc** | MIT / 公开接口文档 | https://github.com/Arkq/openlipc | `tools/vdkaf/vdkaf.c` 声明的 3 个 `liblipc` 公开 C 函数原型（运行时动态链接设备自带库，不带固件文件） |
| **KOReader** | AGPL-3.0-only | https://github.com/koreader/koreader | 墨水屏经典的左右边缘滑动调光/色温、四角长按与双指快捷手势交互范式、EPUB 目录回退思路，以及 `VeraHome` 书库调用外部 KOReader 打开书籍的 Intent 联动 |
| **Waydroid / Anbox** | GPL-3.0 | https://github.com/waydroid/waydroid 、https://github.com/anbox/anbox | 在宿主 Linux 内核上利用 namespaces（`mnt`/`pid`/`net`/`uts`/`ipc`）、`cgroup freezer` 与 `binder` 运行完整 Android 根文件系统的开源容器架构路线 |
| **EinkBro** | GPL-3.0 | https://github.com/plateaukao/einkbro | 墨水屏无惯性整页翻页滚动、高对比度黑白线框 UI、WebView 正文加粗与加深增强思路参考 |
| **KUAL / MRPI / SH_Integration / KPM** | GPL / MIT / 社区公开规范 | https://www.mobileread.com/forums/forumdisplay.php?f=150 | Kindle 越狱生态通用的书库启动脚本注释头（`# Name:`、`# DontUseFBInk`）、KUAL 扩展 `menu.json` / `config.xml` 与 KPM 仓库 `manifest.v2.json` 接口格式（本项目不分发越狱程序） |
| **eink-emulator & QEMU** | GPL-2.0 | https://github.com/katadelos/eink-emulator 、https://www.qemu.org/ | 仅在开发调试与回归测试阶段使用的墨水屏模拟器环境，不随安装包分发 |
| **Open-Meteo** | AGPL-3.0（服务端）/ CC BY 4.0（数据） | https://github.com/open-meteo/open-meteo | `VeraHome` 桌面可选天气卡片按需请求的公开免密钥天气接口 |
| **通用墨水屏 Android View 反射接口** | 公开 API 签名互操作 | 见 `android/eink-patches.py`、`android/overlay/.../android/eink/` | 为兼容第三方墨水屏阅读应用通过反射调用的 `View.EINK_*` 常量与 `EPDCDevice` 桩类，由本项目基于 `/dev/kfb/epd.fifo` 独立从零实现 |

## 其他自有资源

- `host/*/boot/poems.txt`：开机画面显示的古诗文名句，作者均为古代人物，属公有领域。
- 界面图标由 `VeraHome` 的 `Icons.java` 与启动脚本内嵌自绘图标生成，不含第三方商业图标。
