package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 许可证策略登记请求。
 *
 * <p>作用域二选一：{@code LOCK}（按精确锁定图版本，须填 lockFileId）或
 * {@code COORDINATE}（按制品坐标，须填 artifactName，artifactVersion 可空表示该名称全部版本）。
 *
 * @param scopeType       作用域类型：LOCK / COORDINATE
 * @param lockFileId      LOCK 作用域关联的锁文件 ID
 * @param artifactName    COORDINATE 作用域制品名称
 * @param artifactVersion COORDINATE 作用域制品版本，空表示全部版本
 * @param licenseId       许可证标识
 * @param action          策略动作：NOTICE_REQUIRED / ALLOWED
 */
public record RegisterPolicyRequest(
        @NotBlank String scopeType,
        Long lockFileId,
        String artifactName,
        @Positive Integer artifactVersion,
        @NotBlank String licenseId,
        @NotBlank String action) {
}
