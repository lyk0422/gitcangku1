package com.example.starter.incident;

/**
 * 单次派发记录状态：
 * DISPATCHED 派发时校验通过、待执行前检查；
 * EXECUTING 执行前重新检查通过，执行已启动；
 * BLOCKED 执行前检查不通过（凭据过期或撤销），任务随之阻断。
 * 再派发会产生新的派发记录（新的 seq），旧记录状态不可变。
 */
public enum DispatchStatus {

    /** 已派发，等待执行前检查。 */
    DISPATCHED,

    /** 执行前检查通过，执行已启动。 */
    EXECUTING,

    /** 执行前检查不通过（CREDENTIAL_EXPIRED / CREDENTIAL_REVOKED）。 */
    BLOCKED;
}
