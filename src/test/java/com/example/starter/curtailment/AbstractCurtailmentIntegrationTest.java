package com.example.starter.curtailment;

import com.example.starter.curtailment.api.ApiDtos;
import com.example.starter.curtailment.service.CurtailmentService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 集成测试基类：真实 H2（MODE=MySQL）+ 可控时钟；每个用例前清空场景数据并复位时钟。
 */
@SpringBootTest
@Import(TestClockConfiguration.class)
public abstract class AbstractCurtailmentIntegrationTest {

    @Autowired
    protected CurtailmentService service;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetScenario() {
        jdbc.update("DELETE FROM reconciliation_adjustment");
        jdbc.update("DELETE FROM reconciliation_tick");
        jdbc.update("DELETE FROM reconciliation");
        jdbc.update("DELETE FROM power_sample");
        jdbc.update("DELETE FROM idempotency_record");
        jdbc.update("DELETE FROM curtailment_instruction");
        jdbc.update("DELETE FROM unit_lock");
        clock.setInstant(TestClockConfiguration.BASE_TIME);
    }

    /** 创建一小时（4 刻度）窗口的指令，起点为当前时间 1 小时后。 */
    protected ApiDtos.InstructionView newInstruction(String unitId, String maxPowerKw) {
        Instant start = clock.instant().plusSeconds(3600);
        return service.createInstruction(new ApiDtos.CreateInstructionRequest(
                unitId, start, start.plusSeconds(3600), new BigDecimal(maxPowerKw), null));
    }

    /** 创建并确认指令。 */
    protected ApiDtos.InstructionView newConfirmedInstruction(String unitId, String maxPowerKw) {
        ApiDtos.InstructionView instruction = newInstruction(unitId, maxPowerKw);
        return service.confirm(instruction.id(), new ApiDtos.ConfirmRequest("confirm-" + UUID.randomUUID()));
    }

    /** 上报指定刻度采样。 */
    protected ApiDtos.SampleView report(long instructionId, Instant tickStart, String avgPowerKw) {
        return service.reportSample(instructionId, new ApiDtos.SampleRequest(
                "sample-" + UUID.randomUUID(), tickStart, new BigDecimal(avgPowerKw)));
    }

    /** 上报窗口内 4 个刻度的相同功率采样。 */
    protected void reportFour(long instructionId, Instant windowStart, String avgPowerKw) {
        for (int i = 0; i < 4; i++) {
            report(instructionId, windowStart.plusSeconds(900L * i), avgPowerKw);
        }
    }

    /** 统计表行数。 */
    protected long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }
}