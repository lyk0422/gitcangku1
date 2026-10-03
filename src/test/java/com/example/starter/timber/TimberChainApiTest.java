package com.example.starter.timber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 监管链主流程与失败分支的端到端测试：真实H2写入，MockMvc走完整HTTP层。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimberChainApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM request_log");
        jdbc.update("DELETE FROM transfer_order");
        jdbc.update("DELETE FROM timber_batch");
        jdbc.update("DELETE FROM timber_source");
    }

    private void registerSource(String voucher, String species, int weight, String holder) throws Exception {
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"voucherNo\":\"" + voucher + "\",\"species\":\"" + species
                                + "\",\"weightKg\":" + weight + ",\"holder\":\"" + holder + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootBatchNo").value("BATCH-" + voucher))
                .andExpect(jsonPath("$.weightKg").value(weight));
    }

    private ResultActions split(String parentBatchNo, String requestId, String childrenJson) throws Exception {
        return mvc.perform(post("/api/batches/{batchNo}/splits", parentBatchNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"children\":[" + childrenJson + "]}"));
    }

    private ResultActions transfer(String batchNo, String requestId, String from, String to) throws Exception {
        return mvc.perform(post("/api/batches/{batchNo}/transfers", batchNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"fromHolder\":\"" + from
                        + "\",\"toHolder\":\"" + to + "\"}"));
    }

    private ResultActions sign(long transferId, String requestId, String decision, String reason) throws Exception {
        String reasonPart = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return mvc.perform(post("/api/transfers/{id}/sign", transferId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"" + requestId + "\",\"decision\":\"" + decision + "\"" + reasonPart + "}"));
    }

    private long transferIdOf(ResultActions actions) throws Exception {
        String body = actions.andReturn().getResponse().getContentAsString();
        String marker = "\"transferId\":";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf(',', start);
        return Long.parseLong(body.substring(start, end));
    }

    @Test
    void fullChainSplitTransferAcceptAndTrace() throws Exception {
        registerSource("V-FULL", "红松", 100, "加工点A");

        split("BATCH-V-FULL", "S-FULL-1",
                "{\"batchNo\":\"B-F-60\",\"weightKg\":60},{\"batchNo\":\"B-F-40\",\"weightKg\":40}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children.length()").value(2))
                .andExpect(jsonPath("$.children[0].voucherNo").value("V-FULL"))
                .andExpect(jsonPath("$.children[0].species").value("红松"))
                .andExpect(jsonPath("$.children[0].holder").value("加工点A"));

        long transferId = transferIdOf(transfer("B-F-60", "T-FULL-1", "加工点A", "下游B")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")));

        sign(transferId, "G-FULL-1", "ACCEPT", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.holder").value("下游B"));

        mvc.perform(get("/api/batches/{batchNo}/trace", "B-F-60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherNo").value("V-FULL"))
                .andExpect(jsonPath("$.species").value("红松"))
                .andExpect(jsonPath("$.weightKg").value(60))
                .andExpect(jsonPath("$.holder").value("下游B"))
                .andExpect(jsonPath("$.chain.length()").value(2))
                .andExpect(jsonPath("$.chain[0].batchNo").value("BATCH-V-FULL"))
                .andExpect(jsonPath("$.chain[0].leaf").value(false))
                .andExpect(jsonPath("$.chain[1].batchNo").value("B-F-60"))
                .andExpect(jsonPath("$.chain[1].leaf").value(true));
    }

    @Test
    void splitWeightMismatchReturns422AndBatchStaysUsableAndKeyNotConsumed() throws Exception {
        registerSource("V-W", "云杉", 100, "加工点A");

        split("BATCH-V-W", "S-W-1",
                "{\"batchNo\":\"B-W-60\",\"weightKg\":60},{\"batchNo\":\"B-W-50\",\"weightKg\":50}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WEIGHT_NOT_CONSERVED"));

        assertEquals(0, count("SELECT COUNT(*) FROM timber_batch WHERE parent_id IS NOT NULL"));
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'S-W-1'"));
        assertEquals(1, count("SELECT COUNT(*) FROM timber_batch WHERE batch_no = 'BATCH-V-W' AND leaf = TRUE"));

        split("BATCH-V-W", "S-W-1",
                "{\"batchNo\":\"B-W-60\",\"weightKg\":60},{\"batchNo\":\"B-W-40\",\"weightKg\":40}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children.length()").value(2));
        assertEquals(2, count("SELECT COUNT(*) FROM timber_batch WHERE parent_id IS NOT NULL"));
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}