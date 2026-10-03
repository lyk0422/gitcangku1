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
import org.springframework.test.web.servlet.ResultActions;

/**
 * 拆分与移交的失败分支测试：重复编号、无权移交、已拆分父批次、重复申请。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TimberFailureBranchApiTest {

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

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    @Test
    void duplicateChildBatchNoInRequestReturns422() throws Exception {
        registerSource("V-DUP", 100, "加工点A");
        split("BATCH-V-DUP", "S-DUP-1",
                "{\"batchNo\":\"B-D-1\",\"weightKg\":50},{\"batchNo\":\"B-D-1\",\"weightKg\":50}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DUPLICATE_CHILD_BATCH_NO"));
        assertEquals(0, count("SELECT COUNT(*) FROM timber_batch WHERE parent_id IS NOT NULL"));
    }

    @Test
    void existingChildBatchNoReturns409AndLeavesNoPartialChildren() throws Exception {
        registerSource("V-E1", 100, "加工点A");
        registerSource("V-E2", 80, "加工点C");
        split("BATCH-V-E1", "S-E-1", "{\"batchNo\":\"B-E-1\",\"weightKg\":100}")
                .andExpect(status().isOk());

        split("BATCH-V-E2", "S-E-2",
                "{\"batchNo\":\"B-E-NEW\",\"weightKg\":40},{\"batchNo\":\"B-E-1\",\"weightKg\":40}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NO_EXISTS"));

        assertEquals(0, count("SELECT COUNT(*) FROM timber_batch b JOIN timber_source s ON b.source_id = s.id"
                + " WHERE s.voucher_no = 'V-E2' AND b.parent_id IS NOT NULL"));
        assertEquals(1, count("SELECT COUNT(*) FROM timber_batch WHERE batch_no = 'BATCH-V-E2' AND leaf = TRUE"));
        assertEquals(0, count("SELECT COUNT(*) FROM request_log WHERE request_id = 'S-E-2'"));
    }

    @Test
    void transferGuards() throws Exception {
        registerSource("V-G", 100, "加工点A");
        split("BATCH-V-G", "S-G-1",
                "{\"batchNo\":\"B-G-60\",\"weightKg\":60},{\"batchNo\":\"B-G-40\",\"weightKg\":40}")
                .andExpect(status().isOk());

        transfer("BATCH-V-G", "T-G-1", "加工点A", "下游B")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BATCH_ALREADY_SPLIT"));

        transfer("B-G-60", "T-G-2", "别人", "下游B")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLDER_MISMATCH"));

        transfer("B-G-60", "T-G-3", "加工点A", "加工点A")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_HOLDER"));

        transfer("B-G-60", "T-G-4", "加工点A", "下游B").andExpect(status().isOk());
        transfer("B-G-60", "T-G-5", "加工点A", "下游C")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_PENDING"));
        assertEquals(1, count("SELECT COUNT(*) FROM transfer_order WHERE status = 'PENDING'"));
    }

    @Test
    void duplicateSourceVoucherReturns409() throws Exception {
        registerSource("V-DUPSRC", 100, "加工点A");
        mvc.perform(post("/api/sources").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"voucherNo\":\"V-DUPSRC\",\"species\":\"红松\",\"weightKg\":50,\"holder\":\"加工点B\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_EXISTS"));
    }

    @Test
    void unknownBatchReturns404() throws Exception {
        split("NO-SUCH", "S-X-1", "{\"batchNo\":\"B-X\",\"weightKg\":1}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_FOUND"));
        transfer("NO-SUCH", "T-X-1", "A", "B")
                .andExpect(status().isNotFound());
    }
}