package com.example.starter.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 镜像可用性变更请求：true=标记可用，false=标记不可用。
 */
public record MirrorAvailabilityRequest(@NotNull Boolean available) {
}
