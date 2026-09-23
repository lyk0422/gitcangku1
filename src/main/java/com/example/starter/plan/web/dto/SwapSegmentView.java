package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 交换证据中的区段时段视图，起止为 UTC 时刻，区间左闭右开。
 */
public record SwapSegmentView(String sectionId, Instant startUtc, Instant endUtc) {
}
