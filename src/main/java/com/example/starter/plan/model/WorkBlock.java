package com.example.starter.plan.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 区段维护封锁窗口，UTC 左闭右开 [startUtc, endUtc)，相邻端点不冲突。
 *
 * @param id        主键
 * @param blockKey  封锁业务键，全局唯一
 * @param opDate    运营日期（Asia/Shanghai 日历日），窗口必须完全落在该日内
 * @param sectionId 被封锁区段 ID
 * @param startUtc  封锁开始时刻（含），UTC
 * @param endUtc    封锁结束时刻（不含），UTC，必须晚于 startUtc
 * @param status    封锁状态
 * @param createdAt 创建时刻，UTC 毫秒
 * @param updatedAt 最近变更时刻，UTC 毫秒
 */
public record WorkBlock(long id, String blockKey, LocalDate opDate, String sectionId,
                        Instant startUtc, Instant endUtc, WorkBlockStatus status,
                        long createdAt, long updatedAt) {
}
