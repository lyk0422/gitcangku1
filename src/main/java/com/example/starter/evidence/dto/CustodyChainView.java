package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 历史位置轨迹 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * （均按发生顺序）。位置轨迹只追加，复核放行不改写隔离时的原位置快照。
 *
 * @param evidence    证物当前视图
 * @param locations   历史位置轨迹，只追加
 * @param transfers   历史交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封条核验记录（含归还时的封条核验），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<LocationView> locations,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections) {
}
