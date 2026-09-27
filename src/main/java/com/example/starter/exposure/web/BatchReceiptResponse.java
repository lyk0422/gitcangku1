package com.example.starter.exposure.web;

import java.util.List;

/**
 * 批量回执响应。results 按提交顺序排列；数量字段为整批实际裁决结果。
 *
 * @param requestId       批量请求的幂等键
 * @param campaignId      本批回执归属的公告编号
 * @param totalCount      本批回执总数，单位条
 * @param attributedCount 窗口内计入归因的回执数，单位条
 * @param lateCount       窗口外迟到回执数，单位条
 * @param results         各回执的裁决结果，顺序与请求 items 一致
 */
public record BatchReceiptResponse(
        String requestId,
        String campaignId,
        int totalCount,
        int attributedCount,
        int lateCount,
        List<ReceiptResponse> results
) {
}
