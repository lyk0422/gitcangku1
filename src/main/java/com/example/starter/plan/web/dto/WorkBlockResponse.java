package com.example.starter.plan.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 封锁详情视图：封锁窗口本身（UTC 左闭右开）与登记时受影响计划的处理结果。
 */
public record WorkBlockResponse(String blockKey, String sectionId, LocalDate opDate,
                                Instant startUtc, Instant endUtc, String status,
                                List<AffectedPlanView> affectedPlans) {
}
