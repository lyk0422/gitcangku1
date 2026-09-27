package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * + 全部位置迁移封签快照（均按发生顺序）。
 *
 * @param evidence       证物当前视图
 * @param transfers      历史交接记录，只追加
 * @param loans          历史借出记录，只追加
 * @param inspections    历史封条核验记录（含归还时的封条核验），只追加
 * @param sealSnapshots  历史位置迁移封签核验快照，只追加、不可变
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<SealSnapshotView> sealSnapshots) {
}
