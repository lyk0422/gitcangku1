package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 列车轴重档案响应，含当前版本与有序轴序列（单位千克）。
 */
public record TrainAxleProfileResponse(String trainNo, int version, List<Long> axleWeightsKg) {
}
