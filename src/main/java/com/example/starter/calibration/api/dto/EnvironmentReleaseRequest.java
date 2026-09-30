package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 环境补偿放行请求。整批 1～50 条原子生效：每条携带测量键与放行时刻的温湿度读数。
 *
 * @param requestId 放行请求 ID（幂等键）：重放首次计算快照；读数或放行人变化返回 409
 * @param items     放行条目（测量键＋温度℃＋湿度%RH），十进制字符串最多 6 位小数
 */
public record EnvironmentReleaseRequest(String requestId, List<Item> items) {

    /**
     * 单条环境补偿放行测项。
     *
     * @param measurementKey 测量键
     * @param temperature    环境温度读数（℃）
     * @param humidity       环境湿度读数（%RH）
     */
    public record Item(String measurementKey, String temperature, String humidity) {
    }
}
