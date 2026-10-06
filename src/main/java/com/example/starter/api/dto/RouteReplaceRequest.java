package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换航线点列请求。expectedVersion 为客户端期望的当前航线版本，
 * 版本不匹配返回 409；成功后航线版本加一并使当前审核失效。
 * 携带 energyProfile 时为新生成的版本登记合成能量档案（替换旧版本档案），
 * 此时 operator 必须非空；不携带时新版本无能量档案，按原契约审查。
 *
 * @param routeId         航线唯一标识
 * @param expectedVersion 期望的当前航线版本（版本从 1 开始）
 * @param points          新的有序航点
 * @param energyProfile   新版本的合成能量档案；null 表示新版本无能量档案
 * @param operator        操作者；登记能量档案时必须非空
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record RouteReplaceRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull Integer expectedVersion,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid EnergyProfileDto energyProfile,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {

    /** 旧契约构造器：无能量档案。 */
    public RouteReplaceRequest(String routeId, Integer expectedVersion,
                               List<RoutePointDto> points, String requestId) {
        this(routeId, expectedVersion, points, null, null, requestId);
    }
}
