package com.example.starter.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量策略迁移预校验请求：以候选策略参数评估全部锁定图，不写入任何数据。
 */
public record MigrationCheckRequest(
        @Positive int minLevel,
        @NotEmpty @Size(max = 10) List<@NotBlank String> allowedRepos) {
}
