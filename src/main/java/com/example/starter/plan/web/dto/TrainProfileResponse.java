package com.example.starter.plan.web.dto;

import java.math.BigDecimal;

/**
 * 列车制动档案响应，含当前配置版本。
 */
public record TrainProfileResponse(String trainNo, BigDecimal reactionTimeT, BigDecimal baseDecelA,
                                   int version) {
}
