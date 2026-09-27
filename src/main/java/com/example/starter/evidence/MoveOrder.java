package com.example.starter.evidence;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库位迁移单实体，对应 move_order 表。
 * moveKey 为整张迁移的幂等键；evidenceKeys 为规范化（去重升序）后的证物集合。
 *
 * @param id               主键
 * @param moveKey          迁移业务键，全局唯一
 * @param requestHash      申请指纹（操作者+规范化证物集合+源目标库位+版本的 SHA-256）
 * @param applicantId      迁移申请操作人
 * @param evidenceKeys     规范化后的证物业务键集合（升序）
 * @param sourceLocation   源库位业务键
 * @param targetLocation   目标库位业务键
 * @param expectedVersion  申请时源库位库存版本，第二人确认时仍须相等
 * @param status           迁移单状态
 * @param firstConfirmer   首名确认保管人；NULL 表示尚未首人确认
 * @param firstConfirmedAt 首人确认时间（Asia/Shanghai）
 * @param secondConfirmer  第二名确认保管人；NULL 表示尚未完成双人确认
 * @param cancelledBy      撤销操作人；NULL 表示未撤销
 * @param decidedAt        完成或撤销时间（Asia/Shanghai）
 * @param createdAt        申请时间（Asia/Shanghai）
 * @param updatedAt        最近一次状态变更时间（Asia/Shanghai）
 */
public record MoveOrder(
        Long id,
        String moveKey,
        String requestHash,
        String applicantId,
        List<String> evidenceKeys,
        String sourceLocation,
        String targetLocation,
        int expectedVersion,
        MoveStatus status,
        String firstConfirmer,
        LocalDateTime firstConfirmedAt,
        String secondConfirmer,
        String cancelledBy,
        LocalDateTime decidedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
