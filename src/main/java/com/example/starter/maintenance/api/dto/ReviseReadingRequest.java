package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 修订读数请求：只改变累计分钟，不改采样时刻。作为历史保养锚点的读数不可修订（409）；
 * 修订值与当前有效值差额绝对值超出设备见证阈值时先进入待确认，须另一名维护人见证后才生效。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param cumulativeMinutes  修订后的累计工时（分钟），非负整数，须同时符合前后相邻读数值
 * @param submitterId        提交修订的维护人标识，不可为空白；见证人不得与提交人相同
 */
public record ReviseReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @PositiveOrZero Long cumulativeMinutes,
        @NotBlank String submitterId) {
}
