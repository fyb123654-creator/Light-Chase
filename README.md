# Light Chase

最新契约修复（2026-10-24）：入站有界保护、WELCOME关联、首包序号、未连接返回错误码已修复，并恢复原协作资料的ROOM_BUSY约定。435项JVM测试、服务端分发包、Android debug构建及lint通过，详见 [最后修改](最后修改.md)。C仍保持原骨架；B6双Android完整集成受C接入依赖阻塞、未验收。此前418项记录保留作历史追溯。

Java + libGDX 的 Android 双人非对称追捕游戏。目标是两台客户端通过局域网连接电脑上的 Java 权威服务器，完成一局基础游戏。

历史逐项审核与本轮B侧综合复核统一记录于 [最终闭环报告](docs/verification/2026-10-24_B-闭环报告.md)（本轮按仅修改B的范围归档）；本页仅记录已有实现与实测范围。

**当前状态（2026-10-24）：B网络/服务端已接入V1引擎并具备B5–B7代码，B-F01–B-F05逐项独立通过；B-F06及整轮验收以最终闭环报告为准。** Android仍是离线替身，双Android完整集成因C接入依赖未就绪而未验收；不能宣称完整V1或自然玩法联合验收完成。唯一当前结论见 [B部分状态与证据](docs/verification/2026-10-24_B-repair-status.md)。

## 三人从这里开始

先读 [骨架运行与交接](docs/development/08_骨架运行与交接.md)，再读 [接口契约](docs/development/02_接口契约.md)。完整文档入口见 [docs/ 文档总目录](docs/README.md)。

| 成员 | 主要目录 | 首个任务 |
|---|---|---|
| A：游戏逻辑 | game-core、game-data | 维护现有V1规则，推进正式图联合切换与试玩 |
| B：双端网络 | server、client-network | 维护已实现网络边界，完成本轮文档/综合复审与设备联调配合 |
| C：Android 界面 | app、client-core | 保留离线替身，增加服务器地址/房间输入及真实网关切换 |

contracts 和地图/规则文件需要三人协调变更。客户端只发送操作意图，服务器计算位置与胜负，界面消费按角色过滤的 PlayerView。

## 快速运行（Windows PowerShell）

将仓库克隆到完整路径不含中文的位置，例如 C:\dev\Light-Chase。安装 JDK 21、Android Studio、Android SDK Platform 37 和 Build Tools 36.0.0；在 Android Studio 打开项目根目录并选择 JDK 21 作为 Gradle JDK。Java 源码编译目标为 17。

只跑 JVM 模块（contracts、game-core、server、client-network、client-core）不需要 Android SDK，`.\gradlew.bat checkJvm` 直接可用。构建 `:app:` 才需要 SDK：Android Studio 会自动写 `local.properties`；用纯命令行时设置环境变量，例如 `$env:ANDROID_HOME="C:\Users\<你>\AppData\Local\Android\Sdk"`。

```powershell
git clone https://github.com/fyb123654-creator/Light-Chase.git C:\dev\Light-Chase
cd C:\dev\Light-Chase
.\gradlew.bat checkJvm :app:assembleDebug :server:installDist
.\gradlew.bat :server:run
```

另开终端测试大厅：

```powershell
.\gradlew.bat :server:probe --args="127.0.0.1 8080"
```

Android Studio 选择 app 运行配置和 API 37 模拟器，点击 Run。或用 SDK 的 adb 安装 app/build/outputs/apk/debug/app-debug.apk。第一次同步需下载依赖，local.properties 由 Android Studio 创建，不提交到 Git。

## 已有内容与限制

- 七模块工程、Gradle Wrapper、Java 契约、测试地图与规则 JSON。
- 保留旧移动原型；实际JVM服务端已使用V1EngineFactory接入权威固定步与角色视图。
- A2–A5 权威规则引擎：V1EngineFactory 提供固定步移动、超时、退出、手电/遮挡、小偷矩形视野、标记/保护、物资/拾取/交付/丢弃、出口/撤离、结局优先级/幂等和基础 PlayerView；A6 正式图 `maps/v1.json` 已准备，默认仍test-room，B的JVM服务端已接入。
- A6 正式地图：24×18、两个 3×3 岛、334 个可行走格、全连通、所有任务点可达；`DataFiles.bundledFormal()` 显式加载，默认 `bundled()` 未切换，等待 A/B/C 联合提交。
- 真实网络：握手/哈希、两人房间、选角/准备/取消倒计时、固定步/安全视图、健康、结果确认/返回/新局与背压限流；B6双Android出口仍未验收。
- Android 离线演示：地图、角色切换、摇杆、交互/丢弃按钮采样、生命周期清理；无完整玩法判定。
- 自动化测试当前数量与实际命令见 [B部分状态与证据](docs/verification/2026-10-24_B-repair-status.md)；不沿用旧阶段170/283/294/306/311总数作当前结论。A侧与S0设备历史分别见 [A规则记录](docs/verification/2026-10-24_A-rules.md)、[S0历史记录](docs/verification/2026-10-23_S0.md)。

规则引擎已能跑包含两次标记、主动丢弃/重取、三次交付和撤离的整局脚本；正式地图已准备但默认仍是 test-room，已接入JVM服务端，Android真实接入及双机/真机验收仍待完成。测试地图不能当作已完成的正式关卡。

## 仓库结构：代码与资料分开

根目录只放工程本身：Gradle 配置、七个模块，以及会被打进产物的 `THIRD_PARTY_NOTICES.md` 与 `third-party/` 许可证。所有需求、设计与过程资料集中在 [docs/](docs/README.md)：

| 目录 | 内容 |
|---|---|
| [docs/requirements/](docs/requirements/README.md) | 当前范围、V1 玩法需求、跨版本扩展约定、原始需求快照 |
| [docs/development/](docs/development/README.md) | 分工与开发设计、接口契约、联调验收、决策表、任务书、开源方案 |
| [docs/verification/](docs/verification/) | 各阶段构建、测试与设备验证记录 |
| [docs/outline/](docs/outline/) | 课程 Outline 的 PDF、PPT 与中文调研报告 |

教师课件、参考应用二进制等第三方材料不纳入仓库，留在本地课程资料目录。

## 开源使用

已经实际依赖 libGDX、Java-WebSocket、Gson 等，见 [第三方声明](THIRD_PARTY_NOTICES.md) 和 [复用登记表](docs/development/07_开源复用登记表.md)。Fugitive 仍是参考候选，未复制或移植其源码。第三方许可证不等于本项目原创代码的许可证；团队尚未选择原创代码的开源许可。

[docs/outline/](docs/outline/) 中的课程概述 PDF、PPT 与调研报告保留用于追溯，冲突时以当前已确认需求为准。


协作基线更新（2026-10-23，契约 R2）：开始独立开发前必须阅读接口契约第 12 节。GatewayListener 新增 onSession/onControl；A 按单步输入验收表实现规则，B 维护身份/控制请求和消息校验，C 将回调入队并按控制终态恢复按钮。共享变更应先合入共同基线，三人逐个可运行功能集成，不等所有模块写完才合并。这是2026-10-23协作基线历史说明；当前B实现与验收边界以本页链接的B状态为准。
