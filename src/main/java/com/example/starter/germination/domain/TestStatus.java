package com.example.starter.germination.domain;

/**
 * 发芽率检测记录状态。
 * <ul>
 *     <li>{@link #VALID}：有效检测，可参与证书签发判定；</li>
 *     <li>{@link #VOID}：取样记录已作废，仅作历史留存，不再支撑任何证书。</li>
 * </ul>
 */
public enum TestStatus {
    /** 有效。 */
    VALID,
    /** 已作废（不可恢复，历史保留）。 */
    VOID
}
