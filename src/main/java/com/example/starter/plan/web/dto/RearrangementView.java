package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 计划重排记录视图：固化触发操作、实际顺延分钟数、使用的限速令版本快照、
 * 原计划时刻与新计划时刻。记录追加后不可变。
 */
public record RearrangementView(
        long id,
        String scheduleKey,
        String opType,
        int shiftMinutes,
        List<RestrictionSnapshot> restrictions,
        List<OccupancyView> originalOccupancies,
        List<OccupancyView> shiftedOccupancies,
        Instant createdAt) {
}
