package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 迁移封签快照实体，对应 move_seal_snapshot 表。
 * 第二人确认成功时按证物逐件写入，只追加、不可变。
 *
 * @param id              主键
 * @param moveKey         关联迁移业务键
 * @param evidenceKey     关联证物业务键
 * @param sealNo          迁移完成时刻的封条编号快照
 * @param fromLocation    迁出库位业务键
 * @param toLocation      迁入库位业务键
 * @param firstConfirmer  首名确认保管人
 * @param secondConfirmer 第二名确认保管人
 * @param snapshotAt      快照写入时间（Asia/Shanghai）
 */
public record MoveSealSnapshot(
        Long id,
        String moveKey,
        String evidenceKey,
        String sealNo,
        String fromLocation,
        String toLocation,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime snapshotAt) {
}
