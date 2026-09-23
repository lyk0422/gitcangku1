package com.example.starter.calibration.model;

/**
 * 批次版本快照项：复核成功时冻结整批每个位置的测量与版本，作为重新放行精确映射的基准。
 *
 * @param id                 快照记录 ID（自增）
 * @param batchId            放行批次 ID
 * @param reviewId           产生该快照的复核记录 ID
 * @param measurementId      测量记录 ID
 * @param measurementVersion 冻结的测量版本号
 * @param rejected           该位置是否被驳回
 * @param reason             驳回原因；未驳回为 null
 */
public record BatchSnapshotItem(
        long id,
        String batchId,
        long reviewId,
        long measurementId,
        int measurementVersion,
        boolean rejected,
        String reason) {
}
