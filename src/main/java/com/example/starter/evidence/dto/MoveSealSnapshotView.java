package com.example.starter.evidence.dto;

import java.time.LocalDateTime;

/**
 * 迁移封签快照视图。
 *
 * @param evidenceKey     证物业务键
 * @param sealNo          迁移完成时刻的封条编号快照
 * @param fromLocation    迁出库位业务键
 * @param toLocation      迁入库位业务键
 * @param firstConfirmer  首名确认保管人
 * @param secondConfirmer 第二名确认保管人
 * @param snapshotAt      快照写入时间（Asia/Shanghai）
 */
public record MoveSealSnapshotView(
        String evidenceKey,
        String sealNo,
        String fromLocation,
        String toLocation,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime snapshotAt) {
}
