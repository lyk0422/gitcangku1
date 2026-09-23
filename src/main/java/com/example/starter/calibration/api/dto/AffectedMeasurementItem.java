package com.example.starter.calibration.api.dto;

import java.util.List;

/**
 * 闭包内受影响测量结果项：直接或间接使用了闭包内标准器版本、且测量时刻不早于失效起始时刻。
 *
 * @param measurementId    测量记录 ID
 * @param measurementKey   测量业务键
 * @param status           快照时状态：PENDING / RELEASED / BLOCKED / REVIEW_REQUIRED
 * @param boundVersionId   提交时绑定的标准器版本 ID
 * @param impactPath       到失效根的最短血缘路径上的版本 ID（depth 0=直接绑定版本，依次到失效根）
 * @param releaseSnapshot  原放行快照（放行人与放行时间）；未放行时为 null，冻结后保留不变
 */
public record AffectedMeasurementItem(
        long measurementId,
        String measurementKey,
        String status,
        long boundVersionId,
        List<Long> impactPath,
        ReleaseRecordResponse releaseSnapshot) {
}
