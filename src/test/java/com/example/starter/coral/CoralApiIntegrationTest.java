package com.example.starter.coral;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 真实H2（MODE=MySQL）集成测试：主流程、拒绝分支、幂等重放与封存快照。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:coral_test_api;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CoralApiIntegrationTest {

    private static final Instant FIXED = Instant.parse("2026-10-01T08:30:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private Clock coralClock;

    @BeforeEach
    void setUp() {
        lenient().when(coralClock.instant()).thenAnswer(inv -> Instant.now());
        lenient().when(coralClock.getZone()).thenReturn(ZoneOffset.UTC);
        jdbc.update("DELETE FROM review");
        jdbc.update("DELETE FROM plot_seal");
        jdbc.update("DELETE FROM transplant");
        jdbc.update("DELETE FROM plot");
    }

    @AfterAll
    void shutdownDb() {
        try {
            jdbc.execute("SHUTDOWN");
        } catch (Exception ignored) {
            // 库已随上下文关闭
        }
    }

    private void insertPlot(String code) {
        jdbc.update("INSERT INTO plot (plot_code, capacity, status) VALUES (?, 12, 'ACTIVE')",
                code);
    }

    private long plotId(String code) {
        return jdbc.queryForObject("SELECT id FROM plot WHERE plot_code = ?", Long.class, code);
    }

    private void insertTransplant(String req, String plant, long plotId, String status) {
        jdbc.update(
                "INSERT INTO transplant (request_id, plant_code, nursery, plot_id,"
                        + " transplanted_at, status) VALUES (?, ?, '合成苗圃T', ?,"
                        + " TIMESTAMP '2026-09-01 00:00:00', ?)",
                req, plant, plotId, status);
    }

    private void fillPlot(String code, int activeCount) {
        insertPlot(code);
        long id = plotId(code);
        for (int i = 0; i < activeCount; i++) {
            insertTransplant("seed-" + code + "-" + i, code + "-P" + i, id, "ALIVE");
        }
    }

    private String registerBody(String req, String plant, String plot) {
        return "{\"requestId\":\"" + req + "\",\"plantCode\":\"" + plant
                + "\",\"nursery\":\"合成苗圃N\",\"plotCode\":\"" + plot + "\"}";
    }

    @Test
    void registerReviewAndQueryFlow() throws Exception {
        insertPlot("T-OPEN");
        when(coralClock.instant()).thenReturn(FIXED);

        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-1", "C-001", "T-OPEN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nursery").value("合成苗圃N"))
                .andExpect(jsonPath("$.plotCode").value("T-OPEN"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.transplantedAt").value("2026-10-01T08:30:00Z"))
                .andExpect(jsonPath("$.seal.sealed").value(false));

        mvc.perform(post("/api/coral/transplants/C-001/reviews").contentType("application/json")
                        .content("{\"requestId\":\"rev-1\",\"result\":\"ALIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ALIVE"))
                .andExpect(jsonPath("$.reviews[0].requestId").value("rev-1"))
                .andExpect(jsonPath("$.reviews[0].reviewedAt").value("2026-10-01T08:30:00Z"));

        mvc.perform(get("/api/coral/transplants/C-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews.length()").value(1));

        mvc.perform(get("/api/coral/plots/T-OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(1))
                .andExpect(jsonPath("$.aliveCount").value(1))
                .andExpect(jsonPath("$.seal").doesNotExist());
    }

    @Test
    void fullPlotRejectsThirteenthTransplant() throws Exception {
        fillPlot("T-FULL", 12);
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-13", "C-013", "T-FULL")))
                .andExpect(status().isConflict());
    }

    @Test
    void deathReleasesCapacityForNewTransplant() throws Exception {
        fillPlot("T-REL", 12);
        mvc.perform(post("/api/coral/transplants/T-REL-P0/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-dead\",\"result\":\"DEAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEAD"));

        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-new", "C-NEW", "T-REL")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/coral/plots/T-REL"))
                .andExpect(jsonPath("$.activeCount").value(12))
                .andExpect(jsonPath("$.deadCount").value(1));
    }
    @Test
    void duplicatePlantCodeRejected() throws Exception {
        insertPlot("T-DUP");
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-a", "C-DUP", "T-DUP")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-b", "C-DUP", "T-DUP")))
                .andExpect(status().isConflict());
    }

    @Test
    void idempotentReplayAndParamMismatch() throws Exception {
        insertPlot("T-IDM");
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-same", "C-IDM", "T-IDM")))
                .andExpect(status().isCreated());
        // 同参重放：返回原记录
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-same", "C-IDM", "T-IDM")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plantCode").value("C-IDM"));
        // 同requestId异参：409
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-same", "C-OTHER", "T-IDM")))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/coral/transplants/C-IDM/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-same\",\"result\":\"ALIVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/coral/transplants/C-IDM/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-same\",\"result\":\"ALIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews.length()").value(1));
        mvc.perform(post("/api/coral/transplants/C-IDM/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-same\",\"result\":\"DEAD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void deadPlantCannotBeMarkedAliveAgain() throws Exception {
        insertPlot("T-DEAD");
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-d", "C-DEAD", "T-DEAD")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/coral/transplants/C-DEAD/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-d1\",\"result\":\"DEAD\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/coral/transplants/C-DEAD/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-d2\",\"result\":\"ALIVE\"}"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/coral/transplants/C-DEAD/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-d3\",\"result\":\"PENDING\"}"))
                .andExpect(status().isUnprocessableEntity());
        // 失败复核不得改变状态
        mvc.perform(get("/api/coral/transplants/C-DEAD"))
                .andExpect(jsonPath("$.status").value("DEAD"))
                .andExpect(jsonPath("$.reviews.length()").value(1));
    }

    @Test
    void sealRequiresNoPendingAndKeepsDeadRecords() throws Exception {
        insertPlot("T-SEAL");
        long id = plotId("T-SEAL");
        insertTransplant("s-1", "C-S1", id, "ALIVE");
        insertTransplant("s-2", "C-S2", id, "DEAD");
        insertTransplant("s-3", "C-S3", id, "PENDING");

        mvc.perform(post("/api/coral/plots/T-SEAL/seal").contentType("application/json")
                        .content("{\"requestId\":\"seal-1\"}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/api/coral/transplants/C-S3/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-s3\",\"result\":\"ALIVE\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/coral/plots/T-SEAL/seal").contentType("application/json")
                        .content("{\"requestId\":\"seal-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliveCount").value(2))
                .andExpect(jsonPath("$.deadCount").value(1))
                .andExpect(jsonPath("$.pendingCount").value(0))
                .andExpect(jsonPath("$.plants.length()").value(3))
                .andExpect(jsonPath("$.plants[1].plantCode").value("C-S2"))
                .andExpect(jsonPath("$.plants[1].status").value("DEAD"));

        // 同requestId重放返回原快照，异requestId冲突
        mvc.perform(post("/api/coral/plots/T-SEAL/seal").contentType("application/json")
                        .content("{\"requestId\":\"seal-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aliveCount").value(2));
        mvc.perform(post("/api/coral/plots/T-SEAL/seal").contentType("application/json")
                        .content("{\"requestId\":\"seal-2\"}"))
                .andExpect(status().isConflict());

        // 封存后查询：单株视图带封存数量，样方视图带快照
        mvc.perform(get("/api/coral/transplants/C-S2"))
                .andExpect(jsonPath("$.status").value("DEAD"))
                .andExpect(jsonPath("$.seal.sealed").value(true))
                .andExpect(jsonPath("$.seal.deadCount").value(1));
        mvc.perform(get("/api/coral/plots/T-SEAL"))
                .andExpect(jsonPath("$.status").value("SEALED"))
                .andExpect(jsonPath("$.seal.aliveCount").value(2));
    }

    @Test
    void sealedPlotRejectsTransplantAndReview() throws Exception {
        insertPlot("T-LOCK");
        long id = plotId("T-LOCK");
        insertTransplant("l-1", "C-L1", id, "ALIVE");
        mvc.perform(post("/api/coral/plots/T-LOCK/seal").contentType("application/json")
                        .content("{\"requestId\":\"seal-l\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-l2", "C-L2", "T-LOCK")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/coral/transplants/C-L1/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-l1\",\"result\":\"DEAD\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void notFoundBranches() throws Exception {
        insertPlot("T-NF");
        mvc.perform(get("/api/coral/transplants/NOPE")).andExpect(status().isNotFound());
        mvc.perform(get("/api/coral/plots/NOPE")).andExpect(status().isNotFound());
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content(registerBody("req-nf", "C-NF", "NOPE")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/coral/transplants/NOPE/reviews")
                        .contentType("application/json")
                        .content("{\"requestId\":\"rev-nf\",\"result\":\"ALIVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void blankRequestIdRejected() throws Exception {
        insertPlot("T-VAL");
        mvc.perform(post("/api/coral/transplants").contentType("application/json")
                        .content("{\"requestId\":\"\",\"plantCode\":\"C-V\","
                                + "\"nursery\":\"合成苗圃N\",\"plotCode\":\"T-VAL\"}"))
                .andExpect(status().isBadRequest());
    }
}