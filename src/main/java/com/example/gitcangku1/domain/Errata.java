package com.example.gitcangku1.domain;

import java.time.Instant;

/** 放行后勘误，只允许追加；createdAt 为 UTC。 */
public record Errata(
        long id,
        long releaseId,
        String note,
        Instant createdAt) {
}