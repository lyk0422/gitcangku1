package com.example.starter.calibration.api;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

/**
 * 测量环境超出补偿系数适用区间：422，携带适用的温湿度区间供调用方校正。
 */
public class EnvironmentOutOfRangeException extends ApiException {

    private final Range range;

    public EnvironmentOutOfRangeException(String instrumentModel, long profileId, Range range) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "ENVIRONMENT_OUT_OF_RANGE",
                "测量环境超出仪器型号 " + instrumentModel + " 补偿系数版本 " + profileId + " 的适用区间");
        this.range = range;
    }

    public Range getRange() {
        return range;
    }

    /**
     * 适用区间（含端点）。
     *
     * @param tempMin     温度下限（摄氏度）
     * @param tempMax     温度上限（摄氏度）
     * @param humidityMin 湿度下限（%RH）
     * @param humidityMax 湿度上限（%RH）
     */
    public record Range(
            String tempMin,
            String tempMax,
            String humidityMin,
            String humidityMax) {

        public static Range of(BigDecimal tempMin, BigDecimal tempMax,
                               BigDecimal humidityMin, BigDecimal humidityMax) {
            return new Range(
                    tempMin.stripTrailingZeros().toPlainString(),
                    tempMax.stripTrailingZeros().toPlainString(),
                    humidityMin.stripTrailingZeros().toPlainString(),
                    humidityMax.stripTrailingZeros().toPlainString());
        }
    }
}
