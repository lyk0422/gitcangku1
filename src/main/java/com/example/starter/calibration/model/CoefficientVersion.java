package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 环境补偿系数版本。版本只增不改；生效区间为 UTC 左闭右开 [effectiveFrom, effectiveTo)，
 * effectiveTo 为 null 表示当前仍开放。同一仪器任意时刻最多只有一个生效版本。
 *
 * @param id               系数版本 ID（自增）
 * @param instrumentId     仪器 ID
 * @param effectiveFrom    生效起点（UTC，含该时刻）
 * @param effectiveTo      生效终点（UTC，不含该时刻）；null 表示开放至今
 * @param refTemperature   参考温度（℃），温差＝放行温度−参考温度
 * @param refHumidity      参考湿度（%RH），湿度差＝放行湿度−参考湿度
 * @param temperatureCoeff 温度系数（补偿量/℃）
 * @param humidityCoeff    湿度系数（补偿量/%RH）
 * @param createdAt        版本创建时间（UTC）
 */
public record CoefficientVersion(
        long id,
        String instrumentId,
        Instant effectiveFrom,
        Instant effectiveTo,
        BigDecimal refTemperature,
        BigDecimal refHumidity,
        BigDecimal temperatureCoeff,
        BigDecimal humidityCoeff,
        Instant createdAt) {
}
