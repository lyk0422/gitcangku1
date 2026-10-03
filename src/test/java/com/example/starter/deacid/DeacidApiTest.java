package com.example.starter.deacid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层测试：验证 HTTP 状态码、幂等重放与 422 问题页清单。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeacidApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM seal_request");
        jdbc.update("DELETE FROM treatment_record");
        jdbc.update("DELETE FROM page_occupancy");
        jdbc.update("DELETE FROM batch_page");
        jdbc.update("DELETE FROM deacid_batch");
        jdbc.update("DELETE FROM archive_page");
    }

    @Test
    void 完整业务链接口调用() throws Exception {
        mvc.perform(post("/api/pages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionNo\":\"CN-W1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pageId").isNumber());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"B-W1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        long batchId = jdbc.queryForObject(
                "SELECT id FROM deacid_batch WHERE batch_no = 'B-W1'", Long.class);
        long pageId = jdbc.queryForObject(
                "SELECT id FROM archive_page WHERE collection_no = 'CN-W1'", Long.class);

        mvc.perform(post("/api/batches/{b}/pages", batchId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-e1\",\"collectionNo\":\"CN-W1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(post("/api/batches/{b}/pages", batchId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-e1\",\"collectionNo\":\"CN-W1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post("/api/batches/{b}/pages", batchId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-e1\",\"collectionNo\":\"CN-OTHER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));

        mvc.perform(post("/api/batches/{b}/pages/{p}/treatments", batchId, pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phBefore\":6.8,\"phAfter\":7.4,\"reagentBatchNo\":\"RG-W\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roundNo").value(1));

        mvc.perform(post("/api/batches/{b}/seal", batchId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SEALED"));

        mvc.perform(get("/api/pages/{c}/treatments", "CN-W1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds[0].batchStatus").value("SEALED"))
                .andExpect(jsonPath("$.rounds[0].reagentBatchNo").value("RG-W"));
    }

    @Test
    void 封存拒绝返回422及问题页清单() throws Exception {
        mvc.perform(post("/api/pages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"collectionNo\":\"CN-W2\"}"));
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                .content("{\"batchNo\":\"B-W2\"}"));
        long batchId = jdbc.queryForObject(
                "SELECT id FROM deacid_batch WHERE batch_no = 'B-W2'", Long.class);
        mvc.perform(post("/api/batches/{b}/pages", batchId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"w-e2\",\"collectionNo\":\"CN-W2\"}"));

        mvc.perform(post("/api/batches/{b}/seal", batchId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"w-s2\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEAL_REJECTED"))
                .andExpect(jsonPath("$.problems[0].collectionNo").value("CN-W2"))
                .andExpect(jsonPath("$.problems[0].reasons[0]").value("MISSING_READINGS"));
    }
}