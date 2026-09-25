package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 不良事件报告请求体。
 *
 * @param eventKey    事件编号，同一分配内唯一，用于紧急揭盲时关联严重报告
 * @param severity    严重度 MILD / MODERATE / SEVERE（非法值由服务层按 400 拒绝）
 * @param description 事件描述，必填
 */
public record AdverseEventRequest(
        @NotBlank(message = "eventKey 不能为空")
        @Size(max = 64, message = "eventKey 最长 64 字符")
        String eventKey,

        @NotBlank(message = "severity 不能为空")
        String severity,

        @NotBlank(message = "description 不能为空")
        @Size(max = 1000, message = "description 最长 1000 字符")
        String description
) {
}
