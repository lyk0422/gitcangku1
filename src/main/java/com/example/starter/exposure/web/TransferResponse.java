package com.example.starter.exposure.web;

import java.util.List;

/**
 * 预算转移单视图：激活结果与账本证据查询共用，只读且稳定排序。
 *
 * @param transferKey 转移单业务编号
 * @param status      转移单状态，成功激活固定为 ACTIVATED
 * @param lines       冻结的规范化转移明细，按（源,目标）稳定排序
 * @param before      激活前各活动账本快照，按 campaignId 稳定排序
 * @param after       激活后各活动账本快照，按 campaignId 稳定排序
 * @param createdAtUtc 激活时刻，epoch 毫秒，UTC
 */
public record TransferResponse(
        String transferKey,
        String status,
        List<TransferLineResponse> lines,
        List<AccountStateResponse> before,
        List<AccountStateResponse> after,
        long createdAtUtc
) {
}
