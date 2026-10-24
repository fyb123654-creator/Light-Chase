# B1–B7 当前工作区复查（2026-10-24）

本页保留前次复查的417项与缺失报告结论供追溯；后续已按用户要求恢复全部C改动，新增仅B侧整局联网回归并通过418项测试。最新范围与结论见 [B侧闭环报告](2026-10-24_B-闭环报告.md)，双Android出口仍受C依赖阻塞、未验收。

结论：**现有 JVM 自动化测试、服务端打包、Android debug 构建及 lint 通过；不能认定 B1–B7 每个验收出口全部完成。** B6 仍缺双 Android 完整联调；六项修复流程停在 B-F06 已交审核，尚无最终闭环报告。

本次将“B 到 B新”按任务书 B1–B7 理解，同时检查 B-F01–B-F06 修复记录。仅检查和归档证据，未修改业务源码、仓库测试、依赖或既有审核记录；本页不替代原协调流程尚未完成的签署。

## 逐阶段判断

| 阶段 | 本次结论 | 依据及剩余事项 |
|---|---|---|
| B1 契约与 Codec | 已实现，现有回归通过 | contracts 8 项；client-network 的方向性 Codec、合法/非法包和协议契约测试通过 |
| B2 服务端与房间 | 已实现，现有回归通过 | 服务端会话、握手/资源哈希、入房、房满、协议边界及启动测试通过 |
| B3 准备与倒计时 | 已实现，现有回归及真实连接探针通过 | 两端选角/准备/取消等已有回归；自动倒计时与角色初始视图探针通过 |
| B4 引擎与固定步 | 已实现，现有回归及真实连接探针通过 | 默认 V1 引擎、30Hz 固定步、15Hz 视图、输入确认/250ms 租约、离开结算探针通过 |
| B5 网关与健康 | 网络层实现及当前回归通过 | 快照/房间关联、疑似断线输入释放、首结果锁、服务端 3秒/5秒健康边界通过；Android 触控、后台和真实接入的设备验收仍待完成 |
| B6 结算、重开与完整集成 | **部分验收完成** | JVM 结果投递/确认、返回/新局、旧局隔离、背压/限流回归通过；**双 Android 完整集成未验收**，依赖 C 接入 |
| B7 打包与交付 | 构建和样例验证通过，验收归档未闭环 | installDist、distZip、启动测试通过；16 个真实文档 JSON 和 1 处输入 API 调用验证通过；B-F06 正式审核结论及最终闭环报告未找到 |

“回归通过”仅说明本次执行覆盖范围，没有将其扩展为所有未知场景或设备测试通过。

## 本次实际执行

仓库：`Light-Chase` 当前 checkout，包含既有未提交和未跟踪实现；分支 `codex/network-b3`，HEAD `2730617ee575a1dc7a1e626a53dd5075bb6694f9`。不能仅用 HEAD 代表本次实现。对照 `B-F06-交审-文件快照.json`，其登记文件内容均一致，差异为 0。

使用本机缓存 JDK21，Java 编译目标 17，执行：

```sh
JAVA_HOME=/Users/fyb/.cache/lightchase-tools/jdk21/Contents/Home sh gradlew \
  -Dorg.gradle.java.installations.paths=/Users/fyb/.cache/lightchase-tools/jdk21/Contents/Home \
  checkJvm :server:installDist :server:distZip \
  :android:assembleDebug :android:lintDebug :android:testDebugUnitTest \
  --rerun-tasks --console=plain
```

结果：退出 0，`BUILD SUCCESSFUL in 19s`，78 个任务执行。读取这次重新生成的 JUnit XML：

| 模块 | 测试数 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| contracts | 8 | 0 | 0 | 0 |
| game-data | 38 | 0 | 0 | 0 |
| game-core | 108 | 0 | 0 | 0 |
| client-network | 130 | 0 | 0 | 0 |
| server | 131 | 0 | 0 | 0 |
| client-core | 2 | 0 | 0 | 0 |
| 合计 | **417** | **0** | **0** | **0** |

Android `testDebugUnitTest` 为 **NO-SOURCE**，没有 Android 模块单元测试，不能计作通过了一组 Android 测试。此次没有执行设备 UI、instrumentation、真机或自然玩法联合测试。

