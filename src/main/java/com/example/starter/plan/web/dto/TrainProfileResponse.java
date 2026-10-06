package com.example.starter.plan.web.dto;

import java.math.BigDecimal;

/**
 * 列车应急制动档案响应。
 */
public record TrainProfileResponse(String trainNo, BigDecimal reactionTime,
                                   BigDecimal baseDeceleration, int version) {
}
