package com.example.starter.calibration.api;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

/**
 * 测量环境超出补偿系数适用区间：不得计算补偿，返回 422 并回传适用区间（含端点）。
 */
public class EnvOutOfRangeException extends ApiException {

    private final String instrumentModel;
    private final BigDecimal tempMin;
    private final BigDecimal tempMax;
    private final BigDecimal humidityMin;
    private final BigDecimal humidityMax;

    public EnvOutOfRangeException(String instrumentModel,
                               BigDecimal tempMin, BigDecimal tempMax,
                               BigDecimal humidityMin, BigDecimal humidityMax) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "ENV_OUT_OF_COMPENSATION_RANGE",
                "测量环境超出补偿系数适用区间，不得计算补偿: model=" + instrumentModel);
        this.instrumentModel = instrumentModel;
        this.tempMin = tempMin;
        this.tempMax = tempMax;
        this.humidityMin = humidityMin;
        this.humidityMax = humidityMax;
    }

    public String getInstrumentModel() {
        return instrumentModel;
    }

    public BigDecimal getTempMin() {
        return tempMin;
    }

    public BigDecimal getTempMax() {
        return tempMax;
    }

    public BigDecimal getHumidityMin() {
        return humidityMin;
    }

    public BigDecimal getHumidityMax() {
        return humidityMax;
    }
}
