package com.example.starter.plan.model;

import java.time.Instant;

/**
 * 危险品隔离配置版本。生效区间左闭右开 [effectiveFrom, effectiveTo)，均为 UTC。
 *
 * @param id             主键
 * @param versionNo      配置版本号，全表唯一
 * @param effectiveFrom  生效开始时刻（含），UTC
 * @param effectiveTo    生效结束时刻（不含），UTC；null 表示无限期
 * @param fingerprint    配置内容规范化指纹（SHA-256）
 * @param createdAt      创建时刻，UTC 毫秒
 */
public record HazardConfigVersion(long id, int versionNo, Instant effectiveFrom,
                                  Instant effectiveTo, String fingerprint, long createdAt) {
}
