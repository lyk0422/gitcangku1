package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求：expectedVersion 必须等于当前航线版本，成功后版本加一并使当前审核失效。
 */
public record ReplaceRouteRequest(
        @NotBlank String requestId,
        @NotNull Integer expectedVersion,
        @NotNull @Size(min = 2, max = 50) List<PointDto> points) {
}
