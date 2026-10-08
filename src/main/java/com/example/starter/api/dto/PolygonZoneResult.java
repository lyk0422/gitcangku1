package com.example.starter.api.dto;

import java.util.List;

/**
 * 带孔洞多边形禁飞区创建结果。环顶点为创建时冻结的内容。
 *
 * @param zoneId          禁飞区标识
 * @param zoneType        固定为 POLYGON
 * @param status          操作后状态：ACTIVE
 * @param outerRing       冻结的外环顶点（首尾不重复）
 * @param holes           冻结的孔洞环顶点列表
 * @param airspaceVersion 创建后生效的全局空域版本
 */
public record PolygonZoneResult(String zoneId, String zoneType, String status,
                                List<RoutePointDto> outerRing,
                                List<PolygonRingDto> holes,
                                long airspaceVersion) {
}
