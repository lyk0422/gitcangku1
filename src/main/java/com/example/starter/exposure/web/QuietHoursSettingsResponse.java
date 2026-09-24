package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.VisitorQuietHours;

/**
 * 访客静默时段设置视图。访客未登记时不返回（404）。
 *
 * @param visitorId         访客编号
 * @param utcOffsetMinutes  UTC 偏移分钟（-720～840）
 * @param quietStartMinute  静默开始本地分钟（0～1439）
 * @param quietEndMinute    静默结束本地分钟（0～1439，排他；起大于止为跨零点）
 * @param allowCritical     静默时段内是否放行 CRITICAL
 * @param version           当前乐观锁版本，下次修改须作为 expectedVersion 上送
 * @param createdAtUtc      首次登记时刻，epoch 毫秒，UTC
 * @param updatedAtUtc      最近修改时刻，epoch 毫秒，UTC
 */
public record QuietHoursSettingsResponse(
        String visitorId,
        int utcOffsetMinutes,
        int quietStartMinute,
        int quietEndMinute,
        boolean allowCritical,
        int version,
        long createdAtUtc,
        long updatedAtUtc
) {
    public static QuietHoursSettingsResponse from(VisitorQuietHours settings) {
        return new QuietHoursSettingsResponse(
                settings.visitorId(),
                settings.utcOffsetMinutes(),
                settings.quietStartMinute(),
                settings.quietEndMinute(),
                settings.allowCritical(),
                settings.version(),
                settings.createdAtUtc(),
                settings.updatedAtUtc());
    }
}
