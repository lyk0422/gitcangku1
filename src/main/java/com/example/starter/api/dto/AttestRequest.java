package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 摘要见证请求：由一名发布角色提交其见证的摘要。
 *
 * @param checksum 见证的内容校验摘要，须与该版本登记摘要逐字符一致
 */
public record AttestRequest(
        @NotBlank String checksum) {
}
