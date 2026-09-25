package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划乘务资质缺口诊断响应：gaps 为空表示两角色资质完整。
 */
public record CrewGapDiagnosisResponse(String scheduleKey, List<CrewGapView> gaps) {
}
