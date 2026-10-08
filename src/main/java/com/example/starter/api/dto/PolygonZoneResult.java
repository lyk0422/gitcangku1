package com.example.starter.api.dto;

import java.util.List;

/**
 * 带孔洞多边形禁飞区创建结果。环顶点为创建时冻结的不可变内容。
 *
 * @param zoneId          禁飞区标识
 * @param status          操作后状态：ACTIVE / REVOKED
 * @param kind            区域类型，固定为 POLYGON；矩形区域为 RECTANGLE
 * @param airspaceVersion 操作后生效的全局空域版本
 * @param outer           外环有序顶点（冻结快照）
 * @param holes           孔洞环有序顶点列表（冻结快照）
 */
public record PolygonZoneResult(String zoneId, String status, String kind,
                                long airspaceVersion,
                                List<RoutePointDto> outer,
                                List<List<RoutePointDto>> holes) {
}
