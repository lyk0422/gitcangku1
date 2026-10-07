package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.MigrationRuleType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 配置迁移规则请求项。RENAME 需 targetKey 且目标键在预演时不得已存在；
 * MULTIPLY 需 factor（1~10），结果值须在 0~1000000 内。类型与参数的配套关系由服务层校验。
 */
public record MigrationRuleInput(
        @NotNull MigrationRuleType type,
        @NotBlank @Size(max = 64) String key,
        @Size(max = 64) String targetKey,
        @Min(1) @Max(10) Integer factor) {
}
