package com.example.gitcangku1.domain;

import java.time.Instant;

/** 整批放行单，每批至多一张；releasedAt 为 UTC。 */
public record ReleaseOrder(
        long id,
        long batchId,
        int targetVersion,
        String requestId,
        String requestHash,
        Instant releasedAt) {
}