package com.example.starter.observation;

import java.time.Instant;

/**
 * 持续越限区间：左闭右开，[startUtc, endUtc)。
 *
 * @param startUtc 区间首个越限分钟（UTC）
 * @param endUtc   区间右端：最后一个越限分钟加 1 分钟（UTC，不含）
 */
public record ExcursionInterval(
        Instant startUtc,
        Instant endUtc) {
}
