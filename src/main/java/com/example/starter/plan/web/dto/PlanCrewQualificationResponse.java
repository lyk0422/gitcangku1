package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划乘务资质总览响应。
 */
public record PlanCrewQualificationResponse(
        String scheduleKey,
        String status,
        boolean riskBlocked,
        List<PlanCrewRoleView> roles) {
}
