package com.example.starter.plan;

import java.time.Instant;

/**
 * 区段占用视图。
 *
 * @param trainNo 列车编号
 * @param sectionId 区段 ID
 * @param startUtc 占用开始时刻（UTC，左闭）
 * @param endUtc 占用结束时刻（UTC，右开）
 */
public record OccupancyView(String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
}
