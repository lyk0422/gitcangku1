package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量版本历史。每次提交或替换标准器重算生成一个版本；旧版本保留用于血缘追溯，
 * 重算失败时旧版本仍是当前有效版本。
 *
 * @param id                 测量版本 ID（自增）
 * @param measurementId      测量记录 ID
 * @param version            版本号，从 1 开始递增
 * @param certificateId      该版本引用的校准证书 ID
 * @param certVersion        该版本引用的证书版本快照
 * @param compensationCoeff  该版本补偿系数快照
 * @param uncertaintyVersion 该版本不确定度版本快照
 * @param computedValue      该版本未舍入计算值 a×读数+b
 * @param uncertainty        该版本不确定度 |补偿系数×读数|
 * @param passed             该版本是否合格（基于未舍入值，含端点）
 * @param createdAt          版本生成时间（UTC）
 */
public record MeasurementVersion(
        long id,
        long measurementId,
        int version,
        long certificateId,
        String certVersion,
        BigDecimal compensationCoeff,
        String uncertaintyVersion,
        BigDecimal computedValue,
        BigDecimal uncertainty,
        boolean passed,
        Instant createdAt) {
}
