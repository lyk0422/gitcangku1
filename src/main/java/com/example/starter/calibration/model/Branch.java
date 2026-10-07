package com.example.starter.calibration.model;

/**
 * 二次证书分支单调方向。导数 2ax+b 在整个输入闭区间 [L, U] 内严格同号，
 * 由端点精确验证（导数为线性函数，端点同号即全区间同号）。
 */
public enum Branch {

    /** 严格递增分支：导数在 [L, U] 内严格为正。 */
    INCREASING,

    /** 严格递减分支：导数在 [L, U] 内严格为负。 */
    DECREASING
}
