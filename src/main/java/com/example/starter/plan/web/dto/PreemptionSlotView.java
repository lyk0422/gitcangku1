package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 抢占涉及的时隙视图，区间左闭右开，起止为 UTC 时刻。
 */
public record PreemptionSlotView(String sectionId, int sectionLevel,
                                 Instant startUtc, Instant endUtc) {
}
