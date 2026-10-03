package com.example.starter.calibration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 绑定请求；requestId 为幂等键，同参重放返回原绑定，异参返回 409。
 *
 * @param requestId 幂等键
 * @param taskId 任务主键
 * @param calibrationId 校准编号
 */
public record CreateBindingRequest(
        @NotBlank String requestId,
        @NotNull Long taskId,
        @NotNull Long calibrationId) {
}