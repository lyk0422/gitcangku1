package com.example.starter.exposure.budget.web;

import java.util.List;

/**
 * 账本证据查询只读视图：转移单元信息、冻结的规范化明细与前后账本快照，全部稳定排序。
 *
 * @param transferKey 转移单业务唯一键
 * @param requestId   激活请求幂等键
 * @param tenantId    租户编号
 * @param status      转移单状态
 * @param activatedAtUtc 激活时刻，epoch 毫秒，UTC
 * @param details     冻结的规范化明细，按（源,目标）升序
 * @param snapshots   前后账本快照，按 campaignId 升序
 */
public record TransferEvidenceResponse(
        String transferKey,
        String requestId,
        String tenantId,
        String status,
        long activatedAtUtc,
        List<BudgetTransferActivateResponse.FrozenDetail> details,
        List<Snapshot> snapshots
) {
    /**
     * 单活动前后账本快照。
     *
     * @param campaignId     活动编号
     * @param versionBefore  转移前版本号
     * @param versionAfter   转移后版本号
     * @param budgetBefore   转移前总预算，单位次
     * @param budgetAfter    转移后总预算，单位次
     * @param confirmedCount 激活时刻已确认曝光数，单位次
     * @param inflightCount  激活时刻在途预占数，单位次
     */
    public record Snapshot(
            String campaignId,
            long versionBefore,
            long versionAfter,
            long budgetBefore,
            long budgetAfter,
            long confirmedCount,
            long inflightCount
    ) {
    }
}
