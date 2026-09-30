package com.example.starter.water;

import com.example.starter.water.dto.Dtos.EmergencyActivationResponse;
import com.example.starter.water.dto.Dtos.EmergencyEventResponse;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可控时钟测试：在真实 H2 内存库（MODE=MySQL）上注入固定 Clock，验证事件登记、双角色确认、
 * 核销与归还各环节写出的时间字段严格来自时钟；随后拨快时钟验证时间随之推进。
 * 不使用 Thread.sleep，也不以打印判断通过。
 */
class EmergencyClockTests {

    private static final String URL_TEMPLATE =
            "jdbc:h2:mem:emergency-clock-%s;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";

    @Test
    void allTimestampsFollowControllableClock() throws Exception {
        String url = String.format(URL_TEMPLATE, UUID.randomUUID().toString().substring(0, 8));
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        WaterRepository waterRepository = new WaterRepository(jdbc);
        EmergencyRepository emergencyRepository = new EmergencyRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);

        Instant t0 = Instant.parse("2026-11-20T08:30:15.123456789Z");
        MutableClock clock = new MutableClock(t0);
        EmergencyService service = new EmergencyService(emergencyRepository, waterRepository, txManager,
                objectMapper, clock);

        long windowId = waterRepository.insertWindow("wk-clock", "ch-clock", 1L, 2L,
                new BigDecimal("10.000"), 0L);

        EmergencyEventResponse event = service.createEvent("cmd-e", "ev-clock", "时钟事件");
        assertEquals("2026-11-20T08:30:15.123456789Z", event.createdUtc());

        EmergencyActivationResponse first = service.confirmActivation("cmd-c1", "req-clock", "ev-clock",
                windowId, "6", "DISPATCH", "jia");
        assertEquals("PENDING", first.status());
        assertEquals("2026-11-20T08:30:15.123456789Z", first.createdUtc());
        assertEquals("2026-11-20T08:30:15.123456789Z",
                first.confirmations().get(0).confirmedUtc());

        // 拨快 1 小时后第二位确认：启用时间与流水时间为新时刻，而请求创建时间保持 t0
        clock.setInstant(t0.plusSeconds(3600));
        EmergencyActivationResponse activated = service.confirmActivation("cmd-c2", "req-clock",
                "ev-clock", windowId, "6", "AUDIT", "yi");
        assertEquals("ACTIVE", activated.status());
        assertEquals("2026-11-20T09:30:15.123456789Z", activated.activatedUtc());
        assertEquals("2026-11-20T08:30:15.123456789Z", activated.createdUtc());
        assertEquals("2026-11-20T09:30:15.123456789Z",
                activated.confirmations().get(1).confirmedUtc());
        assertEquals("2026-11-20T09:30:15.123456789Z",
                service.listRequestFlows("req-clock").flows().get(0).createdUtc());

        // 再拨快 1 天核销 6：核销流水时间继续跟随时钟，储备不回补
        clock.setInstant(t0.plusSeconds(86400 + 3600));
        EmergencyActivationResponse settled = service.writeOff("cmd-w", "req-clock", "6", "jia");
        assertEquals("SETTLED", settled.status());
        assertEquals("2026-11-21T09:30:15.123456789Z",
                service.listRequestFlows("req-clock").flows().get(1).createdUtc());
        assertEquals("4", service.getReserve().remainingVolume());
        assertTrue(settled.confirmations().size() == 2);
    }

    /** 可在测试中拨点的固定时钟。 */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
