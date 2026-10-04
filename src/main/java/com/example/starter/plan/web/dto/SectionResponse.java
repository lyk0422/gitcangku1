package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 区段视图，起讫站用于接续站点连续性校验。
 */
public record SectionResponse(String sectionId, String fromStation, String toStation,
                              Instant createdAt) {
}