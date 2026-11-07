// Copyright 2026 池也; SPDX-License-Identifier: Apache-2.0
export function judgeTime(value: string): string {
  // API LocalDateTime values represent UTC database timestamps.
  const date = new Date(/(?:Z|[+-]\d{2}:\d{2})$/i.test(value) ? value : value + 'Z')
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}
export function judgeLabel(status: string, verdict?: string | null): string {
  const labels: Record<string, string> = {
    QUEUED: '等待判题',
    RUNNING: '正在判题',
    RETRYING: '平台正在重试',
    CANCELLED: '已取消',
    SYSTEM_ERROR: '判题服务异常',
    FINISHED: '判题结束',
    AC: '已通过',
    WA: '答案错误',
    CE: '编译错误',
    RE: '运行错误',
    TLE: '运行超时',
    MLE: '内存超限',
    OLE: '输出超限',
    SECURITY_VIOLATION: '执行受限',
  }
  return labels[status === 'FINISHED' && verdict ? verdict : status] ?? '状态暂不可识别'
}
export function judgeAdvice(status: string, verdict?: string | null): string {
  if (status === 'SYSTEM_ERROR')
    return '这是平台执行异常，不代表代码答案错误。请稍后查看记录或重试。'
  const advice: Record<string, string> = {
    AC: '本次提交已通过当前版本的全部测试，可以查看官方题解并继续练习。',
    WA: '检查题意、边界条件和输出格式；可用公开样例或自己构造输入进行自测。',
    CE: '请根据下方编译信息修改代码，确认使用 public class Main 和 main 方法。',
    RE: '检查数组下标、空值、除零和输入读取方式，再用自测缩小范围。',
    TLE: '检查是否存在死循环，以及算法是否能在题目数据范围内及时完成。',
    MLE: '检查数组大小、对象数量和额外空间的使用。',
    OLE: '检查循环输出和调试打印，避免产生过量内容。',
    SECURITY_VIOLATION: '请仅使用题目要求的标准输入输出，不启动外部进程或访问网络。',
  }
  return status === 'FINISHED' && verdict ? (advice[verdict] ?? '') : ''
}
