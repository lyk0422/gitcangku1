package com.example.starter.plan.model;

import java.util.List;

/**
 * 列车有序轴重档案。
 *
 * @param trainNo       列车编号，全局唯一
 * @param version       档案版本，从 1 起，每次整体替换轴序列成功加一
 * @param axleWeightsKg 有序轴重清单（2 至 16 轴），每轴为正整数，单位千克；轴序换位视为改参
 */
public record TrainAxleProfile(String trainNo, int version, List<Long> axleWeightsKg) {
}
