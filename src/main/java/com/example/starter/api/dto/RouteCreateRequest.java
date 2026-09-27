package com.example.starter.api.dto;

import com.example.starter.domain.RouteType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 *
 * @param routeId   航线唯一标识
 * @param points    有序航点
 * @param routeType 航线类型：REGULAR 常规（默认）/ EMERGENCY 应急；缺省按 REGULAR 处理
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Pattern(regexp = "REGULAR|EMERGENCY", message = "routeType 只能是 REGULAR 或 EMERGENCY")
        String routeType,
        @NotBlank @Size(max = 64) String requestId) {

    /** 规范化：缺省类型按 REGULAR 处理。 */
    public RouteCreateRequest {
        if (routeType == null || routeType.isBlank()) {
            routeType = RouteType.REGULAR.name();
        }
    }

    /** 兼容旧调用：不指定类型时按 REGULAR 处理。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, RouteType.REGULAR.name(), requestId);
    }
}
