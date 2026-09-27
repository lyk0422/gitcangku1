package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 批量审查的单个航线项。
 *
 * @param routeId      航线唯一标识
 * @param routeVersion 明确的航线版本（须为当前版本）
 */
public record BatchReviewItemDto(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion) {
}
