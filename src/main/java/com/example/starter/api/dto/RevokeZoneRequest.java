package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 撤销禁飞区请求：仅携带幂等键，zoneId 来自路径。
 */
public record RevokeZoneRequest(@NotBlank String requestId) {
}
