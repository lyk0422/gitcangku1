package com.example.starter.maintenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 等效寿命并发竞争测试：更正与保养按设备版本裁决，恰有一个生效；
 * 保养冻结的等效基准与最终读数状态一致，不会出现基准取旧等效值而读数已改。
 */
@SpringBootTest
@AutoConfigureMockMvc
class EquipmentEquivalentConcurrencyTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("DELETE FROM maintenance");
        jdbc.update("DELETE FROM reading_revision");
        jdbc.update("DELETE FROM reading");
        jdbc.update("DELETE FROM equipment");
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private MvcResult postJson(String url, String body) throws Exception {
        return mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    @Test
    void concurrentReviseVsMaintenance_equivalentBaselineConsistent() throws Exception {
        mockMvc.perform(post("/api/equipment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"er-reg","equipmentId":"eq-erace","maintenancePeriodMinutes":10000,
                                 "equivalentPeriodMinutes":1000}
                                """))
                .andExpect(status().isCreated());
        postJson("/api/equipment/eq-erace/readings", """
                {"requestId":"er-r1","expectedVersion":1,"readingId":"r1",
                 "sampledAt":"2026-01-01T10:00:00Z","cumulativeMinutes":100,"loadWeight":"NORMAL"}
                """);
        postJson("/api/equipment/eq-erace/readings", """
                {"requestId":"er-r2","expectedVersion":2,"readingId":"r2",
                 "sampledAt":"2026-01-01T11:00:00Z","cumulativeMinutes":150,"loadWeight":"HEAVY"}
                """);
        // 当前等效累计 200；若 r1 更正为 80 则变为 220

        CountDownLatch gate = new CountDownLatch(1);
        Future<MvcResult> reviseFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-erace/readings/r1/revisions", """
                    {"requestId":"er-revise","expectedVersion":3,"cumulativeMinutes":80}
                    """);
        });
        Future<MvcResult> maintenanceFuture = executor.submit(() -> {
            gate.await(5, TimeUnit.SECONDS);
            return postJson("/api/equipment/eq-erace/maintenances", """
                    {"requestId":"er-mnt","expectedVersion":3,"readingId":"r2","anchorRevisionNo":1}
                    """);
        });
        gate.countDown();

        MvcResult revise = reviseFuture.get(15, TimeUnit.SECONDS);
        MvcResult maintenance = maintenanceFuture.get(15, TimeUnit.SECONDS);
        int reviseStatus = revise.getResponse().getStatus();
        int maintenanceStatus = maintenance.getResponse().getStatus();

        // 版本裁决：恰有一个成功
        assertTrue(
                (reviseStatus == 201 && maintenanceStatus == 409)
                        || (reviseStatus == 409 && maintenanceStatus == 201),
                "更正与保养并发应恰有一个成功，实际：revise=" + reviseStatus
                        + ", maintenance=" + maintenanceStatus);
        mockMvc.perform(get("/api/equipment/eq-erace/status"))
                .andExpect(jsonPath("$.version").value(4));

        if (maintenanceStatus == 201) {
            // 保养胜出：冻结基准为更正前的等效 200，随后更正生效为 220，运行量 20
            mockMvc.perform(get("/api/equipment/eq-erace/maintenances"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].anchorEquivalentCumulativeMinutes").value(200))
                    .andExpect(jsonPath("$[0].latestReadingId").value("r2"));
            MvcResult later = postJson("/api/equipment/eq-erace/readings/r1/revisions", """
                    {"requestId":"er-revise-2","expectedVersion":4,"cumulativeMinutes":80}
                    """);
            assertEquals(201, later.getResponse().getStatus());
            mockMvc.perform(get("/api/equipment/eq-erace/status"))
                    .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(220))
                    .andExpect(jsonPath("$.equivalentRunMinutes").value(20));
            Long frozen = jdbc.queryForObject(
                    "SELECT anchor_equivalent_cumulative_minutes FROM maintenance"
                            + " WHERE equipment_id = 'eq-erace'", Long.class);
            assertEquals(200L, frozen, "冻结基线不得被后续更正改变");
        } else {
            // 更正胜出：无保养记录，等效已为 220；随后保养冻结 220 而非旧的 200
            mockMvc.perform(get("/api/equipment/eq-erace/maintenances"))
                    .andExpect(jsonPath("$.length()").value(0));
            mockMvc.perform(get("/api/equipment/eq-erace/status"))
                    .andExpect(jsonPath("$.latestEquivalentCumulativeMinutes").value(220));
            MvcResult later = postJson("/api/equipment/eq-erace/maintenances", """
                    {"requestId":"er-mnt-2","expectedVersion":4,"readingId":"r2","anchorRevisionNo":1}
                    """);
            assertEquals(201, later.getResponse().getStatus());
            mockMvc.perform(get("/api/equipment/eq-erace/maintenances"))
                    .andExpect(jsonPath("$[0].anchorEquivalentCumulativeMinutes").value(220));
        }
    }
}
