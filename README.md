# Light Chase

Java + libGDX 的 Android 双人非对称追捕游戏。目标是两台客户端通过局域网连接电脑上的 Java 权威服务器，完成一局基础游戏。

**当前状态：S0 开发骨架 + 部分 S1 原型，尚不能联机完成一局。** Android 默认运行清楚标注的离线界面替身；真实 WebSocket 大厅由 JVM 集成测试和命令行探针验证。正式玩法以 [V1.0 需求](docs/requirements/V1.0_基础联机版需求.md) 为准。

## 三人从这里开始

先读 [骨架运行与交接](docs/development/08_骨架运行与交接.md)，再读 [接口契约](docs/development/02_接口契约.md)。完整文档入口见 [开发文档](docs/development/README.md)。

| 成员 | 主要目录 | 首个任务 |
|---|---|---|
| A：游戏逻辑 | game-core、game-data | 在移动原型基础上实现视野、光束、三次标记与保护 |
| B：双端网络 | server、client-network | 完成准备/倒计时，接入固定步引擎与角色快照 |
| C：Android 界面 | android、client-core | 保留离线替身，增加服务器地址/房间输入及真实网关切换 |

contracts 和地图/规则文件需要三人协调变更。客户端只发送操作意图，服务器计算位置与胜负，界面消费按角色过滤的 PlayerView。

## 快速运行（Windows PowerShell）

将仓库克隆到完整路径不含中文的位置，例如 C:\dev\Light-Chase。安装 JDK 21、Android Studio、Android SDK Platform 37 和 Build Tools 36.0.0；在 Android Studio 打开项目根目录并选择 JDK 21 作为 Gradle JDK。Java 源码编译目标为 17。

```powershell
git clone https://github.com/fyb123654-creator/Light-Chase.git C:\dev\Light-Chase
cd C:\dev\Light-Chase
git switch codex/bootstrap
.\gradlew.bat checkJvm :android:assembleDebug :server:installDist
.\gradlew.bat :server:run
```

另开终端测试大厅：

```powershell
.\gradlew.bat :server:probe --args="127.0.0.1 8080"
```

Android Studio 选择 android 运行配置和 API 37 模拟器，点击 Run。或用 SDK 的 adb 安装 android/build/outputs/apk/debug/android-debug.apk。第一次同步需下载依赖，local.properties 由 Android Studio 创建，不提交到 Git。

## 已有内容与限制

- 七模块工程、Gradle Wrapper、Java 契约、测试地图与规则 JSON。
- 规则移动原型：固定步、归一化移动、墙体碰撞、超时与中断；未接入联网对局。
- 真实网络大厅：握手、资源哈希、两人房间、角色互斥、心跳与退出；准备开始明确返回未实现。
- Android 离线演示：地图、角色切换、摇杆、交互/丢弃按钮采样、生命周期清理；无完整玩法判定。
- 18 项自动化测试及 API 37 模拟器运行记录，见 [验证记录](docs/verification/2026-10-23_S0.md)。

当前没有完整追捕/物资/撤离/结算流程，也未完成双 Android 联调和真机验收。测试地图不能当作已完成的正式关卡。

## 开源使用

已经实际依赖 libGDX、Java-WebSocket、Gson 等，见 [第三方声明](THIRD_PARTY_NOTICES.md) 和 [复用登记表](docs/development/07_开源复用登记表.md)。Fugitive 仍是参考候选，未复制或移植其源码。第三方许可证不等于本项目原创代码的许可证；团队尚未选择原创代码的开源许可。

原仓库中的课程概述 PDF 与 PPT 保留用于历史参考，冲突时以当前已确认需求为准。
