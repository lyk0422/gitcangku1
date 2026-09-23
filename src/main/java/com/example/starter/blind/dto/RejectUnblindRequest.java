package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 揭盲申请拒绝请求体。
 *
 * @param rejectReason 拒绝原因，必填非空，最长 500 字符
 */
public record RejectUnblindRequest(
        @NotBlank(message = "rejectReason 不能为空")
        @Size(max = 500, message = "rejectReason 最长 500 字符")
        String rejectReason
) {
}
