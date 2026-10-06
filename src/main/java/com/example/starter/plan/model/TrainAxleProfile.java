package com.example.starter.plan.model;

import java.util.List;

/**
 * 列车有序轴重档案。
 *
 * @param trainNo       列车编号，全局唯一
 * @param version       档案版本，从 1 起，每次成功更新加一
 * @param axleWeightsKg 有序轴重列表（单位千克，正整数），2 至 16 轴，顺序即轴序
 */
public record TrainAxleProfile(String trainNo, int version, List<Integer> axleWeightsKg) {
}
