package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 乘务资格响应：当前状态、版本与有效区间（UTC，左闭右开）。
 */
public record QualificationResponse(long qualificationId, String crewMemberId, int lineGrade,
                                    Instant validFromUtc, Instant validToUtc,
                                    int version, String status) {
}