package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 重排逐段明细视图：固化原计划时刻、新计划时刻、受影响分钟数与起决定作用的限速令版本。
 */
public record RearrangementSegmentView(int seq, String trainNo, String sectionId,
                                       Instant oldStartUtc, Instant oldEndUtc,
                                       Instant newStartUtc, Instant newEndUtc,
                                       long affectedMinutes, String restrictionKey,
                                       int restrictionVersion, int maxSpeedKmh) {
}
