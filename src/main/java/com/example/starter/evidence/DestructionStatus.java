package com.example.starter.evidence;

/**
 * 销毁申请裁决结果。APPROVED 批准且证物进入 DESTROYED；REJECTED 因有效冻结被拒（HTTP 409）。
 * 结果只追加、不可改写；冻结到期或解除不改变过去的 REJECTED 记录。
 */
public enum DestructionStatus {
    APPROVED,
    REJECTED
}
