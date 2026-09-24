package com.example.starter.exposure.web;

import com.example.starter.exposure.domain.VisitorQuietSettings;

/**
 * 访客静默设置视图。
 *
 * @param visitorId        合成访客编号
 * @param utcOffsetMinutes UTC 偏移分钟（−720～840）
 * @param quietStartMinute 静默起始本地分钟（0～1439），含该时刻
 * @param quietEndMinute   静默结束本地分钟（0～1439），不含该时刻；小于起始为跨零点
 * @param allowCritical    静默时段内是否允许 CRITICAL 照常曝光
 * @param version          当前版本号，下次修改须回传
 * @param updatedAtUtc     最近修改时刻，epoch 毫秒，UTC
 */
public record QuietSettingsResponse(
        String visitorId,
        int utcOffsetMinutes,
        int quietStartMinute,
        int quietEndMinute,
        boolean allowCritical,
        int version,
        long updatedAtUtc
) {
    public static QuietSettingsResponse from(VisitorQuietSettings s) {
        return new QuietSettingsResponse(
                s.visitorId(),
                s.utcOffsetMinutes(),
                s.quietStartMinute(),
                s.quietEndMinute(),
                s.allowCritical(),
                s.version(),
                s.updatedAtUtc());
    }
}
