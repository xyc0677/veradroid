# 应用瘦身（appslim）

大应用自带很多和读书无关的后台部件（各家手机厂商的推送、保活服务、小游戏 / 小程序容器、直播、广告下载、日志上传）。
在 512 MB 的 Kindle 上它们和前台一起抢内存，冷启动慢、zram 被塞满、翻页卡。

做法：只把这些**组件**设成停用（Android 的 `setComponentEnabledSetting`，等于用户自己 `pm disable`）。
不改 APK、不重新打包、不调应用的私有接口；随时恢复原样（组件回到停用之前的状态）。

## 用户能看到的

- 桌面“应用优化”页（长按应用 → 应用优化），清单里有、而且装着的应用多一项
  **后台瘦身**：跟随 / 开 / 关（恢复原样）。标题里写着现在停用了几个组件、本机默认是开还是关。
  - 跟随 = 清单默认：`lowmem` = 512 MB 机型开、1 GB 机型关；`on` = 都开。
  - 关 = 一键恢复原样：只恢复我们停用的组件，应用自己停用的不碰。
  - 改了马上生效（正在后台跑的进程不杀，下次启动这个应用时按新的状态）。
- settings.conf：`APP_SLIM=0` 全部恢复原样（排查用）；默认 `auto`。
- 命令：`appslim <包名> | all | restore`（`restore` = 全部恢复，不改设置，下次开机按设置再做）。

## 宿主（host/pw6、host/k49 的 boot/lib/appslim.sh，两份相同）

- 清单 `boot/appslim.conf`，按包名 + versionCode 范围：
  - `<包名> <范围> @default=lowmem|on|off`
  - `<包名> <范围> <组件类名>`
  - 范围 `74532`、`70000-79999`、`74532-`、`-79999`、`*`。装着的版本不在任何范围里：什么都不做（以前停用的恢复）。
  - 新版本里已经没有的类：系统拒绝（类不存在），跳过，不记录。
- 记录 `$SYS/appslim/<包名>.list`：`<类名> <停用之前的状态>`。恢复只看这里。卸载了的应用记录删掉。
- 什么时候做（每次都只改状态不对的组件，重复做不变）：
  开机后（afterboot）、宿主装完 / 更新完应用（cmd install）、应用自己更新（watchdog 每 20 秒看 packages.xml 的修改时间，
  变了再比较清单里各包的版本号）、用户在“应用优化”里改了（appprof_set）。
- 调用：容器里 `service call package 87`（get）/ `86`（set，flags = DONT_KILL_APP），一个 nsenter 里跑完一个包；
  不起 app_process（pm 每条 1 秒多）。码号是 Android 7.1.1 IPackageManager 的
  `getComponentEnabledSetting` / `setComponentEnabledSetting`（out/…/IPackageManager.java 里的 TRANSACTION_ 常量）。
- 日志：`appslim: <包> <版本> (<原因>) on|off: N off (n newly disabled, m restored, k not in this version)`。
- 通用：任何应用都能加清单（晋江 `:pushcore` 之类），不改代码。没有实测过的应用不要加。

## 桌面（VeraHome 93）

`AppDisplayActivity` 多读一个文件 `/dev/kfb/appknobs.pkg`：一行 `<包名> KEY|标题|标签=值,…`，只在这个包的页面上显示。
老桌面不认这个文件，也就不显示这一项（宿主照样按默认做）。

## 实测

见下面各节（模拟器：PW5 5.18.2 512 MB，KT6 5.20.2 1 GB；QEMU TCG，绝对时间比真机慢很多，看前后对比）。

### 番茄免费小说 7.4.5（74532）

清单：只停自己另开进程的部件，67 个（服务 / 接收器 / 内容提供器）：`:push`（字节推送、长连接 WsChannel、红点）、
`:pushservice`（小米 / 华为推送）、`:smp`（推送联盟“拉活”、各厂商推送回调）、`:widgetProvider`、`:uploadLogProcess`、
`:apm_memory_upload`、`:mist`（热更新静默安装）、`:depths_disable_art_image_process`。主进程里的组件一个不动；
`:downloader`、网页内核渲染进程、小游戏 / 小程序、插件占位都不动。默认：512 MB 开，1 GB 关。

为什么值得做（用户诊断，PW6 1 GB，同意协议并登录后）：`:push` 61–64 MB、`:downloader` 57–59 MB、主进程 189–238 MB（PSS，3 份诊断），
procstats 里 `:push` 常驻、`:smp` 也出现过。512 MB 机型上这些常驻进程等于把前台的书挤进 zram。

实测（模拟器，QEMU TCG，比真机慢得多；番茄 243 MB dex 在模拟器上 dex2oat 570 秒看门狗超时，**没有 odex**，所以绝对时间没有参考价值）：

| | KT6 5.20.2 1 GB（WebView 119） | PW5 5.18.2 512 MB |
|---|---|---|
| 设置生效 | 设置页选“开”：63 个停用（另 4 个应用自己本来就停着，不记），约 60 秒（TCG）；选“关”：63 个恢复，回到安装时的 8 个停用 | 开机默认开：63 个停用；再开机一次 0 个变化（幂等）；`appslim restore` 63 个恢复 |
| 不同意 → 简单体验 | 两种状态都能进书城、打开书（书详情页、阅读页）；阅读页章节在模拟器上 6 分钟内没加载出来（不瘦身时也一样） | 不瘦身：首启约 6 分钟后出隐私弹窗 |
| 进程 | 简单体验模式下（没同意协议）**本来就只有主进程**，`:push` 等都不起：瘦身前后都是 1 个进程，主进程 RSS 330–460 MB | 同左 |
| 日志里的异常 | 停用以后没有出现和这些组件有关的错误 / 崩溃 | 同左 |

