package com.example.starter.calibration;

import jakarta.validation.constraints.NotBlank;

/**
 * 复核作废请求。
 *
 * @param reason 作废原因
 */
public record InvalidateRequest(@NotBlank String reason) {
}