package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 带孔洞多边形禁飞区持久化记录（环顶点为创建时冻结的不可变快照）。
 *
 * @param zoneId         多边形禁飞区唯一标识
 * @param status         ACTIVE / REVOKED
 * @param createdVersion 创建后生效的全局空域版本
 * @param revokedVersion 撤销后生效的全局空域版本；null 表示仍有效
 * @param outer          外环有序顶点（3~10 个，首尾不重复）
 * @param holes          孔洞环列表（0~2 个，每个 3~8 顶点，按请求顺序）
 */
public record PolygonZonePo(String zoneId, String status, long createdVersion,
                            Long revokedVersion, List<Point> outer, List<List<Point>> holes) {
}
