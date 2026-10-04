package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 安装部件请求：引用该设备已提交的累计读数，安装时刻以读数 UTC 采样时刻为准。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，计入幂等指纹
 * @param expectedVersion  部件期望版本号，与当前版本不一致时返回 409
 * @param equipmentId      目标设备标识
 * @param readingId        安装引用的设备读数标识，引用后该读数禁止修订
 */
public record InstallComponentRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotNull Long expectedVersion,
        @NotBlank String equipmentId,
        @NotBlank String readingId) {
}