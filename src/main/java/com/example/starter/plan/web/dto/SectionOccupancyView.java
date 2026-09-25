package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 按区段查询的当前生效占用视图，携带区段等级与所属计划等级（计划等级为其全部占用区段最高等级）。
 */
public record SectionOccupancyView(String scheduleKey, String trainNo, String sectionId,
                                   int sectionLevel, int planLevel,
                                   Instant startUtc, Instant endUtc) {
}
