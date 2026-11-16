# 题解访问证据

2026-10-03，基线 c6cf100 的新源码，题解访问单元 VERIFIED；完整 M2 与正式内容验证/审核/发布未完成。详见 [验收记录](../../M2-SOLUTION-ACCESS-VALIDATION.md)。

固定 Linux API 158/Worker 111，前端 45；243 构建输入匹配。浏览器截图是独立可丢弃库中的原创合成题解，普通用户提前确认和真实当前版本 AC 的访问行为，不是已审核正式题库或正式内容验证证据。solution-database 只包含数量/布尔，solution-grants 是实际数据库拒绝；其他 JSON 来自本次 exact-artifact 重放。实际查看/取消未留记录，other-learner 确认后仅其记录为 1；learner 仍 LOCKED，提交得到真实 AC 后解锁且无提前记录。A+B 实际 AC 后仍 UNAVAILABLE，没有自动复制用户源码。

原始日志、JAR、fixture SQL 不提交。所有库/队列/沙箱为独立验收数据，已精确清理。失败运行及探针依赖顺序/端口标注修正保留在验收报告，不以失败运行代替最终通过事实。
