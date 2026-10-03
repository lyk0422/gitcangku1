package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 限速令快照项：重排裁决时固化的限速令状态（含版本），
 * 既用于重排记录持久化，也用于重排历史与诊断视图；后续撤销或修订不改写。
 */
public record RestrictionSnapshotItem(String restrictionKey, int version, String sectionId,
                                      Instant startUtc, Instant endUtc, int maxSpeedKmh) {
}