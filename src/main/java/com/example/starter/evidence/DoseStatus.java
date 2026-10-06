package com.example.starter.evidence;

/**
 * 剂量账本状态（由累计剂量与上限实时推导，不单独落库）：
 * OK 累计未达上限；DOSE_BLOCKED 累计达到或超过上限，禁止新的借出（证物仍可交接）。
 */
public enum DoseStatus {
    /** 累计剂量未达上限，可继续借出。 */
    OK,
    /** 累计剂量达到或超过上限，新增借出返回 422。 */
    DOSE_BLOCKED
}
