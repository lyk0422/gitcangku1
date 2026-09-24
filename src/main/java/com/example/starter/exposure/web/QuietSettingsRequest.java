package com.example.starter.exposure.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记/修改访客静默设置请求。
 *
 * <p>每日静默区间以本地分钟表达，区间左闭右开，起止必须不同；
 * {@code quietStartMinute &gt; quietEndMinute} 表示跨零点（如 22:00～06:00）。</p>
 *
 * @param requestId        写操作全局唯一幂等键
 * @param visitorId        合成访客编号
 * @param utcOffsetMinutes UTC 偏移分钟，取值 −720～840
 * @param quietStartMinute 静默起始本地分钟，取值 0～1439
 * @param quietEndMinute   静默结束本地分钟，取值 0～1439，且不等于起始
 * @param allowCritical    静默时段内是否允许 CRITICAL 照常曝光
 * @param expectedVersion  访客当前设置版本；首次登记传 0，已有设置须传其最新版本，冲突返回 409
 */
public record QuietSettingsRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String visitorId,
        @NotNull @Min(-720) @Max(840) Integer utcOffsetMinutes,
        @NotNull @Min(0) @Max(1439) Integer quietStartMinute,
        @NotNull @Min(0) @Max(1439) Integer quietEndMinute,
        @NotNull Boolean allowCritical,
        @NotNull @Min(0) Integer expectedVersion
) {
}
