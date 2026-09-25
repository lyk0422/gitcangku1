package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 发布锁定图请求：operator 参与 provenanceKey 指纹计算。
 */
public record PublishRequest(
        @NotBlank String operator) {
}
