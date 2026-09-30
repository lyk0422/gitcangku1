package com.example.starter.firmware.domain;

/**
 * 目标固件版本的正式发布冻结区间，历史只增不改；恢复后原创建人、原因与时刻均保留。
 *
 * @param id             冻结记录ID
 * @param versionKey     被冻结的目标固件版本（发布单 to_version）
 * @param status         状态：FROZEN 冻结生效中；RESUMED 已审批恢复（终态）
 * @param frozenBy       冻结创建人
 * @param frozenReason   冻结原因
 * @param frozenAtUtc    冻结生效时刻，UTC，ISO-8601 格式（如 2026-09-30T07:00:00Z）
 * @param approvedBy     恢复审批人，未恢复为 null；批准人必须不同于 frozenBy
 * @param restoredAtUtc  恢复生效时刻，UTC，ISO-8601 格式；未恢复为 null
 */
public record VersionFreeze(long id, String versionKey, FreezeStatus status, String frozenBy,
                            String frozenReason, String frozenAtUtc, String approvedBy, String restoredAtUtc) {
}
