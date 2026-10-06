package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 列车有序轴重档案响应。
 */
public record TrainAxleProfileResponse(String trainNo, int version, List<Integer> axleWeightsKg) {
}
