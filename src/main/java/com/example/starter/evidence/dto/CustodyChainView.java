package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部核验记录 + 全部合并事件（均按发生顺序）。
 * 合并事件中的身份、版本与链快照为合并时刻冻结值；副号历史仍按原号读取。
 *
 * @param evidence    证物当前视图
 * @param transfers   历史交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封条核验记录（含归还时的封条核验），只追加
 * @param merges      历史合并事件（作为主号或副号参与），只追加；别名通过其 primaryKey 指向最终主号
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<MergeView> merges) {
}
