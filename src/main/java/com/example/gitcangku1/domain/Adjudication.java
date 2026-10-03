package com.example.gitcangku1.domain;

import java.time.Instant;

/** 复检裁决：decision 取 ORIGINAL(采纳原测) 或 RECHECK(采纳复检)，decidedAt 为 UTC。 */
public record Adjudication(
        long id,
        long batchId,
        int rollNo,
        MeasureType decision,
        String inspector,
        Instant decidedAt) {
}