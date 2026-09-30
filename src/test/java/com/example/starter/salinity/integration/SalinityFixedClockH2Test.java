package com.example.starter.salinity.integration;

import com.example.starter.salinity.dto.AdoptRequest;
import com.example.starter.salinity.dto.SealResult;
import com.example.starter.salinity.dto.SealedProfileView;
import com.example.starter.salinity.dto.SubmitSampleRequest;
import com.example.starter.salinity.exception.UnprocessableException;
import com.example.starter.salinity.service.SalinityService;
import com.example.starter.salinity.support.BusinessClock;
import com.example.starter.salinity.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可控时钟下的封存时刻断言：所有入库/封存时间来自固定时钟，不依赖真实睡眠。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
class SalinityFixedClockH2Test {

    private static final Instant FIXED = Instant.parse("2026-09-30T12:34:56Z");

    /**
     * 以固定时钟覆盖生产环境的系统时钟，便于确定性断言时间字段。
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        BusinessClock fixedClock() {
            return new MutableClock(FIXED);
        }
    }

    @Autowired
    private SalinityService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SubmitSampleRequest sample(String voyage, String station, int depth, String no) {
        return new SubmitSampleRequest(voyage, station, depth, new BigDecimal("35.000"),
                FIXED, no, "REQ-" + no);
    }

    @Test
    @DisplayName("封存时刻与剖面条目时刻均取自固定时钟，且样本创建时刻可控")
    void sealedAtUsesControllableClock() {
        String voyage = "CLK-" + UUID.randomUUID().toString().substring(0, 8);
        for (int depth : new int[]{0, 50, 100}) {
            String no = "CLK-" + voyage + "-" + depth;
            service.submitSample(sample(voyage, "S", depth, no));
            service.adopt(new AdoptRequest(voyage, no, "ADOPT-" + no));
        }

        SealResult result = service.seal(voyage);

        assertThat(result.sealedAtUtc()).isEqualTo(FIXED);
        assertThat(result.sealedProfile()).extracting(SealedProfileView::sealedAtUtc)
                .containsOnly(FIXED);
        java.sql.Timestamp dbSealedAt = jdbcTemplate.queryForObject(
                "SELECT sealed_at_utc FROM voyages WHERE code = ?", java.sql.Timestamp.class, voyage);
        assertThat(dbSealedAt.toInstant()).isEqualTo(FIXED);
    }

    @Test
    @DisplayName("多站位时任一站位缺深度则整体 422，不固化任何站位，航次仍 OPEN")
    void partialStationsCannotSeal() {
        String voyage = "PART-" + UUID.randomUUID().toString().substring(0, 8);
        // 站位 A 三深度齐全并采纳。
        for (int depth : new int[]{0, 50, 100}) {
            String no = "P-A-" + voyage + "-" + depth;
            service.submitSample(sample(voyage, "A", depth, no));
            service.adopt(new AdoptRequest(voyage, no, "ADOPT-" + no));
        }
        // 站位 B 只采了 0 米且采纳，缺 50/100。
        String b0 = "P-B-" + voyage + "-0";
        service.submitSample(sample(voyage, "B", 0, b0));
        service.adopt(new AdoptRequest(voyage, b0, "ADOPT-" + b0));

        assertThatThrownBy(() -> service.seal(voyage))
                .isInstanceOf(UnprocessableException.class)
                .hasMessageContaining("站位 B");

        Integer sealedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sealed_profiles WHERE voyage_code = ?", Integer.class, voyage);
        assertThat(sealedRows).as("不能部分封存：连齐全的站位 A 也不得固化").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM voyages WHERE code = ?", String.class, voyage)).isEqualTo("OPEN");
    }
}
