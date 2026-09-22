package com.example.starter.firmware.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 设备回执请求。
 *
 * @param requestId 全局唯一请求号，用于幂等去重
 * @param result    回执结果：SUCCESS 或 FAILED
 */
public record ReceiptRequest(
        @NotBlank String requestId,
        @NotBlank @Pattern(regexp = "SUCCESS|FAILED", message = "must be SUCCESS or FAILED") String result) {
}
