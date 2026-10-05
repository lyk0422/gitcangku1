package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 登记设备请求。物理保养周期登记后不可修改；等效寿命周期可选，启用后按负荷加权等效分钟判定。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param operator                  操作者标识（可选，纳入幂等身份）
 * @param equipmentId               设备唯一标识
 * @param maintenancePeriodMinutes  物理保养周期（分钟），正整数
 * @param equivalentPeriodMinutes   等效寿命周期（等效分钟），正整数；缺省表示不启用等效寿命口径
 */
public record RegisterEquipmentRequest(
        @NotBlank String requestId,
        String operator,
        @NotBlank String equipmentId,
        @NotNull @Positive Long maintenancePeriodMinutes,
        @Positive Long equivalentPeriodMinutes) {
}
