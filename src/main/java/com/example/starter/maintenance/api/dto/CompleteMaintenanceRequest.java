package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 完成保养（双人完工确认）请求：确认人须为非证据提交人的另一维护角色，
 * 且确认的证据包版本必须仍是当前周期最新版本、模板版本未再修订，否则 409。
 * 证据包未齐、存在 FAIL 或设备当前 OVERDUE 时返回 422 并列明缺失或失败项。
 *
 * @param requestId               全局唯一请求标识（幂等键）
 * @param expectedVersion         设备期望版本号，与当前版本不一致时返回 409
 * @param readingId               锚点读数标识，须已存在
 * @param anchorRevisionNo        锚点读数的当前修订号，与当前修订号不一致时返回 409
 * @param expectedPackageVersion  确认人审阅的证据包版本，须等于当前周期最新证据包版本
 * @param confirmedBy             完工确认人（另一维护角色），不得与证据提交人相同
 */
public record CompleteMaintenanceRequest(
        @NotBlank String requestId,
        @NotNull Long expectedVersion,
        @NotBlank String readingId,
        @NotNull @Positive Integer anchorRevisionNo,
        @NotNull @Positive Integer expectedPackageVersion,
        @NotBlank String confirmedBy) {
}
