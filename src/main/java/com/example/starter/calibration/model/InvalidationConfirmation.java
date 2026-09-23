package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 失效单确认记录。同一失效单同一确认人仅可确认一次；激活需两名不同质量人员。
 *
 * @param id              确认记录 ID（自增）
 * @param invalidationKey 失效单业务键
 * @param confirmedBy     确认人（质量人员）
 * @param confirmedAt     确认时间（UTC）
 */
public record InvalidationConfirmation(
        long id,
        String invalidationKey,
        String confirmedBy,
        Instant confirmedAt) {
}
