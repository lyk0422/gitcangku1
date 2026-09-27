package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * + 全部检验预约 + 全部检验事件（均按发生顺序，只追加）。
 *
 * @param evidence    证物当前视图
 * @param transfers   历史交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封条核验记录（含归还时的封条核验），只追加
 * @param exams       历史检验预约记录，只追加
 * @param examEvents  历史检验事件（预约/开始/完成/取消及封签版本），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<ExamReservationView> exams,
        List<ExamEventView> examEvents) {
}
