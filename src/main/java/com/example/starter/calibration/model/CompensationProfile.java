package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 环境补偿系数版本。按仪器型号配置公开的线性补偿系数与温湿度适用区间；
 * 版本只增不改，更新即追加并激活新版本，旧测量固化其提交时的版本快照。
 *
 * <p>线性补偿：compensated = 原始读数 + tempCoeff×温度 + humidityCoeff×湿度，
 * 结果按 HALF_UP 保留 6 位小数。
 *
 * @param id           补偿系数版本 ID（自增）
 * @param instrumentModel 仪器型号
 * @param versionNo    型号内版本号，从 1 起递增
 * @param tempCoeff    温度线性补偿系数（补偿量/摄氏度）
 * @param humidityCoeff 湿度线性补偿系数（补偿量/%RH）
 * @param tempMin      适用温度下限（摄氏度，含端点）
 * @param tempMax      适用温度上限（摄氏度，含端点）
 * @param humidityMin  适用湿度下限（%RH，含端点）
 * @param humidityMax  适用湿度上限（%RH，含端点）
 * @param active       是否为该型号当前生效版本
 * @param createdAt    版本创建时间（UTC）
 */
public record CompensationProfile(
        long id,
        String instrumentModel,
        int versionNo,
        BigDecimal tempCoeff,
        BigDecimal humidityCoeff,
        BigDecimal tempMin,
        BigDecimal tempMax,
        BigDecimal humidityMin,
        BigDecimal humidityMax,
        boolean active,
        Instant createdAt) {
}
