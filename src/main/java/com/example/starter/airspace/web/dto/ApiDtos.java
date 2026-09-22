package com.example.starter.airspace.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 禁飞区域与航线审查接口的请求/响应 DTO。坐标均为 [-100000,100000] 内的整数米。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** 单个二维点。 */
    public record PointDto(@NotNull Integer x, @NotNull Integer y) {
    }

    /** 创建禁飞区请求：非退化轴对齐闭矩形。 */
    public record ZoneCreateRequest(
            @NotNull Integer xMin,
            @NotNull Integer yMin,
            @NotNull Integer xMax,
            @NotNull Integer yMax) {
    }

    /** 创建航线请求：2~50 个按顺序连接的点，且至少两个点不同。 */
    public record RouteCreateRequest(
            @NotEmpty(message = "points 不能为空") @Valid List<PointDto> points) {
    }

    /** 替换航线点列请求：需携带期望的当前航线版本。 */
    public record RouteReplaceRequest(
            @NotNull(message = "expectedVersion 不能为空") Integer expectedVersion,
            @NotEmpty(message = "points 不能为空") @Valid List<PointDto> points) {
    }

    /** 提交审核请求：明确指定航线版本与全局空域版本。 */
    public record ReviewSubmitRequest(
            @NotBlank(message = "routeId 不能为空") String routeId,
            @NotNull(message = "routeVersion 不能为空") Integer routeVersion,
            @NotNull(message = "airspaceVersion 不能为空") Integer airspaceVersion) {
    }

    /** 禁飞区响应。 */
    public record ZoneResponse(
            String zoneId,
            String state,
            int airspaceVersion,
            Integer xMin,
            Integer yMin,
            Integer xMax,
            Integer yMax) {
    }

    /** 航线响应。 */
    public record RouteResponse(String routeId, int version, int pointCount) {
    }

    /** 不可变审核结论。 */
    public record ReviewResponse(
            String reviewId,
            String routeId,
            int routeVersion,
            int airspaceVersion,
            String conclusion,
            List<String> hitZoneIds,
            String createdAt) {
    }

    /** 当前可用结论视图：status=CURRENT 时 review 有效；STALE 时任何旧结论都不再可用。 */
    public record CurrentReviewResponse(String status, ReviewResponse review) {
    }

    /** 统一错误体。 */
    public record ErrorResponse(String error, String message) {
    }
}
