package com.example.starter.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 偏差处置确认请求体。version 必须与偏差当前版本一致（否则 409）；
 * 处置人与角色通过 X-Actor-Id / X-Approval-Role 请求头提供。
 */
public record ConfirmDeviationRequest(
        @NotBlank(message = "commandKey 不能为空") String commandKey,
        @NotNull(message = "version 不能为空") Integer version,
        String note
) {
}
