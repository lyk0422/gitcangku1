package com.example.starter.snow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 人工封闭命令请求。
 *
 * @param requestId 幂等键
 * @param reason 封闭原因
 */
public record ClosureRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 255) String reason) {
}