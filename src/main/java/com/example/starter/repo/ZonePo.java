package com.example.starter.repo;

import com.example.starter.domain.Point;
import com.example.starter.domain.Polygon;

import java.util.List;

/**
 * 禁飞区持久化记录。
 *
 * <p>{@code zoneType=RECTANGLE} 时边界坐标非空、{@code polygon} 为 null；
 * {@code zoneType=POLYGON} 时边界坐标为 null、{@code polygon} 携带冻结的外环与孔洞。</p>
 *
 * @param zoneId         禁飞区唯一标识
 * @param zoneType       区域类型：RECTANGLE / POLYGON
 * @param xMin           矩形左边界（含），米；仅矩形区域有值
 * @param yMin           矩形下边界（含），米；仅矩形区域有值
 * @param xMax           矩形右边界（含），米；仅矩形区域有值
 * @param yMax           矩形上边界（含），米；仅矩形区域有值
 * @param polygon        带孔洞多边形（冻结环顶点）；仅多边形区域有值
 * @param status         ACTIVE / REVOKED
 * @param createdVersion 创建后生效的全局空域版本
 * @param revokedVersion 撤销后生效的全局空域版本；null 表示仍有效
 */
public record ZonePo(String zoneId, String zoneType,
                     Integer xMin, Integer yMin, Integer xMax, Integer yMax,
                     Polygon polygon,
                     String status, long createdVersion, Long revokedVersion) {

    /** 矩形区域记录。 */
    public static ZonePo rectangle(String zoneId, int xMin, int yMin, int xMax, int yMax,
                                   String status, long createdVersion, Long revokedVersion) {
        return new ZonePo(zoneId, "RECTANGLE", xMin, yMin, xMax, yMax, null,
                status, createdVersion, revokedVersion);
    }

    /** 带孔洞多边形区域记录。 */
    public static ZonePo polygon(String zoneId, Polygon polygon,
                                 String status, long createdVersion, Long revokedVersion) {
        return new ZonePo(zoneId, "POLYGON", null, null, null, null, polygon,
                status, createdVersion, revokedVersion);
    }

    /** 是否为带孔洞多边形区域。 */
    public boolean isPolygon() {
        return "POLYGON".equals(zoneType);
    }

    /** 外环顶点（多边形区域）；矩形区域返回空列表。 */
    public List<Point> outerRing() {
        return polygon == null ? List.of() : polygon.outerRing();
    }
}
