package com.example.starter.salinity.model;

/**
 * 航次状态。
 * <ul>
 *     <li>{@link #OPEN}：采样与质控进行中，可提交样本、替换采纳版本；</li>
 *     <li>{@link #SEALED}：已封存，剖面冻结，采纳版本不可再替换，仅可追加勘误。</li>
 * </ul>
 */
public enum VoyageStatus {

    /** 采样中：允许提交、质控替换。 */
    OPEN,

    /** 已封存：剖面已固化，拒绝采纳替换，仅允许追加勘误。 */
    SEALED
}
