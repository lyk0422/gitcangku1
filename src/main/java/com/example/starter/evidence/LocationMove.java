package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 位置迁移单实体，对应 location_move 表。
 * 两名不同保管人依次确认；COMPLETED/CANCELLED 后不可再变。
 *
 * @param id                 主键
 * @param moveKey            位置迁移业务键，全局唯一
 * @param sourceLocationCode 源库位编码
 * @param targetLocationCode 目标库位编码
 * @param expectedVersion    申请时证物版本；确认时每件证物版本必须仍等于该值
 * @param status             迁移单状态
 * @param firstConfirmer     第一确认保管人；null 表示尚无人确认
 * @param secondConfirmer    第二确认保管人；null 表示未完成
 * @param createdAt          迁移申请时间（Asia/Shanghai）
 * @param firstConfirmedAt   首人确认时间（Asia/Shanghai）；null 表示未确认
 * @param completedAt        双人确认完成时间（Asia/Shanghai）；null 表示未完成
 * @param cancelledAt        撤销时间（Asia/Shanghai）；null 表示未撤销
 */
public record LocationMove(
        Long id,
        String moveKey,
        String sourceLocationCode,
        String targetLocationCode,
        long expectedVersion,
        MoveStatus status,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime createdAt,
        LocalDateTime firstConfirmedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt) {
}
