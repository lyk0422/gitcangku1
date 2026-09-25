package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建站台请求。requestKey 为幂等键，指纹含操作者、站台代码与有效长度。
 */
public record PlatformCreateRequest(
        @NotBlank String requestKey,
        @NotBlank String operator,
        @NotBlank String code,
        @NotNull @Min(1) Integer effectiveLength) {
}
