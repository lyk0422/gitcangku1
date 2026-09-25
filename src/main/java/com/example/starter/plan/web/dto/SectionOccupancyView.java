package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 按区段查询的当前等级占用视图：该区段上当前已发布生效的时隙及等级信息。
 *
 * @param scheduleKey  占用计划业务键
 * @param trainNo      列车编号
 * @param sectionId    区段 ID
 * @param startUtc     占用开始（含），UTC
 * @param endUtc       占用结束（不含），UTC
 * @param planLevel    占用计划发布时继承的等级
 * @param sectionLevel 区段当前登记等级
 */
public record SectionOccupancyView(String scheduleKey, String trainNo, String sectionId,
                                   Instant startUtc, Instant endUtc,
                                   Integer planLevel, int sectionLevel) {
}
