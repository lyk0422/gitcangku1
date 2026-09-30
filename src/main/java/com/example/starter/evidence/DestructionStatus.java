package com.example.starter.evidence;

/**
 * 销毁申请结论。
 * ALLOWED 申请满足业务条件且无有效冻结，证物已进入 DESTROYED 终态；
 * DENIED 存在证物自身或所属案件的有效冻结，申请被拒（409），证物状态不变。
 * 结论只追加，冻结事后到期或解除均不改写 DENIED 记录。
 */
public enum DestructionStatus {
    ALLOWED,
    DENIED
}
