package com.example.starter.calibration.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 测量修订历史。记录修订前后的完整字段与判定；旧证据只增不回写。
 *
 * @param id              修订记录 ID（自增）
 * @param measurementId   所属测量记录 ID
 * @param fromVersion     修订前测量版本号
 * @param toVersion       修订后测量版本号
 * @param oldMeasuredAt   修订前测量时刻（UTC）
 * @param newMeasuredAt   修订后测量时刻（UTC）
 * @param oldRawReading   修订前原始读数
 * @param newRawReading   修订后原始读数
 * @param oldLowerLimit   修订前合格下限（含端点）
 * @param newLowerLimit   修订后合格下限（含端点）
 * @param oldUpperLimit   修订前合格上限（含端点）
 * @param newUpperLimit   修订后合格上限（含端点）
 * @param oldCertificateId 修订前匹配的证书 ID
 * @param newCertificateId 修订后匹配的证书 ID
 * @param oldComputedValue 修订前未舍入计算值
 * @param newComputedValue 修订后未舍入计算值
 * @param oldPassed       修订前是否合格
 * @param newPassed       修订后是否合格
 * @param revisedBy       修订操作者（X-Actor-Id）
 * @param revisedAt       修订时间（UTC）
 */
public record MeasurementRevision(
        long id,
        long measurementId,
        long fromVersion,
        long toVersion,
        Instant oldMeasuredAt,
        Instant newMeasuredAt,
        BigDecimal oldRawReading,
        BigDecimal newRawReading,
        BigDecimal oldLowerLimit,
        BigDecimal newLowerLimit,
        BigDecimal oldUpperLimit,
        BigDecimal newUpperLimit,
        long oldCertificateId,
        long newCertificateId,
        BigDecimal oldComputedValue,
        BigDecimal newComputedValue,
        boolean oldPassed,
        boolean newPassed,
        String revisedBy,
        Instant revisedAt) {
}
