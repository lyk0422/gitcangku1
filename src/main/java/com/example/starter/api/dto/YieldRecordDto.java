package com.example.starter.api.dto;

/**
 * 让渡历史记录（不可变）。关联原占用、走廊版本与处置结果。
 *
 * @param yieldId         让渡记录唯一标识
 * @param corridorId      触发让渡的应急走廊标识
 * @param corridorVersion 让渡时的走廊版本
 * @param occupancyId     被让渡的占用标识
 * @param routeId         被让渡航线标识
 * @param reviewId        被让渡占用原审查记录标识
 * @param routeVersion    固化的原审查航线版本
 * @param airspaceVersion 固化的原审查空域版本
 * @param reason          固化的让渡原因
 * @param createdAt       让渡时间，epoch 毫秒（UTC）
 */
public record YieldRecordDto(
        String yieldId,
        String corridorId,
        long corridorVersion,
        String occupancyId,
        String routeId,
        String reviewId,
        int routeVersion,
        long airspaceVersion,
        String reason,
        long createdAt) {
}
