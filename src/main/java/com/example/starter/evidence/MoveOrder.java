package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移单实体，对应 move_request 表。evidenceKeys 为规范化（排序去重）后的证物集合。
 *
 * @param id              主键
 * @param moveKey         迁移业务键（幂等键），全局唯一
 * @param evidenceKeys    规范化证物键集合（排序去重）
 * @param sourceLocation  源库位编码
 * @param targetLocation  目标库位编码
 * @param expectedVersion 申请时源库位库存版本
 * @param status          迁移单状态
 * @param createdBy       申请操作人
 * @param firstConfirmer  首人确认保管人；null 表示尚未确认
 * @param secondConfirmer 第二人确认保管人；null 表示尚未确认
 * @param createdAt       申请创建时间（Asia/Shanghai）
 * @param updatedAt       最近一次状态变更时间（Asia/Shanghai）
 * @param decidedAt       完成或撤销时间；null 表示仍在流转
 */
public record MoveOrder(
        Long id,
        String moveKey,
        List<String> evidenceKeys,
        String sourceLocation,
        String targetLocation,
        int expectedVersion,
        MoveStatus status,
        String createdBy,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime decidedAt) {
}
