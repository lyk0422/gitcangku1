package com.example.starter.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 合成三点场景端到端测试：两点确认签发被拒，补齐第三点可签发，第 4 分钟有人进入执行被拒。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestClockConfiguration.class)
class PlanApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM execution_report");
        jdbc.update("DELETE FROM blast_command");
        jdbc.update("DELETE FROM checkpoint_confirmation");
        jdbc.update("DELETE FROM clearance_round");
        jdbc.update("DELETE FROM person_entry_event");
        jdbc.update("DELETE FROM plan_checkpoint");
        jdbc.update("DELETE FROM blast_plan");
        clock.setInstant(TestClockConfiguration.T0);
    }

    private long createPlan() throws Exception {
        MvcResult result = mvc.perform(post("/api/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PLAN-API\",\"areaBoundary\":\"采石场A区：半径300米\","
                                + "\"checkpoints\":[\"CP-NORTH\",\"CP-EAST\",\"CP-GATE\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.occupied").value(false))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.get("planId").asLong();
    }

    private void confirm(long planId, String checkpoint, String confirmer) throws Exception {
        mvc.perform(post("/api/plans/{planId}/checkpoints/{code}/confirmation", planId, checkpoint)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmer\":\"" + confirmer + "\",\"headcount\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointCode").value(checkpoint))
                .andExpect(jsonPath("$.headcount").value(0));
    }

    private JsonNode issue(long planId, String requestId, int expectedStatus) throws Exception {
        MvcResult result = mvc.perform(post("/api/plans/{planId}/commands", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"issuedBy\":\"班长张三\"}"))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void syntheticScenarioWithEntryAtMinute4() throws Exception {
        long planId = createPlan();
        confirm(planId, "CP-NORTH", "安全员李四");
        confirm(planId, "CP-EAST", "安全员王五");
        issue(planId, "CMD-1", 409);
        confirm(planId, "CP-GATE", "安全员赵六");
        JsonNode command = issue(planId, "CMD-1", 201);
        long commandId = command.get("commandId").asLong();
        assertEquals("ISSUED", command.get("status").asText());
        Instant issuedAt = Instant.parse(command.get("issuedAt").asText());
        Instant expiresAt = Instant.parse(command.get("expiresAt").asText());
        assertEquals(Duration.ofMinutes(5), Duration.between(issuedAt, expiresAt));

        clock.advance(Duration.ofMinutes(4));
        mvc.perform(post("/api/plans/{planId}/person-entries", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reporter\":\"警戒员\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/plans/{planId}/commands/{commandId}/execution", planId, commandId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"EX-1\",\"executor\":\"爆破员孙七\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/plans/{planId}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupied").value(true))
                .andExpect(jsonPath("$.rounds[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.rounds[0].confirmations.length()").value(3))
                .andExpect(jsonPath("$.commands[0].status").value("INVALIDATED"))
                .andExpect(jsonPath("$.executionReports.length()").value(0));

        confirm(planId, "CP-NORTH", "安全员李四");
        confirm(planId, "CP-EAST", "安全员王五");
        confirm(planId, "CP-GATE", "安全员赵六");
        JsonNode command2 = issue(planId, "CMD-2", 201);
        long commandId2 = command2.get("commandId").asLong();
        mvc.perform(post("/api/plans/{planId}/commands/{commandId}/execution", planId, commandId2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"EX-2\",\"executor\":\"爆破员孙七\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("SUCCESS"));
        mvc.perform(get("/api/plans/{planId}", planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.rounds.length()").value(2))
                .andExpect(jsonPath("$.commands[1].status").value("EXECUTED"))
                .andExpect(jsonPath("$.executionReports.length()").value(1));
    }

    @Test
    void confirmWithNonZeroHeadcountReturns400() throws Exception {
        long planId = createPlan();
        mvc.perform(post("/api/plans/{planId}/checkpoints/{code}/confirmation", planId, "CP-NORTH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmer\":\"安全员李四\",\"headcount\":2}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void issueReplayReturnsSameCommandAndDifferentParamsReturn409() throws Exception {
        long planId = createPlan();
        confirm(planId, "CP-NORTH", "安全员李四");
        confirm(planId, "CP-EAST", "安全员王五");
        confirm(planId, "CP-GATE", "安全员赵六");
        JsonNode command = issue(planId, "CMD-1", 201);
        JsonNode replayed = issue(planId, "CMD-1", 201);
        assertEquals(command.get("commandId").asLong(), replayed.get("commandId").asLong());
        mvc.perform(post("/api/plans/{planId}/commands", planId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"CMD-1\",\"issuedBy\":\"另一个班长\"}"))
                .andExpect(status().isConflict());
    }
}