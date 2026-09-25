package com.example.starter.exposure.web;

import java.util.List;

/**
 * 访客抑制区间历史视图：含该访客在公告下的全部区间（ACTIVE 与 DELETED）
 * 及不可变删除记录。
 *
 * @param campaignId    公告编号
 * @param visitorId     访客编号
 * @param intervals     全部区间（含已删除快照），按开始时刻升序
 * @param deleteRecords 不可变删除记录，按删除时刻升序
 */
public record SuppressionHistoryResponse(
        String campaignId,
        String visitorId,
        List<SuppressionIntervalResponse> intervals,
        List<SuppressionDeleteRecordResponse> deleteRecords
) {
}
