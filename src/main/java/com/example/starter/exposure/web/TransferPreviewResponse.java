package com.example.starter.exposure.web;

import java.util.List;

/**
 * 预算转移预览响应：按完整后态返回各活动账本状态，不写数据。
 *
 * @param lines    规范化（按源目标求和、稳定排序）后的转移明细
 * @param accounts 转移后各活动账本状态，按 campaignId 稳定排序
 */
public record TransferPreviewResponse(
        List<TransferLineResponse> lines,
        List<AccountStateResponse> accounts
) {
}
