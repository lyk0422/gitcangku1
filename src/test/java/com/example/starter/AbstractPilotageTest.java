package com.example.starter;

import com.example.starter.repo.AssignmentRepository;
import com.example.starter.repo.PilotRepository;
import com.example.starter.service.PilotageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 引航测试基类：独立命名 H2 内存库（MODE=MySQL），每个用例前清理业务数据并重置时钟。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pilotage_test;MODE=MySQL;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
abstract class AbstractPilotageTest {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper om;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PilotageService service;

    @Autowired
    protected PilotRepository pilotRepo;

    @Autowired
    protected AssignmentRepository assignmentRepo;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void cleanBusinessData() {
        jdbc.update("DELETE FROM assignment");
        jdbc.update("DELETE FROM segment");
        clock.setInstant(Instant.parse("2026-10-04T08:00:00Z"));
    }

    protected long pilotId(String code) {
        return pilotRepo.findByPilotCode(code).orElseThrow().id();
    }

    protected long newSegment(String vessel, int grade, String startUtc, String endUtc, String point) {
        return service.createSegment(vessel, grade, Instant.parse(startUtc), Instant.parse(endUtc), point).id();
    }
}