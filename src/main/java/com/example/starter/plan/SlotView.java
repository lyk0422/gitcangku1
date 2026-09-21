package com.example.starter.plan;

import java.time.Instant;

/**
 * 已发布时隙视图：某运营日某区段上当前生效的一条占用。
 *
 * @param scheduleKey 来源计划业务键
 * @param trainNo 列车编号
 * @param sectionId 区段 ID
 * @param startUtc 占用开始时刻（UTC，左闭）
 * @param endUtc 占用结束时刻（UTC，右开）
 */
public record SlotView(String scheduleKey, String trainNo, String sectionId, Instant startUtc, Instant endUtc) {
}
