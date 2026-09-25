package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MINOR 储运偏差质控确认请求；仅 QUALITY 角色可确认。
 */
public record ConfirmMinorExcursionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
