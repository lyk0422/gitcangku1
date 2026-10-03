package com.example.starter.coral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 移植登记请求。requestId为幂等编号；plantCode项目内唯一；时刻由服务端以UTC固化。
 */
public record RegisterTransplantRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String plantCode,
        @NotBlank @Size(max = 128) String nursery,
        @NotBlank @Size(max = 64) String plotCode) {
}