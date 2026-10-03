package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审核请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 * 路径经过气象区域时必须提交预测气象版本，缺失返回 422。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param forecastVersion 预测气象版本；路径不经过任何气象区域时可为 null
 * @param operator        操作者标识，参与幂等指纹计算
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        Long forecastVersion,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容无气象语义的旧调用：不提交预测版本与操作者。 */
    public ReviewRequest(String routeId, Integer routeVersion, Long airspaceVersion, String requestId) {
        this(routeId, routeVersion, airspaceVersion, null, null, requestId);
    }
}