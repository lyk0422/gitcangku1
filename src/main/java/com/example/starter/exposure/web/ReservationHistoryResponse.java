package com.example.starter.exposure.web;

import java.util.List;

/**
 * 预占历史查询视图（只读）。申请时刻区间为左闭右开 [fromCreatedAtUtc, toCreatedAtUtc)，
 * 边界缺省为 null；结果按申请时刻降序、预占单编号升序排列。
 *
 * @param activityId         活动过滤条件；不过滤为 null
 * @param campaignId         公告过滤条件；不过滤为 null
 * @param visitorId          访客过滤条件；不过滤为 null
 * @param fromCreatedAtUtc   申请时刻区间起点（含），epoch 毫秒 UTC；缺省 null
 * @param toCreatedAtUtc     申请时刻区间终点（不含），epoch 毫秒 UTC；缺省 null
 * @param limit              本次返回条数上限
 * @param totalReturned      实际返回条数
 * @param reservations       预占明细列表
 */
public record ReservationHistoryResponse(
        String activityId,
        String campaignId,
        String visitorId,
        Long fromCreatedAtUtc,
        Long toCreatedAtUtc,
        int limit,
        int totalReturned,
        List<ReservationResponse> reservations
) {
}
