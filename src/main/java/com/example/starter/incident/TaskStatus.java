package com.example.starter.incident;

/**
 * 处置任务状态机：
 * OPEN（未派发）→ DISPATCHED（已派发，待执行前检查）→ EXECUTING（执行中）→ DONE；
 * DISPATCHED 在执行前凭据检查不通过时转为 BLOCKED；BLOCKED 仅能凭新凭据显式再派发
 * （BLOCKED → DISPATCHED），旧凭据不会自动解锁。
 * OPEN/DISPATCHED/BLOCKED 可取消为 CANCELLED；EXECUTING 不允许取消。
 * DONE 与 CANCELLED 为终态。未派发的 OPEN 任务保留直接完成的旧路径。
 */
public enum TaskStatus {

    /** 待处理（尚未派发资源凭据）。 */
    OPEN,

    /** 已派发：派发时凭据校验通过并快照版本，等待执行前检查。 */
    DISPATCHED,

    /** 执行中：执行前重新检查凭据通过后进入。 */
    EXECUTING,

    /** 阻断：执行前检查发现凭据已过期或已撤销；只能凭有效新凭据再派发。 */
    BLOCKED,

    /** 已完成：EXECUTING 任务经执行前检查通过后完成，或未派发的 OPEN 任务直接完成。 */
    DONE,

    /** 已取消。 */
    CANCELLED;
}
