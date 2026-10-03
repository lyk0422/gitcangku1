package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划受限速影响的诊断视图（只读，不改变任何状态）。
 * requiredShiftMinutes 为按线路最小间隔整体顺延所需的分钟数（0 表示无需顺延）；
 * alreadyStarted 为计划是否已开始运行（已开始则不可重排）。
 */
public record RestrictionImpactView(String scheduleKey, int requiredShiftMinutes,
                                    boolean alreadyStarted,
                                    List<RestrictionImpactSegment> segments) {
}