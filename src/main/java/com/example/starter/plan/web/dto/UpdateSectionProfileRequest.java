package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 变更区段牵引档案请求，expectedVersion 必须与当前版本一致，成功版本加一。
 * requestId 为幂等键；operator 为操作者标识。
 */
public record UpdateSectionProfileRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Boolean electrified,
        @NotNull Boolean dieselAllowed,
        @NotNull Integer expectedVersion) {
}