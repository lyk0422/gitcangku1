package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 揭盲申请请求体。
 *
 * @param reason 揭盲原因，必填
 */
public record UnblindApplyRequest(
        @NotBlank(message = "reason 不能为空")
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason
) {
}
