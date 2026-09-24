package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备偏移与维护窗口修订请求。只影响后续拉取判定，不改写已下发任务、已提交回执与历史顺延记录。
 *
 * @param requestId        全局幂等请求ID
 * @param expectedVersion  设备当前 deviceVersion，不匹配返回 409
 * @param utcOffsetMinutes 新的 UTC 偏移分钟，-720~840
 * @param windowStartMinute 新窗口本地开始分钟，0~1439
 * @param windowEndMinute  新窗口本地结束分钟（左闭右开），0~1439，须与开始不同；开始大于结束表示跨零点
 */
public record UpdateDeviceWindowRequest(
        @NotBlank @Size(max = 64) String requestId,
        @Min(1) @NotNull Integer expectedVersion,
        @Min(-720) @Max(840) int utcOffsetMinutes,
        @Min(0) @Max(1439) int windowStartMinute,
        @Min(0) @Max(1439) int windowEndMinute) {
}
