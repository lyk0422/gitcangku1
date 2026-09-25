package com.example.starter.evidence;

/**
 * 批量入库重量核对结果。
 * MATCHED 实测与申报差异在申报重量5%以内（含等于）；DISCREPANT 差异超过5%，待复核。
 */
public enum WeightStatus {
    MATCHED,
    DISCREPANT
}
