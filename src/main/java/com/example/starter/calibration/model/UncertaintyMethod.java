package com.example.starter.calibration.model;

/**
 * 合成不确定度计算口径。
 */
public enum UncertaintyMethod {

    /**
     * 公开的 RSS 口径：假设各分量互不相关，
     * 合成不确定度 u_c = sqrt(Σ u_i²)（平方和开根）。
     */
    RSS_V1
}
