package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 紧急揭盲请求体；仅 REVIEWER 可提交，且分配须处于 URGENT_REVIEW。
 *
 * @param eventKey 触发紧急揭盲的 SEVERE 不良事件编号
 * @param reason   紧急揭盲理由，必填
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
