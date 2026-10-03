package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 计划乘务资格核查响应：计划状态、行程窗口要求的最高线路等级，
 * 以及按乘务员 ID 稳定升序的逐人覆盖结果。
 */
public record CrewCheckResponse(String scheduleKey, String planStatus, int requiredLineGrade,
                                List<CrewCheckItem> members) {
}