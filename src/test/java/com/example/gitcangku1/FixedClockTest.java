package com.example.gitcangku1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.gitcangku1.domain.MeasureType;
import com.example.gitcangku1.service.BatchService;
import com.example.gitcangku1.service.ReleaseService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 时间断言使用可控固定时钟。 */
@SpringBootTest
class FixedClockTest {

    static final Instant FIXED = Instant.parse("2026-10-03T12:30:00Z");

    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED, ZoneOffset.UTC);
        }
    }

    @Autowired
    BatchService batchService;
    @Autowired
    ReleaseService releaseService;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_errata");
        jdbc.update("DELETE FROM release_item");
        jdbc.update("DELETE FROM release_order");
        jdbc.update("DELETE FROM recheck_adjudication");
        jdbc.update("DELETE FROM color_measurement");
        jdbc.update("DELETE FROM dye_batch");
    }

    @Test
    void releaseTimestampComesFromControllableClock() {
        var batch = batchService.createBatch("B-" + UUID.randomUUID(), 50, 0, 0, 1);
        assertEquals(FIXED, batch.createdAt());
        batchService.recordMeasurement(batch.id(), "m-1", 1, MeasureType.ORIGINAL,
                51, 1, 1, Instant.parse("2026-10-01T08:00:00Z"));
        var view = releaseService.release(batch.id(), "rel-1", null);
        assertEquals(FIXED, view.releasedAt());
        var errata = releaseService.appendErrata(batch.id(), "固定时钟勘误");
        assertEquals(FIXED, errata.createdAt());
    }
}