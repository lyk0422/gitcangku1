package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 封签核验快照实体，对应 seal_snapshot 表。
 * 位置迁移双人确认完成的同一事务内逐件写入，只追加、不可变。
 *
 * @param id              主键
 * @param moveKey         所属位置迁移业务键
 * @param evidenceKey     快照证物业务键
 * @param sealNo          快照时封条编号
 * @param evidenceStatus  快照时证物状态（完成时必须为 SEALED）
 * @param custodianId     快照时当前保管人
 * @param locationCode    快照时源库位编码（迁移前库位）；null 表示未上架
 * @param version         快照时证物版本（迁移前版本）
 * @param firstConfirmer  第一确认保管人
 * @param secondConfirmer 第二确认保管人
 * @param snapshotAt      快照写入时间（双人确认提交时刻，Asia/Shanghai）
 */
public record SealSnapshot(
        Long id,
        String moveKey,
        String evidenceKey,
        String sealNo,
        EvidenceStatus evidenceStatus,
        String custodianId,
        String locationCode,
        long version,
        String firstConfirmer,
        String secondConfirmer,
        LocalDateTime snapshotAt) {
}
