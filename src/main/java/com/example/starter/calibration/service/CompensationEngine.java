package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.example.starter.calibration.model.CompensationCoefficient;
import com.example.starter.calibration.model.EnvRecord;

/**
 * 环境补偿计算（纯函数，便于数值精度单测）：
 * C = k0 + kTemperature×温度(℃) + kHumidity×湿度(%RH)，结果四舍五入到 6 位小数。
 * 补偿后测量值 = 基础计算值 a×读数+b（未舍入） + 补偿值（6 位小数）。
 */
public final class CompensationEngine {

    private CompensationEngine() {
    }

    /**
     * 按系数版本与记录环境计算补偿值，HALF_UP 保留 6 位小数。
     */
    public static BigDecimal compensation(CompensationCoefficient c, EnvRecord env) {
        return c.k0()
                .add(c.kTemperature().multiply(env.temperatureC()))
                .add(c.kHumidity().multiply(env.humidityPct()))
                .setScale(6, RoundingMode.HALF_UP);
    }

    /**
     * 补偿后测量值：基础值加 6 位补偿值（精确，不再舍入）。
     */
    public static BigDecimal compensated(BigDecimal baseComputed, BigDecimal compensation) {
        return baseComputed.add(compensation);
    }
}
