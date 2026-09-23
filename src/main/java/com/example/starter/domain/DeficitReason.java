package com.example.starter.domain;

/**
 * 命中区域在候选豁免包下的缺口原因（BLOCKED 时返回）。
 */
public enum DeficitReason {
    /** 包内不存在 regionKey + regionVersion 匹配的项（含豁免包已撤销或无此包）。 */
    MISSING,
    /** 存在版本匹配项，但 reviewAt 不在该项 UTC 有效区间 [validFrom, validTo) 内。 */
    EXPIRED,
    /** 项在有效期内，但剩余额度已为 0。 */
    EXHAUSTED
}
