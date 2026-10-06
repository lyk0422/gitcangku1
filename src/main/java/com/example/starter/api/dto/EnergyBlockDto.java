package com.example.starter.api.dto;

/**
 * 能量不足航点的可观察结果：点号与缺口。
 *
 * @param pointIndex      航点序号，从 0 开始
 * @param deficit         缺口：该点总需求 - 出发可用能量（正数）
 * @param required        该点总需求：累计耗能 + 备降耗能 + 必留余量
 * @param availableEnergy 出发可用能量
 */
public record EnergyBlockDto(int pointIndex, long deficit, long required, long availableEnergy) {
}
