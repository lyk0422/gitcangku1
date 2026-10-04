package com.example.starter.plan.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 创建区段牵引档案请求，初始版本为 1。requestId 为幂等键；operator 为操作者标识。
 */
public record CreateSectionProfileRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String sectionId,
        @NotNull Boolean electrified,
        @NotNull Boolean dieselAllowed) {
}