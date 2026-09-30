package com.example.starter.evidence.dto;

import com.example.starter.evidence.EvidenceStatus;
import com.example.starter.evidence.ItemDisposition;

import java.time.LocalDateTime;

/**
 * 巡检箱内清单条目视图：展示巡检时原位置/原保管人/原状态快照及每件当前隔离处置状态。
 * 快照字段在复核放行后保持不变。
 *
 * @param evidenceKey          清单项证物业务键
 * @param snapshotContainerKey 巡检时证物所在容器业务键（原位置快照）
 * @param snapshotCustodianId  巡检时证物保管人快照
 * @param snapshotStatus       巡检时证物状态快照
 * @param disposition          每件处置/隔离状态
 * @param releasedAt           复核放行时刻（Asia/Shanghai）；null 表示尚未放行
 */
public record InspectionItemView(
        String evidenceKey,
        String snapshotContainerKey,
        String snapshotCustodianId,
        EvidenceStatus snapshotStatus,
        ItemDisposition disposition,
        LocalDateTime releasedAt) {
}
