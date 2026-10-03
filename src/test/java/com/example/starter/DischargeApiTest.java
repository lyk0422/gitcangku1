package com.example.starter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * REST 入口测试：状态码、错误体电芯编号与幂等 409。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
class DischargeApiTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM handover_snapshot");
        jdbc.update("DELETE FROM cell_isolation");
        jdbc.update("DELETE FROM cell_reading");
        jdbc.update("DELETE FROM battery_cell");
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM discharge_batch");
    }

    private String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(pairs[i]).append("\":\"").append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    @Test
    void restFlow_rejectionAndIdempotencyStatuses() throws Exception {
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content(json("batchNo", "BATCH-API-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        for (int i = 1; i <= 3; i++) {
            mvc.perform(post("/api/batches/BATCH-API-1/cells").contentType(MediaType.APPLICATION_JSON)
                            .content(json("requestId", "REQ-E" + i, "cellCode",
                                    "CELL-00" + i, "initialVoltage", "3.70")))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/api/batches/BATCH-API-1/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(json("requestId", "REQ-R1", "cellCode", "CELL-001",
                                "voltage", "0.30", "measuredAt", "2026-01-01T01:00:00Z")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/BATCH-API-1/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(json("requestId", "REQ-R2", "cellCode", "CELL-002",
                                "voltage", "0.60", "measuredAt", "2026-01-01T01:00:00Z")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches/BATCH-API-1/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(json("requestId", "REQ-R3", "cellCode", "CELL-003",
                                "voltage", "0.40", "measuredAt", "2026-01-01T01:00:00Z")))
                .andExpect(status().isCreated());

        // 0.60V 电芯导致整批拒绝，响应指出电芯编号
        mvc.perform(post("/api/batches/BATCH-API-1/handover").contentType(MediaType.APPLICATION_JSON)
                        .content(json("requestId", "REQ-H1", "fromParty", "回收站A", "toParty", "拆解区B")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.cellCodes[0]").value("CELL-002"));

        // 测量幂等：同 requestId 异参返回 409
        mvc.perform(post("/api/batches/BATCH-API-1/readings").contentType(MediaType.APPLICATION_JSON)
                        .content(json("requestId", "REQ-R1", "cellCode", "CELL-001",
                                "voltage", "0.99", "measuredAt", "2026-01-01T01:00:00Z")))
                .andExpect(status().isConflict());

        // 隔离异常电芯后重新交接成功
        mvc.perform(post("/api/batches/BATCH-API-1/isolations").contentType(MediaType.APPLICATION_JSON)
                        .content(json("cellCode", "CELL-002", "reason", "终测电压0.60V超限")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISOLATED"));

        mvc.perform(post("/api/batches/BATCH-API-1/handover").contentType(MediaType.APPLICATION_JSON)
                        .content(json("requestId", "REQ-H2", "fromParty", "回收站A", "toParty", "拆解区B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.cells.length()").value(2));

        mvc.perform(get("/api/batches/BATCH-API-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(3))
                .andExpect(jsonPath("$.handoverSnapshot.length()").value(2));
    }
}
