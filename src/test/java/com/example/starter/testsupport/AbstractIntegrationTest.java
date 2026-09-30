package com.example.starter.testsupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestConstructor;

/**
 * H2 集成测试基类：真实 Spring 上下文 + H2 内存库（MODE=MySQL），
 * 每个用例后清理场景数据并重置自增，保证用例相互隔离。
 */
@SpringBootTest(classes = com.example.starter.StarterApplication.class)
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.setInstant(TestClockConfiguration.BASE_TIME);
    }

    @AfterEach
    void cleanScenarioData() {
        jdbcTemplate.execute("DELETE FROM succession_event");
        jdbcTemplate.execute("DELETE FROM idempotent_call");
        jdbcTemplate.execute("DELETE FROM observation_request");
        jdbcTemplate.execute("DELETE FROM instrument_lock");
        jdbcTemplate.execute("ALTER TABLE observation_request ALTER COLUMN id RESTART WITH 1");
        jdbcTemplate.execute("ALTER TABLE succession_event ALTER COLUMN id RESTART WITH 1");
    }
}
