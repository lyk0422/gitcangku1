package com.example.starter.water;

import com.example.starter.water.dto.Dtos.ChannelConfigResponse;
import com.example.starter.water.dto.Dtos.ReadingInput;
import com.example.starter.water.dto.Dtos.ReadingsResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元测试：可控时钟、规则参数解析边界、水量解析边界与 UTC 纳秒时间精度。
 * 时钟测试使用真实 H2 命名内存库与真实事务管理器，不 mock 持久层。
 */
class WaterServiceUnitTests {

    @Test
    void createdTimestampsComeFromInjectedClock() throws Exception {
        String url = "jdbc:h2:mem:clock-" + UUID.randomUUID().toString().substring(0, 8)
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        WaterService service = new WaterService(new WaterRepository(new JdbcTemplate(dataSource)),
                new DataSourceTransactionManager(dataSource), new ObjectMapper());

        Instant fixed = Instant.parse("2026-11-10T08:15:30.123456789Z");
        service.setClock(Clock.fixed(fixed, ZoneOffset.UTC));
        ChannelConfigResponse config = service.configureChannel("cmd-clock-1", "ch-clock",
                "10", "60", "2026-11-01T00:00:00Z", "alice");
        // 创建时间精确来自注入时钟（纳秒精度）
        assertEquals("2026-11-10T08:15:30.123456789Z", config.createdUtc());

        service.setClock(Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneOffset.UTC));
        ReadingsResponse readings = service.submitReadings("cmd-clock-2", "ch-clock",
                List.of(new ReadingInput("2026-11-10T10:00:00Z", "0")), "alice");
        assertEquals("2026-11-10T09:00:00Z", readings.storedReadings().get(0).createdUtc());
        // 采样时刻来自请求而非时钟
        assertEquals("2026-11-10T10:00:00Z", readings.storedReadings().get(0).readingAtUtc());
    }

    @Test
    void ruleParamParsingBoundariesReturn422() {
        assertEquals(new BigDecimal("10"), WaterService.parseRuleRate("10"));
        assertEquals(new BigDecimal("0.000001"), WaterService.parseRuleRate("0.000001"));
        assertEquals(new BigDecimal("30.500"), WaterService.parseRuleInterval("30.500"));
        assertEquals(new BigDecimal("0.001"), WaterService.parseRuleInterval("0.001"));

        for (String bad : new String[]{"0", "-1", "1.0000001", "abc", "", "  ", "1e3"}) {
            ApiException ex = assertThrows(ApiException.class, () -> WaterService.parseRuleRate(bad));
            assertEquals(422, ex.status().value(), "maxFlowRate=" + bad);
            assertEquals("INVALID_RULE_PARAM", ex.code());
        }
        for (String bad : new String[]{"0", "-0.5", "1.0001", "abc", ""}) {
            ApiException ex = assertThrows(ApiException.class, () -> WaterService.parseRuleInterval(bad));
            assertEquals(422, ex.status().value(), "maxIntervalMinutes=" + bad);
            assertEquals("INVALID_RULE_PARAM", ex.code());
        }
    }

    @Test
    void amountParsingBoundaries() {
        assertEquals(new BigDecimal("1.500"), WaterService.parseAmount("amount", "1.500"));
        assertEquals(0, WaterService.parseNonNegativeAmount("cumulativeReading", "0").signum());
        assertEquals(0, WaterService.parseNonNegativeAmount("cumulativeReading", "0.000").signum());
        assertThrows(ApiException.class, () -> WaterService.parseAmount("amount", "0"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("amount", "-1"));
        assertThrows(ApiException.class, () -> WaterService.parseAmount("amount", "1.0001"));
        assertThrows(ApiException.class, () -> WaterService.parseNonNegativeAmount("x", "-0.001"));
        assertThrows(ApiException.class, () -> WaterService.parseNonNegativeAmount("x", null));
    }

    @Test
    void utcNanosRoundTripKeepsPrecision() {
        Instant instant = Instant.parse("2026-11-10T10:30:45.987654321Z");
        long nanos = WaterService.toNanos(instant);
        assertEquals(instant, WaterService.toInstant(nanos));
        assertEquals("2026-11-10T10:30:45.987654321Z", WaterService.toIso(nanos));
        // 整秒与负时间戳（1970 前）同样稳定
        assertEquals("2026-11-10T10:30:45Z", WaterService.toIso(WaterService.toNanos(
                Instant.parse("2026-11-10T10:30:45Z"))));
        long negative = WaterService.toNanos(Instant.parse("1969-12-31T23:59:59.5Z"));
        assertEquals(Instant.parse("1969-12-31T23:59:59.5Z"), WaterService.toInstant(negative));
        // 解析非法时刻 → 400
        ApiException ex = assertThrows(ApiException.class,
                () -> WaterService.parseInstant("atUtc", "not-a-time"));
        assertEquals(400, ex.status().value());
        assertTrue(WaterService.fmt(new BigDecimal("10.500")).equals("10.5"));
    }
}
