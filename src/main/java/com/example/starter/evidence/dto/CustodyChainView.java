package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * + 与该证物相关的保留冻结（证物自身冻结及其所属案件的案件级冻结，含有效性、到期与解除链）
 * + 全部销毁申请记录（含历史 DENIED，不随后续冻结到期/解除改写），均按发生顺序。
 *
 * @param evidence     证物当前视图
 * @param transfers    历史交接记录，只追加
 * @param loans        历史借出记录，只追加
 * @param inspections  历史封条核验记录（含归还时的封条核验），只追加
 * @param holds        证物级冻结 + 所属案件的案件级冻结，按发生顺序
 * @param destructions 历史销毁申请，只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<HoldView> holds,
        List<DestructionView> destructions) {
}
