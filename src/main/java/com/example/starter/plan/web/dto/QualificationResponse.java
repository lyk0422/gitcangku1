package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 资格响应，区间左闭右开，起止均为 UTC 时刻。
 */
public record QualificationResponse(String memberCode, int version, int lineGrade,
                                    Instant validFromUtc, Instant validToUtc, String status) {
}