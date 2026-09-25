package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 借出门禁判定结果：阻断原因列表为空表示允许新借出。
 *
 * @param evidenceKey 证物业务键
 * @param blocked     是否被阻断
 * @param reasons     借出阻断原因（如容器 INSPECTION_FAILED、证物待双人复核）
 */
public record LoanEligibility(
        String evidenceKey,
        boolean blocked,
        List<String> reasons) {
}
