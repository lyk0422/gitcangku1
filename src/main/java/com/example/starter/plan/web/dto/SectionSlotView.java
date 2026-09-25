package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 区段上一条当前生效（已发布）占用时隙。
 */
public record SectionSlotView(String scheduleKey, String trainNo, LocalDate opDate,
                              Instant startUtc, Instant endUtc) {
}
