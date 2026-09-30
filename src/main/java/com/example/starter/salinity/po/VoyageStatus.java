package com.example.starter.salinity.po;

/**
 * 航次状态。
 *
 * <ul>
 *     <li>{@link #OPEN}：采样中，可提交样本、进行质控采纳；</li>
 *     <li>{@link #SEALED}：已封存，剖面已固化，禁止替换采纳版本，仅可追加勘误。</li>
 * </ul>
 */
public enum VoyageStatus {

    /** 采样中（可封存，封存失败不会离开该状态）。 */
    OPEN,

    /** 已封存（剖面冻结，不可再次封存）。 */
    SEALED
}
