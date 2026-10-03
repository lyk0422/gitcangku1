package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置区域气象最低标准请求：整体替换该区域的标准时段集合。
 * 先校验完整最终状态（无负值、无重叠、窗口合法）再事务提交；失败不留半成品。
 *
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param operator  操作者标识，计入幂等指纹
 * @param zoneId    目标区域（须为已存在的区域）
 * @param windows   替换后的完整标准时段集合（可为空，表示清除该区域标准）
 */
public record WeatherMinimaConfigRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Valid @Size(max = 100) List<WeatherWindowDto> windows) {
}