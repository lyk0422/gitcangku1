package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 修订历史项响应。
 *
 * @param id              修订记录 ID
 * @param fromVersion     修订前版本号
 * @param toVersion       修订后版本号
 * @param oldMeasuredAt   修订前测量时刻（UTC）
 * @param newMeasuredAt   修订后测量时刻（UTC）
 * @param oldReading      修订前原始读数
 * @param newReading      修订后原始读数
 * @param oldLowerLimit   修订前下限
 * @param newLowerLimit   修订后下限
 * @param oldUpperLimit   修订前上限
 * @param newUpperLimit   修订后上限
 * @param oldCertificateId 修订前证书 ID
 * @param newCertificateId 修订后证书 ID
 * @param oldComputedValue 修订前计算值
 * @param newComputedValue 修订后计算值
 * @param oldPassed       修订前是否合格
 * @param newPassed       修订后是否合格
 * @param revisedBy       修订操作者
 * @param revisedAt       修订时间（UTC）
 */
public record RevisionResponse(
        long id,
        long fromVersion,
        long toVersion,
        Instant oldMeasuredAt,
        Instant newMeasuredAt,
        String oldReading,
        String newReading,
        String oldLowerLimit,
        String newLowerLimit,
        String oldUpperLimit,
        String newUpperLimit,
        long oldCertificateId,
        long newCertificateId,
        String oldComputedValue,
        String newComputedValue,
        boolean oldPassed,
        boolean newPassed,
        String revisedBy,
        Instant revisedAt) {
}
