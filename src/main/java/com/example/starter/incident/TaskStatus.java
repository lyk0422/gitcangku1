package com.example.starter.incident;

/**
 * 处置任务状态机。
 * OPEN：已创建待派发；DISPATCHED：已派发待执行；EXECUTING：执行前凭据复检通过、进入执行；
 * BLOCKED：已派发但执行前复检发现凭据过期/撤销（含尚未 DISPATCHED 时凭据已失效），
 *          不能直接完成，须用有效凭据重新派发后才可再次进入执行；
 * DONE：已完成；CANCELLED：已取消。
 * 允许的流转：
 * OPEN→DISPATCHED（派发）、DISPATCHED→EXECUTING（执行前复检通过）、EXECUTING→DONE（完成）、
 * DISPATCHED/EXECUTING→BLOCKED（执行前复检凭据失效）、BLOCKED→DISPATCHED（用有效凭据重新派发）、
 * OPEN/DISPATCHED/EXECUTING/BLOCKED→CANCELLED（取消）。
 * DONE 与 CANCELLED 为终态。
 */
public enum TaskStatus {

    /** 待处理（已创建，尚未成功派发）。 */
    OPEN,

    /** 已派发待执行，已绑定派发时的凭据版本。 */
    DISPATCHED,

    /** 执行前凭据复检通过，进入执行状态。 */
    EXECUTING,

    /** 凭据阻断：执行前复检发现凭据过期或被撤销，不能直接完成，须更新凭据后重新派发。 */
    BLOCKED,

    /** 已完成（执行前复检通过且全部阻塞事件已解除）。 */
    DONE,

    /** 已取消。 */
    CANCELLED;

    /** 是否仍可取消（终态 DONE/CANCELLED 不可取消）。 */
    public boolean isCancellable() {
        return this != DONE && this != CANCELLED;
    }
}
