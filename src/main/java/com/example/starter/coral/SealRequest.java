package com.example.starter.coral;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 样方封存请求。requestId为幂等编号。
 */
public record SealRequest(@NotBlank @Size(max = 64) String requestId) {
}