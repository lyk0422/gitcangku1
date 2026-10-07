package com.example.starter.firmware.domain;

/**
 * 配置迁移规则，有序执行，顺序是发布单身份一部分。
 *
 * @param type      规则类型：RENAME 将 key 重命名为 targetKey（目标键不得已存在）；
 *                  MULTIPLY 将 key 的值乘 factor（结果须在 0~1000000 内）
 * @param key       源配置键
 * @param targetKey RENAME 目标键，仅 RENAME 使用，其余类型为 null
 * @param factor    MULTIPLY 乘数，取值 1~10，仅 MULTIPLY 使用，其余类型为 null
 */
public record MigrationRule(MigrationRuleType type, String key, String targetKey, Integer factor) {
}
