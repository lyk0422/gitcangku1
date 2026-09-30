package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 案件两级冻结视图：案件级冻结与指定证物的证物级冻结分别展示。
 *
 * @param caseKey       案件键
 * @param evidenceKey   证物业务键
 * @param caseHolds     所属案件的全部案件级冻结（含生效、已解除、已到期）
 * @param evidenceHolds 该证物的全部证物级冻结（含生效、已解除、已到期）
 */
public record EvidenceHoldsView(
        String caseKey,
        String evidenceKey,
        List<HoldView> caseHolds,
        List<HoldView> evidenceHolds) {
}
