package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 区段维护封锁窗口，区间左闭右开 [startUtc, endUtc)，与计划占用按相同规则比较，
 * 端点相邻（封锁结束 = 占用开始，或占用结束 = 封锁开始）不算冲突。
 *
 * @param id        主键
 * @param blockKey  封锁业务键，全局唯一
 * @param sectionId 被封锁区段 ID
 * @param opDate    封锁窗口所在运营日（Asia/Shanghai 日历日）
 * @param startUtc  封锁开始时刻（含），UTC
 * @param endUtc    封锁结束时刻（不含），UTC，必须晚于 startUtc
 * @param status    封锁状态
 */
public record WorkBlock(long id, String blockKey, String sectionId, LocalDate opDate,
                        Instant startUtc, Instant endUtc, WorkBlockStatus status) {
}
