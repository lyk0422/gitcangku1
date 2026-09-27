package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 重排记录中固化的限速令版本快照；后续撤销或修订限速令不影响该快照。
 */
public record RestrictionSnapshot(
        long id,
        int version,
        String sectionId,
        Instant startUtc,
        Instant endUtc,
        int maxSpeedKmh) {
}
