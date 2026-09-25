package com.example.starter.api.dto;

import java.time.Instant;

/**
 * 许可证策略视图。
 *
 * @param id              策略 ID
 * @param scopeType       作用域类型：LOCK / COORDINATE
 * @param lockFileId      LOCK 作用域锁文件 ID；COORDINATE 作用域为 null
 * @param artifactName    COORDINATE 作用域制品名称；LOCK 作用域为 null
 * @param artifactVersion COORDINATE 作用域制品版本，null 表示该名称全部版本
 * @param licenseId       许可证标识
 * @param action          策略动作：NOTICE_REQUIRED / ALLOWED
 * @param createdAt       策略登记时间，UTC
 */
public record LicensePolicyResponse(
        long id,
        String scopeType,
        Long lockFileId,
        String artifactName,
        Integer artifactVersion,
        String licenseId,
        String action,
        Instant createdAt) {
}
