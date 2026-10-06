package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 某区段上一条已发布生效占用（携带运营日），用于配置变更时校验当前已发布集合。
 *
 * @param opDate      运营日期（Asia/Shanghai 日历日）
 * @param scheduleKey 所属计划业务键
 * @param trainNo     列车编号
 * @param direction   行车方向；null 表示未指定（旧无方向占用）
 * @param startUtc    占用开始（含），UTC
 * @param endUtc      占用结束（不含），UTC
 */
public record DatedSlot(LocalDate opDate, String scheduleKey, String trainNo, Direction direction,
                        Instant startUtc, Instant endUtc) {
}
