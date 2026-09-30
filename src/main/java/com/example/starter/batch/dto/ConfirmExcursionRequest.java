package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 偏差处置确认请求体；确认人与角色通过 X-Actor-Id / X-Storage-Role 请求头提供。
 * 确认必须针对偏差生成时的限值版本。
 */
public record ConfirmExcursionRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey
) {
}
