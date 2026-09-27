package com.example.starter.evidence.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 跨案移交诊断视图（只读，不改变状态）：给出当前 UTC 时刻、要求值/实际值/差额，
 * 以及令版本、授权、每件证物的可移交判定。
 *
 * @param now                 诊断时刻（UTC）
 * @param fromCaseKey         请求来源案件
 * @param toCaseKey           请求目标案件
 * @param requestedItemCount  请求证物数量（去重后实际值）
 * @param existingItemCount   实际存在的证物数量
 * @param blockedItemCount    不满足前置条件的证物数量（差额）
 * @param custodiansDistinct  双方保管人是否不同
 * @param order               移交令版本诊断
 * @param grants              双方授权诊断
 * @param items               每件证物诊断（规范化排序）
 * @param transferable        完整最终状态是否满足、可否整批移交
 */
public record CaseTransferDiagnosisView(
        LocalDateTime now,
        String fromCaseKey,
        String toCaseKey,
        int requestedItemCount,
        int existingItemCount,
        long blockedItemCount,
        boolean custodiansDistinct,
        OrderStatusView order,
        List<GrantStatusView> grants,
        List<TransferItemDiagnosisView> items,
        boolean transferable) {
}
