package com.example.starter.race.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 登记选手请求。
 *
 * @param requestId      全局唯一请求ID
 * @param bib            参赛号，赛事内唯一
 * @param expectedVersion 期望的赛事当前版本，不一致返回409
 * @param rawTimeMs      可选的原始完赛耗时（毫秒，1~86400000）；缺省表示计时缺失
 */
public record RegisterParticipantRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String bib,
        @NotNull Long expectedVersion,
        @Min(1) @Max(86400000) Long rawTimeMs) {
}
