package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审核请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 * 提交预测气象版本时，路径经过区域按固化的最低标准做气象检查：
 * 预测缺失返回 422，任一预测值低于标准结论为 WEATHER_BLOCKED。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param forecastVersion 明确的预测气象版本；null 表示不做气象检查
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        Long forecastVersion,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容不携带预测气象版本的审核请求（不做气象检查）。 */
    public ReviewRequest(String routeId, Integer routeVersion, Long airspaceVersion, String requestId) {
        this(routeId, routeVersion, airspaceVersion, null, requestId);
    }
}
