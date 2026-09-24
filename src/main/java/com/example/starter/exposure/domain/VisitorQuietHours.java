package com.example.starter.exposure.domain;

/**
 * 访客静默时段设置 PO。访客未登记则视为无静默。
 *
 * <p>所有时刻语义均基于访客固定 UTC 偏移换算的本地时间，与 UTC 日期无关；
 * 修改设置只影响后续申请，历史预占与日账目不改写。</p>
 *
 * @param visitorId          访客编号，全局唯一
 * @param utcOffsetMinutes   访客本地时间相对 UTC 的偏移分钟，取值 -720～840（-12～+14 小时）
 * @param quietStartMinute   每日静默开始的本地分钟（0～1439），与结束分钟不同
 * @param quietEndMinute     每日静默结束的本地分钟（0～1439，排他边界）；
 *                           起大于止表示跨零点（如 22:00～次日 06:00）
 * @param allowCritical      静默时段内是否放行 CRITICAL 公告；false 时 CRITICAL 同样抑制
 * @param version            乐观锁版本号，首次登记为 1，每次修改 +1；修改须携带 expectedVersion
 * @param createdAtUtc       首次登记时刻，epoch 毫秒，UTC
 * @param updatedAtUtc       最近修改时刻，epoch 毫秒，UTC
 */
public record VisitorQuietHours(
        String visitorId,
        int utcOffsetMinutes,
        int quietStartMinute,
        int quietEndMinute,
        boolean allowCritical,
        int version,
        long createdAtUtc,
        long updatedAtUtc
) {
}
