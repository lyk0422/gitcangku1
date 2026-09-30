package com.example.starter.salinity.view;

import java.time.Instant;

/**
 * 勘误视图。
 *
 * @param note          更正说明正文
 * @param refSealedUtc  所引用的原封存 UTC 时刻
 * @param createdUtc    勘误追加 UTC 时刻
 */
public record ErratumView(
        String note,
        Instant refSealedUtc,
        Instant createdUtc
) {
}
