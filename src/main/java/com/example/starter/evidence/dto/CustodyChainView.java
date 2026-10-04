package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 完整保管链视图：证物当前状态 + 全部交接记录 + 全部借出记录 + 全部封条核验记录
 * + 受监管交接的检查点名单、签到与在途异常（均按发生顺序，历史交接的旧链原样保留）。
 *
 * @param evidence    证物当前视图
 * @param transfers   历史交接记录，只追加
 * @param loans       历史借出记录，只追加
 * @param inspections 历史封条核验记录（含归还时的封条核验），只追加
 * @param checkpoints 受监管交接检查点名单（含历史交接），只追加
 * @param signins     检查点签到记录（含历史交接），只追加
 * @param anomalies   在途异常记录（含历史交接），只追加
 */
public record CustodyChainView(
        EvidenceView evidence,
        List<TransferView> transfers,
        List<LoanView> loans,
        List<InspectionView> inspections,
        List<CheckpointView> checkpoints,
        List<SigninView> signins,
        List<AnomalyView> anomalies) {
}