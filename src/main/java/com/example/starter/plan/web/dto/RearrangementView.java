package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * 限速重排记录视图（不可变历史证据）。固化重排时使用的限速令版本、
 * 原计划时刻与新计划时刻；字段、排序与时间精度保持稳定。
 *
 * @param restrictionRefs     重排依据的限速令版本快照
 * @param originalOccupancies 原计划时刻（计划内序号升序）
 * @param shiftedOccupancies  新计划时刻（与原计划同序）
 */
public record RearrangementView(long id, String scheduleKey, String opType, long shiftMinutes,
                                List<RestrictionRefView> restrictionRefs,
                                List<OccupancyView> originalOccupancies,
                                List<OccupancyView> shiftedOccupancies,
                                Instant createdAt) {

    /**
     * 重排依据的限速令版本引用。
     */
    public record RestrictionRefView(String restrictionKey, int version, String sectionId,
                                     int maxSpeedKmh) {
    }
}
