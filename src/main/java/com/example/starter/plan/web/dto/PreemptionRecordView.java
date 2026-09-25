package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 不可变抢占记录视图：固化抢占双方计划、涉及区段与各自等级。
 */
public record PreemptionRecordView(long id, LocalDate opDate, String preemptingScheduleKey,
                                   String preemptedScheduleKey, int preemptingLevel,
                                   int preemptedLevel, String preemptKey,
                                   List<PreemptionSectionView> sections, long createdAt) {
}
