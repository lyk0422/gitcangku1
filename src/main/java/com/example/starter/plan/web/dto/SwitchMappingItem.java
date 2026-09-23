package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 封锁切换单条旧→替代映射。两个期望版本在激活事务内重新校验，
 * 登记后任一计划版本变化都会导致激活 409 并整体回滚。
 */
public record SwitchMappingItem(
        @NotBlank String oldScheduleKey,
        @NotBlank String replacementScheduleKey,
        @NotNull Integer expectedOldVersion,
        @NotNull Integer expectedReplacementVersion) {
}
