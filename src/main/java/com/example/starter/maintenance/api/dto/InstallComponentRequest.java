package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 安装部件请求：把部件装到指定设备，以该设备一条已提交读数标记安装起点。
 *
 * @param requestId        全局唯一请求标识（幂等键）
 * @param operator         操作者标识，参与幂等指纹
 * @param equipmentId      目标设备标识，须已登记
 * @param readingId        安装引用的设备累计读数标识，须已提交；安装时刻取其 UTC 采样时刻
 * @param expectedVersion  部件期望版本号，与当前版本不一致时返回 409
 */
public record InstallComponentRequest(
        @NotBlank String requestId,
        @NotBlank String operator,
        @NotBlank String equipmentId,
        @NotBlank String readingId,
        @NotNull Long expectedVersion) {
}
