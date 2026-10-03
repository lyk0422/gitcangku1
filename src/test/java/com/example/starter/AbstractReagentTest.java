package com.example.starter;

import com.example.starter.service.ReagentService;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** 测试基类：独立命名 H2 内存库，逐用例清理场景数据并重置时钟。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class AbstractReagentTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ReagentService service;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM dispense_record");
        jdbc.update("DELETE FROM bottle_event");
        jdbc.update("DELETE FROM reagent_bottle");
        clock.set(TestClockConfig.T0);
    }

    protected long receiveBottle(String lot, String capacityMl, Instant supplierExpiryUtc) {
        return service.receive(lot, new BigDecimal(capacityMl), supplierExpiryUtc).id();
    }
}