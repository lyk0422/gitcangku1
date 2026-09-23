package com.example.starter.restitution.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 追加证据请求：evidenceKey 案内唯一，summary 非空，证据只存摘要。
 */
public record AddEvidenceRequest(
        @NotBlank(message = "evidenceKey 不能为空")
        @Size(max = 64)
        String evidenceKey,
        @NotBlank(message = "证据摘要不能为空")
        @Size(max = 4000)
        String summary
) {
}
