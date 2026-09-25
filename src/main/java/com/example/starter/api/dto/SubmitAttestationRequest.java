package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 提交制品来源证明请求：来源仓标识、构建摘要与证明等级。
 *
 * @param operator 操作者；为空时由 X-Operator 请求头提供
 */
public record SubmitAttestationRequest(
        @NotBlank String name,
        @Positive int version,
        @NotBlank String sourceRepository,
        @NotBlank String buildDigest,
        @Positive int attestationLevel,
        String operator) {
}