结论：没同意协议的简单体验模式里看不出差别（那些进程本来就不起）；差别在同意协议 / 登录以后（每个常驻进程 20–60 MB），
这一部分按规矩没有在模拟器上点“同意”去测，需要有账号的真机确认（阅读、登录短信验证码、充值页能打开）。

另外发现（和瘦身无关）：PW5 512 MB 模拟器上，番茄没有 odex 时每次启动都要从 APK 解压 dex，主进程解压时被 lmkd 以 adj 0 杀掉，
约 30 秒一次反复重启（`lowmemorykiller: Killing 'com.dragon.read' … cache 5836kB is below limit 8192kB for oom_adj 0`），
瘦身开 / 关都一样。真机上编译成功（interpret-only）就不会走这条路。

### 商店其他应用（PW5 512 MB 模拟器，2026-11-22）

“不同意 / 基础模式 / 仅浏览”能进去的就进去测；只能停在隐私页的，清单照放，但默认关（没测过的不默认开）。

| 应用（版本） | 停用的组件 | 默认 | 瘦身前 | 瘦身后 |
|---|---|---|---|---|
| 番茄免费小说 7.4.5（74532） | 67：:push :pushservice :smp :widgetProvider :uploadLogProcess :apm_memory_upload :mist :depths… | 512 开 / 1 GB 关 | 简单体验：1 个进程（推送进程同意协议后才起）；用户诊断同意后 :push 61–64 MB | 简单体验：1 个进程，书城 / 书详情 / 阅读页能进，无相关报错 |
| 晋江文学城 7.1.9（520） | 13：:pushcore（极光 / 小米）、:pushservice（华为）、:push :bytelive（直播插件）、:u_heap（友盟内存监控）、:downloader（广告下载） | 512 开 / 1 GB 关 | 基础模式也会起 :pushcore；512 MB 上它 ANR（executing service PushService），系统连带把前台主进程杀掉（“depends on provider cn.jpush…DataProvider in dying proc”），2 分钟内 2 次 | 只有主进程（PSS 224 MB）；书城、打开书、读第一章、翻页（10% → 20%）、登录页都正常 |
| 豆瓣阅读 6.1.1（516） | 15：:pushservice（个推 / 各厂商推送） | 关（用户可开） | 仅浏览：1 个进程，PSS 173 MB（这一轮被系统杀了一次，内存不够） | 1 个进程，PSS 241 MB；引导页、手机号登录页（获取短信验证码按钮）正常；书城没测到 |
| 多看阅读墨水屏版 1.2.8 | 3：:pushservice（小米推送） | 关 | 没有免同意模式 | 能起到隐私页；之后没测 |
| 欧路词典 10.1.4（27959） | 9 个（本机 6 个生效）：:pushservice、:lqRemote（客服长连接）、:widgetProvider | 关 | 没有免同意模式 | 能起到隐私页（+20 s）；之后没测 |
| 长佩阅读 2.8.11（352） | 53 个 + 动态广告插件目录阻断：:pushcore（极光/小米）、:pushservice、:bytelive/:downloader/:kssdk_remote/:qysdk_simd、近十家广告 SDK 初始化 Provider、冷启动抢主线程的 MusicService | 开 | 未瘦身时 :pushcore（百度加固重复拆 12 DEX）必然 ANR 并因 DataProvider 依赖连带杀掉主进程；同意协议后并发初始化广告 SDK 与动态 dex2oat 还会把主线程 MusicService 拖到 20s ANR 闪退 | 单进程稳定进入 CpHomeActivity（书城/书架/鱼塘/我的），可用内存 173–220 MB，无 ANR / 闪退 |
| 京东读书 4.70.3（32 位）/ 京东读书专业版 4.40.3（32 位） | 25 / 18 个：:jdpush（京东/小米推送）、:pushservice（华为推送）、:bytelive/:push/:downloader 及各厂商推送接收器 | 开 | 配合系统以太网改为上报 `TYPE_WIFI` 后联网正常；未瘦身时额外起无用推送/直播/广告下载后台 | 偏好页、书城、我的/账号登录与专业版机构认证/CARSI 认证均正常，`nt=WiFi` 无网络报错 |

没有加清单（已经很轻，或者另开的进程是功能本身）：微信读书墨水屏版（:seg 是阅读器的分词服务，:patch 只在热更新时起）、
掌阅精选（:hotfix 只在热更新时起）、读库（:u_heap 友盟内存监控，没测）、Anki（:acra 只在崩溃时起）、Librera（:djvu 是 DjVu 渲染）、
KOReader、Moon+ Reader Pro、EinkBro、Readrops、Feeder、Material Files、Markor、英语帮（都是单进程、没有推送 SDK）。

## 社区说明（草稿）

> **番茄 / 晋江“后台瘦身”**：番茄自带一堆和读书无关的后台进程（各家手机的推送、拉活、日志上传、热更新）。
> 新版在 512 MB 的机型上默认把番茄和晋江的这些**组件**停掉（和在手机设置里“停用组件”一样），APK 本身不改，书城和阅读照常用；代价是收不到番茄的推送通知。
> 1 GB 机型默认不动，想要可以在桌面长按应用 → 应用优化 → 后台瘦身 → 开（豆瓣阅读、多看、欧路词典也有这一项，默认关）。
> 觉得哪里不对：同一个地方选“关（恢复原样）”，马上恢复。番茄更新后会按新版本重新核对，新版本里没有的组件自动跳过。
