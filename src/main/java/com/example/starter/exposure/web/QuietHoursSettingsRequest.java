package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记/修改访客静默时段请求。
 *
 * @param requestId         写操作全局唯一幂等键
 * @param visitorId         访客编号
 * @param utcOffsetMinutes  UTC 偏移分钟，取值 -720～840（-12～+14 小时）
 * @param quietStartMinute  每日静默开始本地分钟，0～1439，与结束不同
 * @param quietEndMinute    每日静默结束本地分钟，0～1439（排他）；起大于止为跨零点
 * @param allowCritical     静默时段内是否放行 CRITICAL 公告
 * @param expectedVersion   访客当前设置版本；首次登记传 0，后续修改须传当前版本，冲突 409
 */
public record QuietHoursSettingsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull @Min(-720) @Max(840) Integer utcOffsetMinutes,
        @NotNull @Min(0) @Max(1439) Integer quietStartMinute,
        @NotNull @Min(0) @Max(1439) Integer quietEndMinute,
        @NotNull Boolean allowCritical,
        @NotNull @Min(0) Integer expectedVersion
) {
}
