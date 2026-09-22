package com.example.starter.airspace.domain;

import com.example.starter.airspace.geom.Geometry.Point;

import java.time.Instant;
import java.util.List;

/**
 * 领域行类型（不可变视图）。
 */
public final class DomainRows {

    private DomainRows() {
    }

    /** 禁飞区行。revokedVersion 为 null 表示仍有效。 */
    public record ZoneRow(
            String zoneId,
            int xMin,
            int yMin,
            int xMax,
            int yMax,
            boolean active,
            int createdVersion,
            Integer revokedVersion,
            Instant createdAt) {
    }

    /** 航线当前版本行。 */
    public record RouteRow(String routeId, int version, Instant updatedAt) {
    }

    /** 某航线某版本的点列快照。 */
    public record RouteSnapshot(String routeId, int version, List<Point> points) {
    }

    /** 不可变审核结果行。 */
    public record ReviewRow(
            String reviewId,
            String routeId,
            int routeVersion,
            int airspaceVersion,
            String conclusion,
            List<String> hitZoneIds,
            Instant createdAt) {
    }

    /** 幂等请求记录行。 */
    public record RequestRecordRow(
            String requestId,
            String operation,
            String fingerprint,
            int statusCode,
            String responseBody,
            Instant createdAt) {
    }
}
