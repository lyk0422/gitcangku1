package com.example.starter.calibration.model;

import java.math.BigDecimal;

/**
 * 联合放行批次明细（不可变快照）。固化测量标识、放行时使用的证书与未舍入计算值。
 *
 * @param id              明细 ID（自增）
 * @param jointBatchKey   联合批次键
 * @param measurementSort 批内稳定排序序号（按测量键字典序，从 0 开始）
 * @param measurementId   测量记录 ID
 * @param measurementKey  测量键快照
 * @param certificateId   放行时使用的校准证书 ID 快照
 * @param computedValue   放行时未舍入计算值快照
 */
public record JointReleaseItem(
        long id,
        String jointBatchKey,
        int measurementSort,
        long measurementId,
        String measurementKey,
        long certificateId,
        BigDecimal computedValue) {
}
