package com.example.starter.evidence;

/**
 * 批量入库重量核对结果。
 * MATCHED 实测与申报重量差异不超过申报重量 5%，证物可直接使用；
 * DISCREPANT 差异严格超过申报重量 5%，证物封存同时进入待复核状态。
 */
public enum WeightCheck {
    MATCHED,
    DISCREPANT
}
