package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 拒绝揭盲申请请求体；拒绝原因必填非空。
 *
 * @param reason 拒绝原因，必填，最长 500 字符
 */
public record RejectUnblindRequest(
        @NotBlank(message = "reason 不能为空")
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason
) {
}
