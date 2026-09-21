package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 已发布时隙视图，用于按日期与区段查询当前生效占用。
 */
public record PublishedSlotView(String scheduleKey, String trainNo, String sectionId,
                                Instant startUtc, Instant endUtc) {
}
