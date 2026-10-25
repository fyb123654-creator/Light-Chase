# B 部分当前实施与验证状态

最新补充（2026-10-24）：契约复查的四项偏差及 ROOM_BUSY 对齐已修复，测试总数更新为 435，具体变更与命令见 [最后修改](../../最后修改.md)。下文 418/417 项属于此前阶段记录；双 Android 完整集成的未验收边界仍适用。

记录日期：2026-10-24。当前结论以 [B侧闭环报告](2026-10-24_B-闭环报告.md) 为准：本轮418项JVM测试、服务端打包及B-F06文档复核通过。B-F01–B-F05保留原独立审核结论；本轮综合复核不冒充原流程中缺失的独立审核签署。用户明确仅允许修改B，C全部恢复原骨架；双Android完整集成受C接入依赖阻塞、未验收，不能据此宣称B6全部出口或完整V1通过。下表417项是此前作者实测的历史记录。

本轮新增 `server/src/test/java/com/lightchase/server/NaturalMatchNetworkTest.java`，以原test-room/规则、真实WebSocket和生产V1引擎验证两种自然胜利、两次标记、丢弃重取、三交付/撤离、返回与三局重置、BACKGROUND结果。未改A/C源码。当前分项：contracts8、game-data38、game-core108、client-network130、server132、client-core2，共418项，失败/错误/跳过均0。

## 当前范围

| 阶段 | 当前实现与已有验证 | 未验收出口 |
|---|---|---|
| B1–B4 | contracts/严格双向Codec、握手/资源哈希、房间/准备/倒计时、30Hz固定步、FIFO/inputSeq/drop去重、250ms租约、15Hz及事件视图、实际V1引擎已接入JVM服务端；相关回归保留通过 | Android自然玩法联合验收不能由JVM替代 |
| B5 | B-F01快照局号/身份/角色/tick、B-F02房间资源/成员/revision/结束局保护、B-F03 SUSPECT释放门禁、B-F05服务端2999/3000与4999/5000ms健康及合法包恢复，均逐项独立通过 | C的真实网关注入/渲染线程接收/触控与生命周期设备验证 |
| B6 | GAME_OVER重发/确认、返回大厅/新局、旧局隔离、背压/限流已有代码与回归；B-F04首完整结果锁及冲突诊断独立通过；实际双JVM网关两局流程通过 | 双 Android 完整集成：C 接入依赖未就绪，未验收 |
| B7 | 端口配置/启动关闭/分发包已有测试，B-F05 installDist通过；本轮修正文档真实样例和API调用 | 六项及最终综合验收结论见最终闭环报告；设备出口仍按B6记录 |

## 已有证据与本轮验证

- 最近独立完成的B-F05：client-network 128项、server 131项均0失败/错误/跳过，服务端打包成功。
- 本轮文档回归已实际验证16个真实JSON样例及1处真实InputFrame调用编译；作者运行 `checkJvm :server:installDist --rerun-tasks --console=plain` 通过，打包成功。此为本地修改验证，不替代独立最终验收。
- 修改/审核完整逐步证据保存在工作区父目录 `.codex-coordination/b-audit-repair-20261024/`，包括每项修改过程、交接、审核1与冻结SHA清单；该目录是本机协作记录，不假称已提交仓库。
- `DocumentedProtocolTest` 从真实 `server/B7_实现与交接.md` 提取每个json围栏，调用当前方向性WireCodec；真实submitInput调用用当前contracts编译。没有另写一份样例fixture来替代文档验证。

### 作者本地JVM实测（2026-10-24）

| 模块 | 测试数 | 失败/错误/跳过 |
|---|---:|---|
| contracts | 8 | 0 / 0 / 0 |
| game-data | 38 | 0 / 0 / 0 |
| game-core | 108 | 0 / 0 / 0 |
| client-network | 130 | 0 / 0 / 0 |
| server | 131 | 0 / 0 / 0 |
| client-core | 2 | 0 / 0 / 0 |
| 合计 | 417 | 0 / 0 / 0 |

以上历史日志与XML归档于本机协调目录 `B-F06-作者全量JVM与打包.log` 和 `B-F06-最终-JUnit/`；本轮证据另存父目录 `.codex-coordination/b-only-final-20261024/`，不覆盖旧审核记录。

## 交接约束

1. AndroidLauncher仍注入FakeGateway，C页面/HUD/真实网关注入不是本轮B修复任务；不因此给B新增C开发。
2. 双 Android 完整集成属于B6验收出口，因C接入依赖未就绪而未验收；本轮真实双JVM网关测试不等同双模拟器，更不等同自然玩法/完整V1通过。
3. 默认地图仍为test-room；正式地图联合切换、试玩和设备端权限/触控/生命周期按现有联合计划另验。不更改A规则。
4. B部分六项及最终综合结论由最终闭环报告承载；JVM测试和文档样例通过都不能签署“双Android/自然玩法/完整V1已验收”。

## 可复现命令

```sh
sh gradlew :client-network:test --tests com.lightchase.network.DocumentedProtocolTest --rerun-tasks --console=plain
sh gradlew checkJvm :server:installDist --rerun-tasks --console=plain
```

JDK和路径取本机实际配置；使用JDK21运行Gradle、编译目标Java17。本轮已完成B侧综合复跑与报告归档；原独立审核记录、当前复核和设备出口分别记载，不将JVM通过冒充双Android验收。
