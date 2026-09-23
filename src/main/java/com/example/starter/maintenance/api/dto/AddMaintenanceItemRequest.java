package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * 新增保养项目请求。itemCode 在设备内唯一且不得为 DEFAULT（DEFAULT 随设备登记迁移生成）；
 * 周期为正整数分钟；项目创建后不可修改或删除。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param expectedVersion           设备期望版本号，与当前版本不一致时返回 409
 * @param itemCode                  新增项目编码，设备内唯一；字母/数字/下划线/中划线，1-64 位
 * @param maintenancePeriodMinutes  该项目独立保养周期（分钟），正整数
 */
public record AddMaintenanceItemRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}",
                message = "只允许字母、数字、下划线、中划线，长度 1-64")
        String itemCode,
        @NotNull @Positive Long maintenancePeriodMinutes) {
}
