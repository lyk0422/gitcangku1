package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记设备请求。保养周期与大幅修订见证阈值登记后均不可修改。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  保养周期（分钟），正整数
 * @param revisionThresholdMinutes  大幅读数修订见证阈值（分钟），正整数；
 *                                  修订值与当前有效值差额绝对值超出该阈值时须另一维护人见证
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        @NotBlank String equipmentId,
        @NotNull @Positive Long maintenancePeriodMinutes,
        @NotNull @Positive Long revisionThresholdMinutes) {
}
