package com.example.starter.domain;

/**
 * 命中区域在豁免包中无法核销的原因分类（审核结论保持 BLOCKED 时返回）。
 */
public enum DefectReason {
    /** 豁免包中不存在该 regionKey 的区域项。 */
    MISSING,
    /** 区域项存在，但其 regionVersion 与当前空域中该区域的版本不一致。 */
    VERSION_MISMATCH,
    /** 豁免包或区域项所属豁免包已被撤销。 */
    REVOKED,
    /** reviewAt 不在区域项的 UTC 有效区间内。 */
    EXPIRED,
    /** 区域项剩余额度为 0。 */
    EXHAUSTED
}
