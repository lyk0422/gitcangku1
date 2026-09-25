package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 镜像源登记项：镜像标识与优先级（1 为最高）。
 */
public record MirrorSpec(
        @NotBlank String mirrorId,
        @Positive int priority) {
}
