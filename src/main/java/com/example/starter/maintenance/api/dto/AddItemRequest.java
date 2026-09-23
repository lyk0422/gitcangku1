package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 新增保养项目请求。项目创建后周期不可修改、项目不可删除；设备内（含 DEFAULT）最多 21 个项目。
 *
 * @param requestId                 全局唯一请求标识（幂等键）
 * @param expectedVersion           设备期望版本号，与当前版本不一致时返回 409
 * @param itemCode                  项目编码，设备内唯一；不允许使用保留编码 DEFAULT
 * @param maintenancePeriodMinutes  该项目独立保养周期（分钟），正整数
 */
public record AddItemRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "(?i)(?!DEFAULT$)[A-Za-z0-9_-]+",
                message = "只能包含字母、数字、下划线、连字符，且不能使用保留编码 DEFAULT")
        String itemCode,
        @NotNull @Positive Long maintenancePeriodMinutes) {
}
