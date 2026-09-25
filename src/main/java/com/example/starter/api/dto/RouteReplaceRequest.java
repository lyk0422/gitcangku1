package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 *
 * <p>cruiseAltitude/startTime/endTime 为新巡航高度（米）与 UTC 时段
 * （epoch 毫秒，左闭右开）；缺省（全部 null）时保留原航线的高度时段属性。</p>
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 * @param cruiseAltitude  新巡航高度，单位米；null 保留原属性
 * @param startTime       新 UTC 时段起始，epoch 毫秒（含）；null 保留原属性
 * @param endTime         新 UTC 时段结束，epoch 毫秒（不含）；null 保留原属性
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @NotBlank @Size(max = 64) String requestId,
        Integer cruiseAltitude,
        Long startTime,
        Long endTime) {

    /** 兼容历史调用：仅替换点列，保留原巡航高度与时段。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion,
                               List<RoutePointDto> points, String requestId) {
        this(routeId, expectedVersion, points, requestId, null, null, null);
    }
}
