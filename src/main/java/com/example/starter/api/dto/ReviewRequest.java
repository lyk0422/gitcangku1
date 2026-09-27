package com.example.starter.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 提交审核请求。明确指定航线版本与空域版本，任一不是当前版本返回 409。
 * 航线经过配置了气象最低标准的区域时必须提交预测气象版本，缺失返回 422。
 *
 * @param routeId         航线唯一标识
 * @param routeVersion    明确的航线版本
 * @param airspaceVersion 明确的空域版本
 * @param forecastVersion 预测气象版本；涉及气象标准区域时必填，否则可为 null
 * @param operator        操作者标识，参与幂等指纹；可为 null（兼容无操作者调用）
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer routeVersion,
        @NotNull Long airspaceVersion,
        Long forecastVersion,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {

    /** 兼容构造：不涉及预测与操作者的审核请求。 */
    public ReviewRequest(String routeId, Integer routeVersion, Long airspaceVersion, String requestId) {
        this(routeId, routeVersion, airspaceVersion, null, null, requestId);
    }
}
