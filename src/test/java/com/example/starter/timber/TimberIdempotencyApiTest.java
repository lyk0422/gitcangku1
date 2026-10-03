package com.example.starter.timber;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 幂等重放与签收拒绝测试：同参重放原结果、异参409、拒绝保留原因且不移交。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimberIdempotencyApiTest {

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

    private void registerSource(String voucher, int weight, String holder) throws Exception {
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"voucherNo\":\"" + voucher + "\",\"species\":\"红松\",\"weightKg\":"
                                + weight + ",\"holder\":\"" + holder + "\"}"))
                .andExpect(status().isOk());
    }

    private MvcResult split(String parentBatchNo, String requestId, String childrenJson) throws Exception {
        return mvc.perform(post("/api/batches/{batchNo}/splits", parentBatchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"children\":[" + childrenJson + "]}"))
                .andReturn();
    }

    private long createTransfer(String batchNo, String requestId, String from, String to) throws Exception {
        MvcResult result = mvc.perform(post("/api/batches/{batchNo}/transfers", batchNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"fromHolder\":\"" + from
                                + "\",\"toHolder\":\"" + to + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        String marker = "\"transferId\":";
        int start = body.indexOf(marker) + marker.length();
        int end = body.indexOf(',', start);
        return Long.parseLong(body.substring(start, end));
    }

    private MvcResult sign(long transferId, String requestId, String decision, String reason) throws Exception {
        String reasonPart = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return mvc.perform(post("/api/transfers/{id}/sign", transferId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"" + requestId + "\",\"decision\":\"" + decision + "\"" + reasonPart + "}"))
                .andReturn();
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    @Test
    void splitSameParamsReplaysOriginalResultAndDifferentParamsConflict() throws Exception {
        registerSource("V-I", 100, "加工点A");
        String children = "{\"batchNo\":\"B-I-60\",\"weightKg\":60},{\"batchNo\":\"B-I-40\",\"weightKg\":40}";

        MvcResult first = split("BATCH-V-I", "S-I-1", children);
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = split("BATCH-V-I", "S-I-1", children);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals(2, count("SELECT COUNT(*) FROM timber_batch WHERE parent_id IS NOT NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'S-I-1'"));

        MvcResult conflict = split("BATCH-V-I", "S-I-1",
                "{\"batchNo\":\"B-I-60\",\"weightKg\":60},{\"batchNo\":\"B-I-41\",\"weightKg\":41}");
        assertEquals(409, conflict.getResponse().getStatus());
    }

    @Test
    void signSameParamsReplaysAndDifferentParamsConflict() throws Exception {
        registerSource("V-S", 100, "加工点A");
        long transferId = createTransfer("BATCH-V-S", "T-S-1", "加工点A", "下游B");

        MvcResult first = sign(transferId, "G-S-1", "ACCEPT", null);
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = sign(transferId, "G-S-1", "ACCEPT", null);
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(first.getResponse().getContentAsString(), replay.getResponse().getContentAsString());
        assertEquals("下游B", jdbc.queryForObject(
                "SELECT holder FROM timber_batch WHERE batch_no = 'BATCH-V-S'", String.class));

        MvcResult conflict = sign(transferId, "G-S-1", "REJECT", "重量不符");
        assertEquals(409, conflict.getResponse().getStatus());
    }

    @Test
    void rejectKeepsHolderRecordsReasonAndTransferSurvives() throws Exception {
        registerSource("V-R", 100, "加工点A");
        long transferId = createTransfer("BATCH-V-R", "T-R-1", "加工点A", "下游B");

        MvcResult rejected = sign(transferId, "G-R-1", "REJECT", "含水率超标");
        assertEquals(200, rejected.getResponse().getStatus());

        assertEquals("加工点A", jdbc.queryForObject(
                "SELECT holder FROM timber_batch WHERE batch_no = 'BATCH-V-R'", String.class));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM transfer_order WHERE id = " + transferId, String.class));
        assertEquals("含水率超标", jdbc.queryForObject(
                "SELECT reject_reason FROM transfer_order WHERE id = " + transferId, String.class));
        assertEquals(1, count("SELECT COUNT(*) FROM transfer_order WHERE id = " + transferId));

        MvcResult again = sign(transferId, "G-R-2", "ACCEPT", null);
        assertEquals(409, again.getResponse().getStatus());
    }

    @Test
    void rejectWithoutReasonReturns422() throws Exception {
        registerSource("V-NR", 100, "加工点A");
        long transferId = createTransfer("BATCH-V-NR", "T-NR-1", "加工点A", "下游B");
        MvcResult result = sign(transferId, "G-NR-1", "REJECT", null);
        assertEquals(422, result.getResponse().getStatus());
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM transfer_order WHERE id = " + transferId, String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'G-NR-1'"));
    }

    @Test
    void transferSameParamsReplaysAndDifferentParamsConflict() throws Exception {
        registerSource("V-TR", 100, "加工点A");
        long first = createTransfer("BATCH-V-TR", "T-TR-1", "加工点A", "下游B");
        long replay = createTransfer("BATCH-V-TR", "T-TR-1", "加工点A", "下游B");
        assertEquals(first, replay);
        assertEquals(1, count("SELECT COUNT(*) FROM transfer_order"));

        MvcResult conflict = mvc.perform(post("/api/batches/{batchNo}/transfers", "BATCH-V-TR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"T-TR-1\",\"fromHolder\":\"加工点A\",\"toHolder\":\"下游C\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_CONFLICT"))
                .andReturn();
        assertEquals(1, count("SELECT COUNT(*) FROM transfer_order"));
    }
}