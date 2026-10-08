package com.example.starter.firmware.domain;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 配置迁移规则，发布单内至多5条，按声明顺序执行，顺序是发布单身份的一部分。
 *
 * @param type       规则类型：RENAME 重命名键，MULTIPLY 键值乘整数
 * @param key        源配置键，非空
 * @param targetKey  RENAME 的目标键，不得与现有键同名；MULTIPLY 必须为 null
 * @param multiplier MULTIPLY 的乘数，取值1~10；RENAME 必须为 null
 */
public record MigrationRule(
        @NotNull RuleType type,
        @NotBlank @Size(max = 64) String key,
        @Size(max = 64) String targetKey,
        @Min(1) @Max(10) Integer multiplier) {
}
