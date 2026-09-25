package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 许可证策略登记请求。
 *
 * <p>scopeType=LOCK_FILE 时必须提供 lockFileId；scopeType=ARTIFACT 时必须提供
 * artifactName，artifactVersion 缺省表示该名称全部版本。textKey/textVersion 为
 * 告知文本绑定，可同时缺省（发布时判定为告知缺失）。
 */
public record LicensePolicyRequest(
        @NotBlank String scopeType,
        @Positive Long lockFileId,
        String artifactName,
        @Positive Integer artifactVersion,
        @NotBlank String noticeType,
        String textKey,
        @Positive Integer textVersion) {
}
