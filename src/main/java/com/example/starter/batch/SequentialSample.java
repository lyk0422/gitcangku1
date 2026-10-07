package com.example.starter.batch;

/**
 * 合成序贯检验的单个样本结论：GOOD 合格样本（似然比乘 3）；BAD 不合格样本（似然比乘 1/3）。
 */
public enum SequentialSample {
    GOOD,
    BAD
}
