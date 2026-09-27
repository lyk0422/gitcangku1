package com.example.starter.evidence.dto;

import com.example.starter.evidence.MoveStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 位置迁移单视图：单据信息 + 规范化后的证物集合（按字典序）。
 *
 * @param moveKey            位置迁移业务键
 * @param evidenceKeys       规范化后的证物集合（去重、按字典序）
 * @param sourceLocationCode 源库位编码
 * @param targetLocationCode 目标库位编码
 * @param expectedVersion    申请时证物版本
 * @param status             迁移单状态
 * @param firstConfirmer     第一确认保管人；null 表示尚无人确认
 * @param secondConfirmer    第二确认保管人；null 表示未完成
 * @param createdAt          申请时间（Asia/Shanghai）
 * @param firstConfirmedAt   首人确认时间（Asia/Shanghai）；null 表示未确认
 * @param completedAt        双人确认完成时间（Asia/Shanghai）；null 表示未完成
 * @param cancelledAt        撤销时间（Asia/Shanghai）；null 表示未撤销
 */
public record MoveView(
        String moveKey,
        List<String> evidenceKeys,
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
