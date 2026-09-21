package com.example.starter.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 状态变更请求，targetStatus 只允许为当前状态的下一合法状态。
 */
public record ChangeStatusRequest(
        @NotBlank @Size(max = 128) String commandKey,
        @NotNull IncidentStatus targetStatus) {
}
