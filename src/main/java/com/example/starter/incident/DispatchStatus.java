package com.example.starter.incident;

/**
 * 任务派发记录状态。
 * DISPATCHED：本次派发凭据校验通过，任务待执行；
 * BLOCKED：执行前复检依据当时已提交的凭据状态判定为过期/撤销，任务被阻断；
 * SUPERSEDED：该派发已被一次新的成功派发取代（旧凭据版本不得用于自动解锁）。
 */
public enum DispatchStatus {

    /** 派发成功（凭据类型、区域、有效期校验通过）。 */
    DISPATCHED,

    /** 执行前复检未通过，任务阻断。 */
    BLOCKED,

    /** 已被更新凭据的重新派发取代。 */
    SUPERSEDED;
}
