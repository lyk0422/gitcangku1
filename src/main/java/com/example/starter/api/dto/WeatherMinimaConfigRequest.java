package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配置/修订区域气象最低标准请求。整组替换该区域的标准时段并推进气象标准版本；
 * 标准负值或时段重叠返回 422。
 *
 * @param zoneId    区域唯一标识（须已存在）
 * @param windows   标准时段集合（1~10 个，UTC 一日内分钟，左闭右开，不得重叠）
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record WeatherMinimaConfigRequest(
        @NotBlank @Size(max = 64) String zoneId,
        @NotNull @Valid @Size(min = 1, max = 10) List<WeatherWindowDto> windows,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
