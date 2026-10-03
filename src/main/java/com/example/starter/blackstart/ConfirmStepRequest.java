package com.example.starter.blackstart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 值班员步骤确认请求。
 * requestId 为幂等键：同键同参重放返回首个结果，同键异参返回 409。
 * 读数单位：voltageV 伏特、frequencyHz 赫兹、powerKw 千瓦（仅第三步必填）。
 */
public record ConfirmStepRequest(
        @NotBlank String requestId,
        @NotBlank String deviceStatus,
        @NotNull Double voltageV,
        @NotNull Double frequencyHz,
        Double powerKw) {
}