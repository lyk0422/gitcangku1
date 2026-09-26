package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部核验记录
 * + 全部跨案双案链事件（均按发生顺序）。
 *
 * @param evidence    证物当前视图
 * @param transfers   历史交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封条核验记录（含归还时的封条核验），只追加
 * @param caseLinks   历史跨案双案链事件（移出/移入/撤销反向链），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<CustodyCaseLinkView> caseLinks) {
}
