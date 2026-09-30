package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 批量放行请求。每批 1～50 条测量键，整批原子生效；
 * 全批共用同一次环境读数（温度 ℃、湿度 %RH），按放行时刻命中的唯一生效系数版本补偿。
 * requestId 为幂等键：同一 requestId 重放返回首次计算快照，环境读数或测量键被修改返回 409。
 *
 * @param requestId   放行请求 ID（幂等键）
 * @param keys        测量键列表
 * @param temperature 放行环境温度（℃），最多 6 位小数
 * @param humidity    放行环境湿度（%RH），最多 6 位小数
 */
public record ReleaseRequest(String requestId, List<String> keys, String temperature, String humidity) {
}
