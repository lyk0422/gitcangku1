package com.example.starter.snow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 审核确认请求。
 *
 * @param requestId 幂等键
 * @param reviewer 审核员标识；两次确认必须为不同审核员
 */
public record ConfirmationRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String reviewer) {
}