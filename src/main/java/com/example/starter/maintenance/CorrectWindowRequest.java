package com.example.starter.maintenance;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 追加更正窗口请求：对已封存（或仍需保留历史）的窗口只能追加更正，原窗口置为 SUPERSEDED。
 *
 * @param requestId           全局唯一请求标识（幂等去重键）
 * @param correctionWindowId  更正窗口唯一标识
 * @param startUtc            更正后维护开始时刻（UTC，区间左闭）
 * @param endUtc              更正后维护结束时刻（UTC，区间右开）
 * @param windowVersion       更正窗口维护版本号，必须大于被更正窗口的版本
 */
public record CorrectWindowRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String correctionWindowId,
        @NotNull Instant startUtc,
        @NotNull Instant endUtc,
        @NotNull @Min(1) Integer windowVersion) {
}
