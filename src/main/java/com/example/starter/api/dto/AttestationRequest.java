package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 登记来源证明请求：同一坐标重复登记生成递增证明版本。
 */
public record AttestationRequest(
        @NotBlank String name,
        @Positive int version,
        @NotBlank String repoId,
        @NotBlank String digest,
        @Positive int level) {
}
