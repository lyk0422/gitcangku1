package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 起飞确认请求，requestId用于幂等。 */
public record TakeoffConfirmRequest(
        @NotBlank(message = "requestId不能为空") String requestId,
        @NotNull(message = "flightId不能为空") Long flightId) {
}