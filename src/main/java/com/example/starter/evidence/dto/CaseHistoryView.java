package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 证物跨案历史视图（只读，不改变状态）：证物当前视图 + 全部跨案移交
 * + 全部案件保管链事件 + 全部双案封存快照，均按发生顺序。
 *
 * @param evidence  证物当前视图
 * @param transfers 历史跨案移交（含已撤销），只追加
 * @param events    历史案件保管链事件，只追加
 * @param snapshots 历史双案封存快照，只追加（撤销不删除）
 */
public record CaseHistoryView(
        EvidenceView evidence,
        List<CaseTransferView> transfers,
        List<CaseChainEventView> events,
        List<CaseSealSnapshotView> snapshots) {
}
