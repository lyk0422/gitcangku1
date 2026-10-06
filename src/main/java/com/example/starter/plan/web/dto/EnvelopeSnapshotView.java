package com.example.starter.plan.web.dto;

import java.math.BigDecimal;

/**
 * 制动包络判定快照视图：发布/改签成功时冻结的速度、档案参数与精确判定依据，
 * 后续档案修改不改写历史。通过条件为 leftValue（2*a*v*t + v*v）不大于 rightValue（2*a*d）。
 */
public record EnvelopeSnapshotView(String trainNo, String sectionId, BigDecimal speedV,
                                   BigDecimal reactionTimeT, BigDecimal baseDecelA,
                                   BigDecimal protectDistanceD, BigDecimal leftValue,
                                   BigDecimal rightValue) {
}
