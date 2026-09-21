package com.example.starter.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 事件上报请求。
 */
public record ReportIncidentRequest(
        @NotBlank @Size(max = 128) String incidentKey,
        @NotNull Severity severity,
        @NotBlank @Size(max = 512) String summary,
        @NotBlank @Size(max = 128) String reporter) {
}
