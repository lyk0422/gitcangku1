package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 重排明细视图：逐段的原计划时刻与新计划时刻（UTC，左闭右开）。
 */
public record RearrangementItemView(int seq, String trainNo, String sectionId,
                                    Instant origStartUtc, Instant origEndUtc,
                                    Instant newStartUtc, Instant newEndUtc) {
}
