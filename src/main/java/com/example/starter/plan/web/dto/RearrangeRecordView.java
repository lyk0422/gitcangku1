package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 计划重排记录视图：固化限速令版本快照、原计划时刻与新计划时刻。
 * operator 为 null 表示调用方未提供操作者；createdAt 为 UTC 毫秒时间戳。
 */
public record RearrangeRecordView(long id, String scheduleKey, LocalDate opDate,
                                  int shiftMinutes,
                                  List<RestrictionSnapshotItem> restrictions,
                                  List<OccupancyView> originalOccupancies,
                                  List<OccupancyView> newOccupancies,
                                  String operator, long createdAt) {
}