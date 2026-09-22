package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交审核请求：必须明确航线版本与全局空域版本，任一不是当前版本返回 409。
 */
public record CreateReviewRequest(
        @NotBlank String requestId,
        @NotBlank String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion) {
}
