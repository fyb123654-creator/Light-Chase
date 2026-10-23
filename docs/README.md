# Light Chase 文档与资料总入口

这个目录收纳**开发过程与需求资料**，与仓库根目录的游戏代码分开存放。代码在根目录的 Gradle 模块里（`contracts`、`game-core`、`server`、`client-network`、`client-core`、`android`、`game-data`），文档不会打进 APK，也不进服务器的分发目录（`THIRD_PARTY_NOTICES.md` 与 `third-party/` 属于合规产物，仍留在根目录并随构建复制）。

## 目录结构

| 路径 | 内容 | 维护人 |
|---|---|---|
| [requirements/](requirements/README.md) | 当前范围、V1 玩法需求（F01–F13、A01–A22）、跨版本扩展约定、原始需求快照 | A（用户） |
| [development/](development/README.md) | 分工与开发设计、接口契约、联调验收、决策表、任务书、开源方案与登记表、骨架现状 | 全员，B 维护共享文件 |
| [verification/](verification/) | 各阶段构建/测试/设备验证记录 | 当次验证人 |
| [outline/](outline/) | 课程 Outline 产物：英文 4 页 Summary、14 页 PPT、中文调研报告 | A |

## 阅读顺序

1. 新成员先看仓库根目录 [README.md](../README.md) 的第 1–3 节，了解能跑什么、怎么跑。
2. 再看 [08_骨架运行与交接](development/08_骨架运行与交接.md)，确认自己模块的现状与禁止事项。
3. 按角色读 [05_三人任务书与AI交接说明](development/05_三人任务书与AI交接说明.md)，然后读 [02_接口契约](development/02_接口契约.md)。
4. 开发前读 [04_决策与待确认事项](development/04_决策与待确认事项.md)，不要自行填未知项。

## 约定

- 这里是唯一维护源。`handoff/` 里的 ZIP、`课业\大三上\mobile_computing\project\docs` 的副本都是快照，不要在快照上继续修改。
- 文档优先级：用户最新明确指令 > 已确认玩法需求 > 已采用决策 > 建议值。
- 接口契约是字段与调用方式的唯一来源；改契约先改文档再改代码，并通知另外两人。
- 课程材料（教师 PDF、参考应用二进制）不放进仓库，见 [outline/](outline/) 的说明。
