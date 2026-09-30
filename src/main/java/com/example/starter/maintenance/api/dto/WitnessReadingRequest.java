package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 见证大幅读数修订请求：见证人确认待确认修订的指定版本后，该修订才生效。
 *
 * @param requestId          全局唯一请求标识（幂等键）
 * @param expectedVersion    设备期望版本号，与当前版本不一致时返回 409
 * @param expectedRevisionNo 期望见证的待生效修订号；与当前待确认版本不一致时返回 422
 * @param cumulativeMinutes  期望见证的待确认累计工时（分钟），与申请值不一致时返回 422
 * @param witnessedBy        见证人标识，不可为空白且不得与修订提交人相同（同人见证返回 422）
 */
public record WitnessReadingRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotNull @Positive Integer expectedRevisionNo,
        @NotNull @Positive(message = "待见证累计工时必须为正数") Long cumulativeMinutes,
        @NotBlank String witnessedBy) {
}
