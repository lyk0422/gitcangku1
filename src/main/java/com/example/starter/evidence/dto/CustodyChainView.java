package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * + 与该证物相关的两级保留冻结（案件级覆盖本证物 + 证物级）+ 全部销毁申请（均按发生顺序）。
 *
 * @param evidence     证物当前视图
 * @param transfers    历史交接记录，只追加
 * @param loans        历史借出记录，只追加
 * @param inspections  历史封条核验记录（含归还时的封条核验），只追加
 * @param holds        与本证物相关的案件级与证物级冻结（含解除链），effective 按查询时刻左闭右开判定
 * @param destructions 本证物全部销毁申请历史（含被拒记录，裁决不可改写）
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<HoldView> holds,
        List<DestructionView> destructions) {
}
