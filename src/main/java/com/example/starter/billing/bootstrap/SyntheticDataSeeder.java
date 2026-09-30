package com.example.starter.billing.bootstrap;

import java.time.Instant;

/**
 * 合成种子邀约常量；实际数据由 {@code classpath:data.sql} 随启动幂等写入。
 */
public final class SyntheticDataSeeder {

    /** 合成邀约 ID。 */
    public static final String SEED_OFFER_ID = "offer-20261001-1800";

    /** 时段起点（UTC，左闭）。 */
    public static final Instant WINDOW_START = Instant.parse("2026-10-01T18:00:00Z");

    /** 时段终点（UTC，右开）。 */
    public static final Instant WINDOW_END = Instant.parse("2026-10-01T19:00:00Z");

    private SyntheticDataSeeder() {
    }
}
