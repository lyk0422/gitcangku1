package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 告知绑定请求：将命中指定许可证的作用域制品绑定到某告知文本版本。
 *
 * <p>作用域二选一：{@code LOCK}（lockFileId 必填）或
 * {@code COORDINATE}（artifactName 必填，artifactVersion 可空表示全部版本）。
 *
 * @param scopeType       作用域类型：LOCK / COORDINATE
 * @param lockFileId      LOCK 作用域锁文件 ID
 * @param artifactName    COORDINATE 作用域制品名称
 * @param artifactVersion COORDINATE 作用域制品版本，空表示全部版本
 * @param licenseId       绑定针对的许可证标识
 * @param noticeKey       告知文本业务标识
 * @param noticeVersion   告知文本版本号
 */
public record BindNoticeRequest(
        @NotBlank String scopeType,
        Long lockFileId,
        String artifactName,
        @Positive Integer artifactVersion,
        @NotBlank String licenseId,
        @NotBlank String noticeKey,
        @Positive int noticeVersion) {
}
