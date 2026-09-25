package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量版本血缘记录。提交产生版本 1，替换标准器重算追加新版本；记录只增不改。
 * 每条记录固化该版本引用的证书版本、补偿系数、不确定度及其版本、计算值与 referenceKey 指纹。
 *
 * @param id                  测量版本记录 ID（自增）
 * @param measurementId       所属测量记录 ID
 * @param versionNo           版本号，从 1 开始
 * @param certificateId       该版本引用的证书 ID
 * @param standardId          该版本引用的标准器 ID（快照）
 * @param certificateVersion  该版本引用的证书版本（快照）
 * @param a                   补偿系数 a 快照
 * @param b                   补偿偏移 b 快照
 * @param uncertainty         证书标准不确定度快照，非负，单位与读数一致
 * @param uncertaintyVersion  不确定度版本快照
 * @param computedValue       该版本未舍入计算值 a×读数+b
 * @param expandedUncertainty 该版本扩展不确定度（覆盖因子 k=2）：2×证书标准不确定度
 * @param passed              该版本合格判定（未舍入值，含端点）
 * @param referenceKey        referenceKey 指纹：测量版本+证书版本+测量时刻+输入摘要
 * @param createdAt           该版本生成时间（UTC）
 */
public record MeasurementVersion(
        long id,
        long measurementId,
        int versionNo,
        long certificateId,
        String standardId,
        String certificateVersion,
        BigDecimal a,
        BigDecimal b,
        BigDecimal uncertainty,
        String uncertaintyVersion,
        BigDecimal computedValue,
        BigDecimal expandedUncertainty,
        boolean passed,
        String referenceKey,
        Instant createdAt) {
}
