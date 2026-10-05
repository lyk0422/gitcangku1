package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 安装部件请求：引用目标设备已提交的读数，以其 UTC 采样时刻与累计工时为区间起点。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，参与幂等身份判定
 * @param expectedVersion  部件期望版本号，与当前版本不一致时返回 409
 * @param equipmentId      目标设备标识
 * @param readingId        安装引用的读数标识（该设备已提交读数）
 */
public record InstallComponentRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Long expectedVersion,
        @NotBlank String equipmentId,
        @NotBlank String readingId) {
}
