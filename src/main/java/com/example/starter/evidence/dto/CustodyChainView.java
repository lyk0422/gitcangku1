package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * + 案件级/证物级保留冻结（有效状态、到期与解除链）+ 全部销毁申请历史（均按发生顺序）。
 *
 * @param evidence     证物当前视图
 * @param transfers    历史交接记录，只追加
 * @param loans        历史借出记录，只追加
 * @param inspections  历史封条核验记录（含归还时的封条核验），只追加
 * @param caseHolds    所属案件的案件级冻结（含生效、已解除、已到期）
 * @param evidenceHolds 该证物的证物级冻结（含生效、已解除、已到期）
 * @param destructions 全部销毁申请记录（含被有效冻结阻断的 REJECTED 记录），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<HoldView> caseHolds,
        List<HoldView> evidenceHolds,
        List<DestructionView> destructions) {
}
