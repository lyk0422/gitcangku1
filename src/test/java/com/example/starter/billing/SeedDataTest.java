package com.example.starter.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.starter.billing.bootstrap.SyntheticDataSeeder;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 校验 data.sql 合成种子邀约已随上下文启动自动写入 H2（MODE=MySQL），UTC 瞬时与种子常量一致。
 */
@SpringBootTest
class SeedDataTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("种子数据：18:00-19:00（UTC左闭右开）削峰邀约存在")
    void seedOfferExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM demand_offer WHERE id = ?",
                Integer.class, SyntheticDataSeeder.SEED_OFFER_ID);
        assertEquals(1, count);
        assertEquals(SyntheticDataSeeder.WINDOW_START,
                jdbcTemplate.queryForObject(
                        "SELECT start_time FROM demand_offer WHERE id = ?",
                        java.sql.Timestamp.class, SyntheticDataSeeder.SEED_OFFER_ID).toInstant());
        assertEquals(SyntheticDataSeeder.WINDOW_END,
                jdbcTemplate.queryForObject(
                        "SELECT end_time FROM demand_offer WHERE id = ?",
                        java.sql.Timestamp.class, SyntheticDataSeeder.SEED_OFFER_ID).toInstant());
        assertEquals(Instant.parse("2026-10-01T18:00:00Z"), SyntheticDataSeeder.WINDOW_START);
    }
}
