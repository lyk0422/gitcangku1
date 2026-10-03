package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 指派请求，requestId 为幂等键。
 */
public record AssignRequest(@NotBlank String pilotCode, @NotBlank String requestId) {
}