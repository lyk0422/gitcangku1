package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录（均按发生顺序）
 * + 涉及该证物的合并事件。别名的保管链在合并时冻结，后续主号交接不回写别名历史。
 *
 * @param evidence    证物当前视图（别名含指向最终主号的 aliasOf）
 * @param transfers   历史交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封条核验记录（含归还时的封条核验），只追加
 * @param mergeEvents 涉及该证物的合并事件（作为主号或被合并号），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<MergeEventView> mergeEvents) {
}
