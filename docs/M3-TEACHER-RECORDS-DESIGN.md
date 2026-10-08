# M3 教学作业记录单元

2026-10-08 总门禁补充：完整M3现已 [VERIFIED，4/4 PASS](M3-GATE-AUDIT.md)。本文件以下保留该单元执行时的范围、结果及失败轮次；最终整体状态以总门禁为准，不将各轮次重新描述为同一次运行。

2026-10-08；起点 feat/m2-accounts / 82afd00；本单元 VERIFIED（证据见M3-TEACHER-RECORDS-VALIDATION.md），完整M3仍IN_PROGRESS。落实 Requirements 9.3、D-015/D-042及L-016：只读本班作业正式关联记录，不扩大普通结果GET、私人练习、自测或PRECOMPLETED历史源码权限。本次用户“继续”授权推进本单元，先前作业单元停止点已完成；完整M3总门禁留下一步。

负责人和助教每次请求必须重新检查当前有效账号/session及MySQL班级角色。复用账号→策略fence→班级→成员→作业锁序，不能依赖创建人、历史角色或JWT。有效MEMBER为403；无关班级、LEFT/REMOVED、跨班资源和无作业关联记录为统一空404；未登录401。归档、取消、停止作业保留教学只读，当前教学身份仍必需。转让后原负责人作为有效助教仍可读，降为MEMBER或离班立即失权。

成绩分页基于永久assignment_participant，包含LEFT/REMOVED历史参与者，标注当前成员状态。每人逐题返回完成状态、正式尝试次数、作业内首次真实AC时间；PRECOMPLETED保留完成状态但不把原私人提交ID或源码送给教师。后来加入但未手动加入作业者不列入；草稿/未启动作业无参与记录。与本人Grade使用相同截止、延期、结束、真实AC和零自测规则。按页最多50人、每人最多20题，用集合SQL聚合，避免逐人逐题查询。

正式尝试列表按接受时间和submission ID稳定倒序分页，只有assignment_attempt与冻结assignment_problem和submission的用户/题目/版本均一致才可读；最多50条，不批量返回源码。源码详情只读单条关联正式Submission（含非AC和排队中），不返回隐藏测试、参考程序、标准输出或自由练习源码。PRECOMPLETED、自测、其他作业/班级、同题但无正式关联的提交均不能借教师入口读取。无新迁移、grants、Worker生产变更或依赖。

接口根 /api/v1/classrooms/{room}/assignments/{id}/teaching：GET /grades，GET /participants/{userId}/problems/{ordinal}/attempts，GET /submissions/{submissionId}。每次响应no-store。只读页面 /classrooms/:id/assignments/:assignmentId/records 从现有教学作业详情进入：分页全员摘要，选成员/题目查看正式尝试，再明确选择源码；身份/路由/筛选变化立即清除旧源码，晚到响应丢弃，401/403/404清空敏感页面，刷新复核权限。源码用纯文本只读展示，不执行HTML；不持久化学生代码到localStorage。

验收：先红后绿的真实MySQL权限/资源/状态/分页/成绩一致性测试；前端角色和晚到响应测试；固定Linux全部检查；同JAR真实Worker和多账号页面；只读数据库关联/最小grants、无关私人/预完成/自测拒绝、日志/空队列及精确清理，输入SHA与构件关联。全部通过后才VERIFIED、更新交接并按原授权提交推送。旧交接和SMTP配置保留；浏览器复用、空白保留，不关闭Codex或标签页。
