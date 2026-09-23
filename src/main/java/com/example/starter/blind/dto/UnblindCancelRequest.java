package com.example.starter.blind.dto;

import jakarta.validation.constraints.Size;

/**
 * 撤销揭盲申请请求体；撤销原因为可选项。
 *
 * @param reason 撤销原因，可空，最长 500 字符
 */
public record UnblindCancelRequest(
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason
) {
}
