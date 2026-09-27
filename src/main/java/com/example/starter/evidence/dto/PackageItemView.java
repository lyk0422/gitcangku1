package com.example.starter.evidence.dto;

import com.example.starter.evidence.ItemDiagnosisCode;

/**
 * 披露包逐件条目视图：固化后的链版本、位置、封签快照与生成摘要。
 *
 * @param position        规范排序后的件次（从 0 开始）
 * @param evidenceKey     证物业务键
 * @param chainVersion    截至时刻链版本
 * @param custodianAtAsOf 截至时刻保管人（位置快照）
 * @param sealSnapshot    截至时刻封签快照
 * @param summary         逐件生成摘要
 * @param diagnosis       逐件链诊断码（固化包内固定 OK）
 */
public record PackageItemView(
        int position,
        String evidenceKey,
        int chainVersion,
        String custodianAtAsOf,
        SealSnapshotView sealSnapshot,
        ItemSummaryView summary,
        ItemDiagnosisCode diagnosis) {
}
