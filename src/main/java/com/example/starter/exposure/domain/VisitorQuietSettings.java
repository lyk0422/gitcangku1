package com.example.starter.exposure.domain;

/**
 * 访客静默设置 PO。访客未登记时视为无静默（不抑制任何类别）。
 *
 * <p>静默区间以访客登记的 UTC 偏移换算的本地分钟表达，区间左闭右开；
 * {@code quietStartMinute &gt; quietEndMinute} 表示跨零点区间。</p>
 *
 * @param visitorId         合成访客编号
 * @param utcOffsetMinutes  UTC 偏移分钟，取值 −720～840（如东八区为 480）
 * @param quietStartMinute  每日静默起始本地分钟，取值 0～1439，区间含该时刻
 * @param quietEndMinute    每日静默结束本地分钟，取值 0～1439，与起始不同；区间不含该时刻
 * @param allowCritical     静默时段内是否允许 CRITICAL 公告照常曝光；false 时紧急公告同样抑制
 * @param version           乐观版本号，首次登记为 1，每次修改 +1；修改须带 expectedVersion
 * @param updatedAtUtc      最近一次登记/修改时刻，epoch 毫秒，UTC
 */
public record VisitorQuietSettings(
        String visitorId,
        int utcOffsetMinutes,
        int quietStartMinute,
        int quietEndMinute,
        boolean allowCritical,
        int version,
        long updatedAtUtc
) {
}
