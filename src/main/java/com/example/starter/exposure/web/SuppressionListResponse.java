package com.example.starter.exposure.web;

import java.util.List;

/**
 * 抑制名单视图：公告当前活动版本与区间列表（含历史状态记录）。
 *
 * @param campaignId 公告编号
 * @param version    当前活动版本
 * @param intervals  区间列表，按创建时刻升序
 */
public record SuppressionListResponse(
        String campaignId,
        long version,
        List<SuppressionIntervalResponse> intervals
) {
}
