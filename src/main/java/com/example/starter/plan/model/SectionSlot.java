package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 指定区段上一条已发布占用（含运营日），用于单线配置变更时按运营日分组验证已发布集合。
 *
 * @param scheduleKey 所属计划业务键
 * @param trainNo     列车编号
 * @param opDate      运营日期（Asia/Shanghai 日历日）
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 * @param direction   行车方向标识；为 null 表示旧无方向占用，须先取消，不能猜方向
 */
public record SectionSlot(String scheduleKey, String trainNo, LocalDate opDate,
                          Instant startUtc, Instant endUtc, String direction) {
}
