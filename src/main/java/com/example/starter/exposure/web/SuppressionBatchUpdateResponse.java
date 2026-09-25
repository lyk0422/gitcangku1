package com.example.starter.exposure.web;

import java.util.List;

/**
 * 抑制名单批量更新结果。整批原子成功后返回公告新版本号与全部当前 ACTIVE 区间。
 *
 * @param campaignId     公告编号
 * @param version        变更后的公告版本号（原版本 +1）
 * @param activeIntervals 变更后公告下全部 ACTIVE 区间
 */
public record SuppressionBatchUpdateResponse(
        String campaignId,
        int version,
        List<SuppressionIntervalResponse> activeIntervals
) {
}
