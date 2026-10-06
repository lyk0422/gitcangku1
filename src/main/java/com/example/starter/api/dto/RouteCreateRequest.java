package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 创建航线请求。点列按顺序连接，需 2~50 个点且至少两个点不同。
 * 携带 energyProfile 时登记合成能量档案（新类型），此时 operator 必须非空，
 * 档案缺项或条数不匹配返回 400；不携带时按原契约处理（无能量档案）。
 *
 * @param routeId       航线唯一标识
 * @param points        有序航点
 * @param energyProfile 合成能量档案；null 表示旧契约（无能量档案）
 * @param operator      操作者；登记能量档案时必须非空
 * @param requestId     写操作全局唯一请求标识，用于幂等重放
 */
public record RouteCreateRequest(
        @NotBlank @Size(max = 64) String routeId,
        @NotNull @Valid @Size(min = 2, max = 50) List<RoutePointDto> points,
        @Valid EnergyProfileDto energyProfile,
        @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {

    /** 旧契约构造器：无能量档案。 */
    public RouteCreateRequest(String routeId, List<RoutePointDto> points, String requestId) {
        this(routeId, points, null, null, requestId);
    }
}
