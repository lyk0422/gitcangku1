package com.example.starter.calibration.model;

/**
 * 复核驳回明细：一项被驳回测量在批次中的位置、版本与原因。
 *
 * @param id            明细 ID（自增）
 * @param reviewId      所属复核记录 ID
 * @param position      批次内位置（从 1 开始）
 * @param measurementId 被驳回测量记录 ID
 * @param version       驳回时测量版本；版本已变化则复核失败
 * @param reason        驳回原因（非空）
 */
public record ReviewItem(
        long id,
        long reviewId,
        int position,
        long measurementId,
        int version,
        String reason) {
}
