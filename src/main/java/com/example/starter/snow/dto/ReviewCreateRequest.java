package com.example.starter.snow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 发起解除审核请求。
 *
 * @param requestId 幂等键
 */
public record ReviewCreateRequest(@NotBlank @Size(max = 64) String requestId) {
}