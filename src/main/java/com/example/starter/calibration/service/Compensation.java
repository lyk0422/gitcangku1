package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.example.starter.calibration.model.CompensationProfile;

/**
 * 环境补偿计算（纯函数）。
 *
 * <p>线性补偿：compensated = 原始读数 + tempCoeff×温度 + humidityCoeff×湿度，
 * 结果按 HALF_UP 保留 6 位小数。温湿度适用区间判定含端点。
 */
final class Compensation {

    /** 补偿值保留小数位。 */
    static final int SCALE = 6;

    private Compensation() {
    }

    /**
     * 温湿度是否落在系数版本适用区间内（含端点）。
     */
    static boolean withinRange(CompensationProfile profile, BigDecimal temperature, BigDecimal humidity) {
        return temperature.compareTo(profile.tempMin()) >= 0
                && temperature.compareTo(profile.tempMax()) <= 0
                && humidity.compareTo(profile.humidityMin()) >= 0
                && humidity.compareTo(profile.humidityMax()) <= 0;
    }

    /**
     * 按系数与记录环境计算补偿后测量值，HALF_UP 保留 6 位小数。
     */
    static BigDecimal compensate(BigDecimal rawReading, CompensationProfile profile,
                                 BigDecimal temperature, BigDecimal humidity) {
        BigDecimal delta = profile.tempCoeff().multiply(temperature)
                .add(profile.humidityCoeff().multiply(humidity));
        return rawReading.add(delta).setScale(SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 补偿后值是否落在合格区间（含端点）。
     */
    static boolean withinSpec(BigDecimal compensated, BigDecimal lowerLimit, BigDecimal upperLimit) {
        return compensated.compareTo(lowerLimit) >= 0 && compensated.compareTo(upperLimit) <= 0;
    }
}
