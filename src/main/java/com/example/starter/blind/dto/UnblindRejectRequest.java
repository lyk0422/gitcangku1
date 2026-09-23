package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 揭盲拒绝请求体；另一名 REVIEWER 必须填写非空拒绝原因。
 *
 * @param reason 拒绝原因，必填，非空
 */
public record UnblindRejectRequest(
        @NotBlank(message = "拒绝原因不能为空")
        @Size(max = 500, message = "拒绝原因最长 500 字符")
        String reason
) {
}
