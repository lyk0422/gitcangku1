package com.example.starter.calibration.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.example.starter.calibration.model.Certificate;
import com.example.starter.calibration.model.CoefficientVersion;

/**
 * 环境补偿计算（纯领域逻辑）。
 * 补偿值＝(放行温度−参考温度)×温度系数＋(放行湿度−参考湿度)×湿度系数；
 * 最终值＝(计算值＋补偿值) 按 HALF_UP 保留 4 位小数。
 * 全部使用 BigDecimal 精确运算，不在中间步骤舍入。
 */
public final class EnvironmentCompensation {

    /** 最终结果小数位：4 位。 */
    public static final int FINAL_SCALE = 4;

    private EnvironmentCompensation() {
    }

    /**
     * 未舍入补偿值：温差×温度系数＋湿度差×湿度系数。
     */
    public static BigDecimal compensation(BigDecimal temperature, BigDecimal humidity,
                                          CoefficientVersion version) {
        BigDecimal temperatureDelta = temperature.subtract(version.refTemperature());
        BigDecimal humidityDelta = humidity.subtract(version.refHumidity());
        return temperatureDelta.multiply(version.temperatureCoeff())
                .add(humidityDelta.multiply(version.humidityCoeff()));
    }

    /**
     * 最终结果：计算值＋补偿值后 HALF_UP 保留 4 位小数。
     */
    public static BigDecimal finalValue(BigDecimal computedValue, BigDecimal compensation) {
        return computedValue.add(compensation).setScale(FINAL_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 温湿度是否落在证书允许范围内，四个端点均包含（温度/湿度恰好等于上限视为范围内）。
     * 证书未定义完整环境范围时恒为 false。
     */
    public static boolean withinCertificateRange(Certificate certificate,
                                                 BigDecimal temperature, BigDecimal humidity) {
        if (!certificate.hasEnvironmentRange()) {
            return false;
        }
        return temperature.compareTo(certificate.tempMin()) >= 0
                && temperature.compareTo(certificate.tempMax()) <= 0
                && humidity.compareTo(certificate.humidityMin()) >= 0
                && humidity.compareTo(certificate.humidityMax()) <= 0;
    }
}