Android lint：0 错误，4 警告（工具版本提示、横屏适配提示、备份规则提示、缺应用图标），不阻塞本次构建。详情见 `android/build/reports/lint-results-debug.html`。构建另有 Gradle 弃用及 libgdx.so 符号剥离提示；本次任务仍成功，未调整依赖。

## 补充真实连接及边界探针

从全局脚本目录读取既有 Java 探针，以本次 `installDist` 的 jar **重新编译**后执行：

| 探针 | 结果 |
|---|---|
| LightChaseB3AuditProbe | 通过；双方自动倒计时、V1 初始投影和广播间隔检查通过 |
| LightChaseB4Probe | 通过；真实双连接固定步、输入租约、安全视图、退出结果通过 |
| LightChaseBF01Review | 原历史探针失败，见下方；适配后的 LightChaseBF01CurrentReview 通过 |
| LightChaseBF02Review | 通过；18 个房间状态/重复包/revision/成员/跨局检查 |
| LightChaseBF03Review | 通过；PONG 恢复、输入释放门禁、drop、再次中断与租约 |
| LightChaseBF04Review | 通过；首完整结果锁、冲突诊断、返回引用与第二局 |
| LightChaseBF05Review | 通过；未过滤真实连接下 2999/3000ms、4999ms 恢复、旧包不续期及 5000ms 断开 |

历史 BF01 脚本在 COUNTDOWN 构造两名 `ready=false` 成员，后来 B-F02 加入的合法房间检查会拒绝，因此在 COUNTDOWN 屏障超时。本次保留原文件及失败日志，在全局目录另建副本，仅将成员准备状态改为 `phase != LOBBY`，原有快照和跨局断言全部保留。适配后通过；没有删除断言或修改产品来通过探针。首次汇总脚本退出 1 如实保留，适配脚本退出 0。

上述探针和 JUnit 中的双 JVM 网关测试均不能替代双 Android 对局。`GatewayRoomLifecycleTest` 两局采用引擎异常触发 SERVER_ABORTED，以快速检查生命周期，也不能证明两局自然玩法完成。

## 尚未完成的验收出口

1. **Android 真实网络入口缺失。** `android/src/main/java/com/lightchase/android/AndroidLauncher.java:14` 仍注入 `new FakeGateway()`；`client-core/src/main/java/com/lightchase/client/LightChaseApp.java` 仍连接 `offline:8080 / DEMO`。因此现有 APK 无法据此验证两端连接真实服务器的完整流程。
2. **B6 双 Android 联调未验收。** 本机 adb 可见 emulator-5554、emulator-5556，但设备存在不表示功能接入或验收完成。本次未安装或操作其 UI。需在 C 完成接入后验连接、选角、准备/取消、自然对局、双方一致结算、返回、第二局及后台/断线。
3. **修复流程归档未闭环。** 父目录 `b-audit-repair-20261024/进度.md` 记录 B-F01–B-F05 已独立通过，B-F06 为“已交独立审核”；存在其审核中快照与 client 日志，但未找到 `B-F06-审核1.md`、最终综合审核结论及 `docs/verification/2026-10-24_B-闭环报告.md`。本次全量复跑和样例通过不能倒填既有独立审核记录。
4. **完整 V1 另有联合出口。** 默认仍为 test-room，正式图联合切换、完整页面/HUD、触控/生命周期与真机玩法不能由本次 B 网络检查签署。

## 证据与复现

本次 XML、lint XML、探针原始成功/失败日志和统计归档在工作区父目录：

`.codex-coordination/b-current-check-20261024/`

其中 `summary.json` 包含 417 项统计、F06 文件内容比对、原始探针退出码和 BF01 适配说明；原始退出码未改写。辅助脚本位于 `/Users/fyb/Desktop/实验1/webcoding存脚本专用/LightChaseCurrentBCheck.py` 和 `LightChaseCurrentBF01Adapter.py`，按此顺序可重跑探针与统计。既有 B-F01 原探针会如实再次产生同一非法测试数据失败，随后适配副本验证通过。

`git diff --check` 通过。本次没有发现执行覆盖范围内新的业务阻塞缺陷；“B 全步骤完成/全部验收通过”的结论仍不成立，主要剩余为设备集成和正式审核归档。
