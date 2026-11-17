# R3 / v2 共享期望样例

日期：2026-11-16。字段以 [02 接口契约第 13 节](../../02_接口契约.md)为准。所有消息 JSON 均为完整包络；`scenario-expectations.json` 是 review 用例清单，不是线上消息。

15 份线上消息样例已通过真实 `V2WireCodecTest` 的严格解码和类型往返检查；原运行端仍使用 v1，规则与联机验收尚未完成。所有坐标和时长仅供协议说明，不代表正式地图/平衡配置，也不能用于真实资源哈希握手。实际固定配置通过 `V2DataFiles.bundled()` 获取，不混用其坐标与本目录示意场景。

- `input-police.json`：向右移动、向左照射。
- `use-shield.json`、`action-*.json`：使用槽0护盾与成功回执，及旧实例失败回执。
- `snapshot-*-locked.json`：两角色初始视图、公开地面道具/卡与锁定通道。
- `snapshot-thief-two-shields.json`、`snapshot-thief-shield-refreshed.json`：独立两槽与 max 刷新，不缩短、不增加 marks。
- `snapshot-thief-unlocking.json`、`snapshot-thief-unlocked-release.json`：期望卡实例锁定，解锁与进入分开。
- `event-portal-started.json`、`snapshot-*-transit.json`、`snapshot-police-landed-hidden.json`：事件去重、双方中途恢复光球和落地权限。
- `snapshot-thief-new-match.json`：新局清理与恢复卡/门。

各文件的包络 seq 是独立结构样例，不表示按目录顺序发送。串联演示须由替身重新分配单调 seq，按 scenario-expectations 的场景选择；不得串联互不相关初始状态。没有为假网关另造业务规则。
