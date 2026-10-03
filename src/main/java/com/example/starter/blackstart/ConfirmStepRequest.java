package com.example.starter.blackstart;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 步骤确认请求。值班员提交设备读数，服务端记录 UTC 时刻。
 *
 * @param requestId 客户端请求标识，同一计划内唯一；同参重放返回原结果，异参返回 409
 * @param voltage 母线电压读数（V），允许范围 380~420
 * @param frequency 频率读数（Hz），允许范围 49.5~50.5
 * @param powerKw 负载接入功率读数（kW），仅第三步要求，不得超过 200
 * @param deviceStatus 设备状态，仅 NORMAL 可接受
 */
public record ConfirmStepRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotNull BigDecimal voltage,
        @NotNull BigDecimal frequency,
        BigDecimal powerKw,
        @NotBlank @Size(max = 32) String deviceStatus) {
}