package com.example.starter.restitution.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 追加证据请求：只存非空摘要。
 *
 * @param evidenceKey 案内唯一证据键
 * @param summary     非空证据摘要
 */
public record AddEvidenceRequest(
        @NotBlank(message = "evidenceKey 不能为空") String evidenceKey,
        @NotBlank(message = "summary 不能为空") String summary) {
}
