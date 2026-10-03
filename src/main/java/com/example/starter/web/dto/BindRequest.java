package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 任务绑定请求：幂等请求编号 + 任务 + 校准记录。
 */
public record BindRequest(
        @NotBlank String requestId,
        @NotNull Long taskId,
        @NotNull Long calibrationId) {
}