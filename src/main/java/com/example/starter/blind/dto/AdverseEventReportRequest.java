package com.example.starter.blind.dto;

import com.example.starter.blind.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 不良事件报告请求体。
 *
 * @param eventKey    报告业务键，实验内唯一，必填
 * @param severity    严重度：MILD / MODERATE / SEVERE，必填
 * @param description 事件描述，必填，最长 1000 字符
 */
public record AdverseEventReportRequest(
        @NotBlank(message = "eventKey 不能为空")
        @Size(max = 64, message = "eventKey 最长 64 字符")
        String eventKey,

        @NotNull(message = "severity 不能为空")
        Severity severity,

        @NotBlank(message = "description 不能为空")
        @Size(max = 1000, message = "description 最长 1000 字符")
        String description
) {
}
