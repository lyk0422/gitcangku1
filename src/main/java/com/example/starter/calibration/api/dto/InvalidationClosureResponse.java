package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 失效影响闭包视图：预览、创建重放与激活结果共用，均为稳定排序的完整闭包。
 *
 * @param invalidationKey 失效单业务键；预览时尚未创建为 null
 * @param status          失效单状态：PENDING / ACTIVATED；预览时为 null
 * @param impactVersion   激活后生成的唯一影响版本号；未激活为 null
 * @param rootVersionId   失效根版本 ID
 * @param effectiveFrom   失效起始时刻（UTC，含）
 * @param domainVersion   本次闭包对应的领域版本号
 * @param standards       闭包内全部子孙标准器版本（含根，稳定排序）
 * @param measurements    直接或间接使用闭包版本的校准记录/已放行结果（按测量 ID 升序）
 * @param confirmers      已确认质量人员；预览时为空
 * @param reason          失效原因；预览时为 null
 * @param createdAt       失效单创建时间（UTC）；预览时为 null
 * @param activatedAt     激活时间（UTC）；未激活为 null
 */
public record InvalidationClosureResponse(
        String invalidationKey,
        String status,
        String impactVersion,
        long rootVersionId,
        Instant effectiveFrom,
        long domainVersion,
        List<AffectedStandardItem> standards,
        List<AffectedMeasurementItem> measurements,
        List<String> confirmers,
        String reason,
        Instant createdAt,
        Instant activatedAt) {
}
