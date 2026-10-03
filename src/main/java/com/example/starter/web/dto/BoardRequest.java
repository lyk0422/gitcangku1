package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登轮确认请求，requestId 为幂等键。
 */
public record BoardRequest(@NotBlank String requestId) {
}