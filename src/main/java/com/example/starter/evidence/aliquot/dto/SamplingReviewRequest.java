package com.example.starter.evidence.aliquot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 联合取样审核请求（确认/拒绝共用）。
 * 第一次确认不携带版本；第二次确认必须携带申请版本（首次确认后为 1）和全部母样版本。
 *
 * @param commandKey     幂等命令键
 * @param note           审核备注，可空
 * @param requestVersion 第二次确认携带的申请版本；第一次确认与拒绝时为 null
 * @param sampleVersions 第二次确认携带的全部母样版本（母样键 -> 版本）；第一次确认与拒绝时为 null
 */
public record SamplingReviewRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String note,
        Long requestVersion,
        Map<@NotBlank String, @NotNull Long> sampleVersions) {
}
