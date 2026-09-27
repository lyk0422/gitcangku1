package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 见证提交请求：发布角色对制品版本已登记摘要的见证。
 *
 * @param role     发布角色标识（操作者），同一版本内同一角色仅允许一条有效见证
 * @param checksum 见证的内容校验摘要，必须与已登记摘要一致（规范化后比较）
 */
public record AttestationRequest(
        @NotBlank String role,
        @NotBlank String checksum) {
}
