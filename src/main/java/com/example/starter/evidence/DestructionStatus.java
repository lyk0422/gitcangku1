package com.example.starter.evidence;

/**
 * 销毁申请审批结果。
 * APPROVED 满足业务条件且无有效冻结，证物进入 DESTROYED；
 * REJECTED 被证物自身或所属案件的有效冻结阻断（HTTP 409，证物状态不变，记录只追加保留）。
 */
public enum DestructionStatus {
    APPROVED,
    REJECTED
}
