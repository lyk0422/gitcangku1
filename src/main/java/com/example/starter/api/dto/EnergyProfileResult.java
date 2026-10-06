package com.example.starter.api.dto;

/**
 * 能量档案登记结果。
 *
 * @param routeId      航线标识
 * @param routeVersion 档案绑定的航线版本
 * @param segments     登记的耗能段数
 */
public record EnergyProfileResult(String routeId, int routeVersion, int segments) {
}
