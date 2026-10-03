package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 校准复核作废请求：携带作废原因。
 */
public record VoidCalibrationRequest(@NotBlank String reason) {
}