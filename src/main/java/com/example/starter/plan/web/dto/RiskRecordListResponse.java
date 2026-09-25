package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划乘务风险记录查询响应。
 */
public record RiskRecordListResponse(String scheduleKey, boolean riskBlocked,
                                     List<RiskRecordView> records) {
}
