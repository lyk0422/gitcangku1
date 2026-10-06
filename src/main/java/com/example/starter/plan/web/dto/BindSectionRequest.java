package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 区段绑定/改绑变电所请求，expectedVersion 对目标变电所做乐观校验。
 * 未绑定旧区段绑定前须取消其旧已发布占用；改绑使现存计划超限则 409。
 */
public record BindSectionRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String substationKey,
        @NotNull Integer expectedVersion) {
}
