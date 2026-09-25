package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 紧急揭盲请求体。
 *
 * @param eventKey 触发 URGENT_REVIEW 的 SEVERE 不良事件报告业务键，必填
 * @param reason   紧急揭盲理由，必填，最长 500 字符
 */
public record EmergencyUnblindRequest(
        @NotBlank(message = "eventKey 不能为空")
        @Size(max = 64, message = "eventKey 最长 64 字符")
        String eventKey,

        @NotBlank(message = "reason 不能为空")
        @Size(max = 500, message = "reason 最长 500 字符")
        String reason
) {
}
