package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 巡检箱内清单条目实体，对应 inspection_item 表。
 * 双人确认完成时冻结每件证物的原位置/原保管人/原状态/待接收交接快照；只追加，
 * 复核放行仅更新处置状态与放行时刻，快照字段永不改写。
 *
 * @param id                      主键
 * @param inspectionId            关联巡检单ID
 * @param evidenceKey             清单项证物业务键
 * @param snapshotContainerKey    巡检时证物所在容器业务键（原位置快照）
 * @param snapshotCustodianId     巡检时证物保管人快照
 * @param snapshotStatus          巡检时证物状态快照
 * @param snapshotTransferId      巡检时待接收交接记录ID快照；null 表示无待接收交接
 * @param disposition             每件处置/隔离状态
 * @param releasedAt              复核放行时刻（Asia/Shanghai）；null 表示尚未放行
 * @param createdAt               清单冻结时间（Asia/Shanghai）
 */
public record InspectionItem(
        Long id,
        Long inspectionId,
        String evidenceKey,
        String snapshotContainerKey,
        String snapshotCustodianId,
        EvidenceStatus snapshotStatus,
        Long snapshotTransferId,
        ItemDisposition disposition,
        LocalDateTime releasedAt,
        LocalDateTime createdAt) {
}
