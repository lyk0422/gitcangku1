package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 区段绑定/改绑变电所请求。首次绑定时 expectedVersion 应为空或 0；
 * 改绑定时必须与当前绑定版本一致。绑定前须取消该区段的已发布占用。
 */
public record BindSectionRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        Integer expectedVersion,
        @NotBlank String substationKey) {
}
