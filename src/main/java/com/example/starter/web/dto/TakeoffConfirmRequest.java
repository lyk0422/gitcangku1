package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 起飞确认请求。
 *
 * @param requestId 幂等请求号
 */
public record TakeoffConfirmRequest(@NotBlank String requestId) {
}