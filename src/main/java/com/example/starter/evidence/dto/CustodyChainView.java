package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部双人交接记录 + 全部借出记录 + 全部封签核验记录
 * + 全部跨案保管链事件（移出/移入/撤销反向链，均按发生顺序）。
 *
 * @param evidence    证物当前视图
 * @param transfers   历史双人交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封签核验记录（含归还时的封签核验），只追加
 * @param caseEvents  跨案保管链事件：来源案移出/目标案移入及撤销反向链，按提交顺序，只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<ChainEventView> caseEvents) {
}
