package com.example.starter.evidence.dto;

import com.example.starter.evidence.MoveStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 迁移单视图：申请参数、双人确认进度与完成后的逐件封签快照。
 *
 * @param moveKey          迁移业务键
 * @param applicantId      迁移申请操作人
 * @param evidenceKeys     规范化（去重升序）后的证物业务键集合
 * @param sourceLocation   源库位业务键
 * @param targetLocation   目标库位业务键
 * @param expectedVersion  申请时源库位库存版本
 * @param status           迁移单状态
 * @param firstConfirmer   首名确认保管人；null 表示尚未首人确认
 * @param firstConfirmedAt 首人确认时间（Asia/Shanghai）
 * @param secondConfirmer  第二名确认保管人；null 表示尚未完成双人确认
 * @param cancelledBy      撤销操作人；null 表示未撤销
 * @param decidedAt        完成或撤销时间（Asia/Shanghai）
 * @param createdAt        申请时间（Asia/Shanghai）
 * @param snapshots        逐件封签快照；未完成时为空列表
 */
public record MoveView(
        String moveKey,
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
        List<MoveSealSnapshotView> snapshots) {
}
