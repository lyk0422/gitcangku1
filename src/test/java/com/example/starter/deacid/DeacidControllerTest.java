package com.example.starter.deacid;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层测试：HTTP 状态码与响应体映射，真实 H2  backing。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeacidControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        for (String table : List.of("seal_request", "enroll_request", "qc_rejection",
                "treatment_record", "page_occupancy", "batch_member", "deacid_batch", "archive_page")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void sealRejectionReturns422WithProblemPages() throws Exception {
        mvc.perform(post("/api/pages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionNo\":\"P-W01\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"B-W01\"}"))
                .andExpect(status().isCreated());
        long batchId = jdbc.queryForObject(
                "SELECT id FROM deacid_batch WHERE batch_no = 'B-W01'", Long.class);
        long pageId = jdbc.queryForObject(
                "SELECT id FROM archive_page WHERE collection_no = 'P-W01'", Long.class);

        mvc.perform(post("/api/batches/{batchId}/pages", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"enr-w1\",\"collectionNo\":\"P-W01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ENROLLED"));

        mvc.perform(post("/api/batches/{batchId}/pages/{pageId}/treatments", batchId, pageId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phBefore\":6.0,\"phAfter\":6.4,\"reagentBatchNo\":\"RG-W01\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roundNo").value(1));

        mvc.perform(post("/api/batches/{batchId}/seal", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"seal-w1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.outcome").value("REJECTED"))
                .andExpect(jsonPath("$.problems[0].collectionNo").value("P-W01"));

        mvc.perform(get("/api/pages/{collectionNo}/treatments", "P-W01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchNo").value("B-W01"))
                .andExpect(jsonPath("$[0].batchStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$[0].phAfter").value(6.4))
                .andExpect(jsonPath("$[0].reagentBatchNo").value("RG-W01"));
    }

    @Test
    void enrollConflictReturns409() throws Exception {
        mvc.perform(post("/api/pages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"collectionNo\":\"P-W02\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"B-W02\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/batches").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"B-W03\"}"))
                .andExpect(status().isCreated());
        long batchA = jdbc.queryForObject(
                "SELECT id FROM deacid_batch WHERE batch_no = 'B-W02'", Long.class);
        long batchB = jdbc.queryForObject(
                "SELECT id FROM deacid_batch WHERE batch_no = 'B-W03'", Long.class);

        mvc.perform(post("/api/batches/{batchId}/pages", batchA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"enr-w2\",\"collectionNo\":\"P-W02\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/batches/{batchId}/pages", batchB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"enr-w3\",\"collectionNo\":\"P-W02\"}"))
                .andExpect(status().isConflict());
    }
}