package com.example.starter.coral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 复核请求。requestId为幂等编号；result为本次复核结论。
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull TransplantStatus result) {
}