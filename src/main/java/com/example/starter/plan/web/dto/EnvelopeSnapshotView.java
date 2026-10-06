package com.example.starter.plan.web.dto;

import java.math.BigDecimal;

/**
 * 应急制动包络判定快照视图：发布/改签成功时冻结的速度、参数与精确判定依据，
 * 后续档案修改不改写历史。lhsScaled &lt;= rhsScaled 表示判定不越限。
 */
public record EnvelopeSnapshotView(String scheduleKey, int occupancySeq, String trainNo,
                                   String sectionId, BigDecimal speed, BigDecimal reactionTime,
                                   BigDecimal baseDeceleration, BigDecimal protectionDistance,
                                   int trainProfileVersion, int sectionProfileVersion,
                                   BigDecimal lhsScaled, BigDecimal rhsScaled) {
}
