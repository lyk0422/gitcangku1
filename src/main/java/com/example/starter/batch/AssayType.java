package com.example.starter.batch;

/**
 * 批次检验类型。
 * CLASSIC：仅经典必做检验项（PASS/FAIL），沿用旧规则；
 * SEQUENTIAL：在经典必做检验项之外增加合成序贯检验（GOOD/BAD 抽样似然比），
 * 序贯 PASS 且必做项齐全后才可进入双角色放行。
 */
public enum AssayType {
    CLASSIC,
    SEQUENTIAL
}
