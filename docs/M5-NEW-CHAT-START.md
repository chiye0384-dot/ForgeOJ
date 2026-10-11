# ForgeOJ新对话启动指令：M4完成后的M5接续准备

交接日期：2026-10-11，Asia/Shanghai。仓库：`D:/Java项目/ForgeOJ`。本文件是当前入口，取代旧M1/M2/M3/M4交接中的“下一步”。完整事实、规则、工具与验收标准见[M5详细交接](M5-NEXT-CHAT-HANDOFF.md)。

## 新对话必须执行的第一轮

用户本次要求整理交接并创建新对话。**第一轮只读接手、核事实并提出M5建议，等待用户明确说“继续”后才改业务/部署代码或启动长验证。** 不重新做已完成M4，不把创建对话当作购买云资源、公网发布或创建Release的授权。

1. 显式进入`D:/Java项目/ForgeOJ`；桌面保存项目“简历Java项目”根是父目录`D:/Java项目`，它不是ForgeOJ Git根。在ForgeOJ目录执行Git/Maven。使用`forgeoj-development`技能及delivery-workflow。
2. 依次读本文件、仓库`AGENTS.md`顶部、[详细交接](M5-NEXT-CHAT-HANDOFF.md)、[M4总审计](M4-GATE-AUDIT.md)、[监控验收](M4-MONITORING-VALIDATION.md)最终部分和[机器证据](evidence/m4-monitoring/verification.json)。
3. 读[Roadmap](ForgeOJ-Roadmap.md)第9/10节、[Requirements](ForgeOJ-Requirements.md)14.4/14.5/15、[Decision Log](ForgeOJ-Decision-Log.md)D-045～D-048、[限制](KNOWN_LIMITATIONS.md)、[上游许可](UPSTREAM-AND-LICENSE.md)、[性能计划](Performance-Test-Plan.md)及[简历证据矩阵](Resume-Evidence-Matrix.md)E-06/E-07。
4. 只读核对实际分支、HEAD、工作树、保护文件SHA、额度与Docker。完成M4实现提交`30b7f53619f9c1aaf930ea1df64f82f5d42271be`已推送`feat/m2-accounts`；本交接另有文档提交，核实际HEAD，不将正常文档提交当业务漂移。
5. 当前M-1～M4全部VERIFIED；M5/V1.1 PLANNED；没有PR/main合并/tag/Release/RESUME_READY。M4七步及5/5总门禁已完成。第一轮不启动全量测试、演练容器、云资源、真实邮件或公网服务。
6. 向用户报告已读文档、实际Git/环境、M5已有基础和缺口、建议首个有限单元、真正需要用户提供的主机/域名/备份/试用信息。仓库能查的事实自己查；不让用户替你选普通工程细节。
7. 用户明确“继续”后，先形成可审阅M5设计和分组计划，再在已授权范围实际推进。没有主机信息时可做独立设计/部署包和隔离验证；不得假装主机/公网/异地备份/真实试用门禁通过。费用、新服务/许可、真实持久数据和公网发布须依据实际授权处理，先完成可审阅成果，再请求必要的最终批准。

## 不可越过的边界

- `docs/M2-NEXT-CHAT-HANDOFF.md`、`.smtp.qq.local`禁止读/打印/复制/编辑/暂存/提交/覆盖/删除；只允许SHA核对，摘要在详细交接。
- 不关闭、退出、重启、终止或更新Codex/ChatGPT；不关闭内置自动化标签页。旧tab5曾在about:blank/deliverable，跨对话先查真实标签页，不盲用旧ID，不复现关闭缺陷。
- MySQL为业务/权限事实源，API无Docker权限、Worker保持最小授权；不能改判定/源码、清死信、重置恢复预算或关闭认证来通过部署。
- 每组昂贵验证前核额度；用户明确“如果额度不够做下一件事就及时停下来”。不足则写诚实检查点、精确清理，不弱化验收后标VERIFIED。
- 旧验收容器和数据库已清理，旧URL失效；`target`构件/日志不是运行服务，不能复用旧私有cookie。

旧`M4-STEP4-NEW-CHAT-START.md`、`M4-NEXT-CHAT-HANDOFF.md`、M3及更早入口均为历史。本轮只读接手完成后，等待用户“继续”。
