package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量发布请求：一次改动多个计划，按最终站台占用和最终编组联合裁决，
 * 任一超长或同站台重叠即 422 并列出计划和站台，整批不写入。
 */
public record BatchPublishRequest(
        @NotBlank String requestKey,
        @NotNull @NotEmpty @Size(max = 50) List<@NotBlank String> scheduleKeys) {
}
