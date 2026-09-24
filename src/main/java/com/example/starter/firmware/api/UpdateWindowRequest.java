package com.example.starter.firmware.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 设备维护窗口修改请求。三项窗口参数必填且起止不同；expectedVersion 为设备配置版本乐观锁。
 * 修改只影响后续拉取判定，不改写已下发任务、已提交回执和历史顺延记录。
 */
public record UpdateWindowRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull @Min(1) Integer expectedVersion,
        @NotNull @Min(-720) @Max(840) Integer utcOffsetMinutes,
        @NotNull @Min(0) @Max(1439) Integer windowStartMinute,
        @NotNull @Min(0) @Max(1439) Integer windowEndMinute) {
}
