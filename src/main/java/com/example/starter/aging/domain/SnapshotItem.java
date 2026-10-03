package com.example.starter.aging.domain;

import java.time.LocalDate;

/**
 * 放行快照项：固化放行时七天各自的翻面与有效温度记录编号，
 * 之后补录、作废或替代均不改变快照。
 */
public record SnapshotItem(long id, long releaseId, LocalDate dayDate,
                           long turnId, long temperatureId) {
}