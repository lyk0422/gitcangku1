package com.example.starter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 异常标记、驳回与复烧流程：异常批次只能驳回或复烧，驳回后不能补录点再直接放行，
 * 复烧批次独立且不修改原始测温数据。真实 H2（MODE=MySQL）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kiln_test_anomaly;MODE=MySQL;DB_CLOSE_DELAY=-1")
class AnomalyRefireIT extends KilnApiIT {

    @Test
    void anomalyBlocksReleaseAndUploadThenRejectThenRefire() throws Exception {
        long programId = createProgram("GLAZE-H", 1);
        long batchId = createBatch("B-ANO-1", programId);
        upload(batchId, "req-a1", fullTrajectory()).andExpect(status().isOk());

        mvc.perform(post("/api/batches/{id}/anomalies", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"markedBy\":\"op-li\",\"reason\":\"热电偶漂移\"}"))
                .andExpect(status().isCreated());

        // 异常批次不得放行
        release(batchId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("异常")));
        // 异常批次禁止补录测温点
        upload(batchId, "req-a2", new int[][] {{3, 602}}).andExpect(status().isConflict());

        // 质检员驳回
        mvc.perform(post("/api/batches/{id}/reject", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"qc-wang\",\"reason\":\"轨迹不可信\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // 驳回后不能补录点再直接放行
        upload(batchId, "req-a3", new int[][] {{3, 602}}).andExpect(status().isConflict());
        release(batchId).andExpect(status().isConflict());

        // 发起独立复烧批次，默认沿用原程序版本
        JsonNode refire = om.readTree(mvc.perform(post("/api/batches/{id}/refire", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchNo\":\"B-ANO-1-REFIRE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FIRING"))
                .andExpect(jsonPath("$.refireOfId").value(batchId))
                .andReturn().getResponse().getContentAsString());
        long refireId = refire.get("id").asLong();
        assertTrue(refireId != batchId);

        // 原始测温数据未被覆盖
        JsonNode original = detail(batchId);
        assertEquals(9, original.get("readingCount").asInt());
        assertEquals("REJECTED", original.get("batch").get("status").asText());
        assertEquals(1, original.get("anomalies").size());

        // 复烧批次独立走完整流程可放行
        upload(refireId, "req-a4", fullTrajectory()).andExpect(status().isOk());
        release(refireId).andExpect(status().isOk());
    }

    @Test
    void rejectWithoutAnomalyBlocksRelease() throws Exception {
        long programId = createProgram("GLAZE-I", 1);
        long batchId = createBatch("B-REJ-1", programId);
        upload(batchId, "req-j1", fullTrajectory()).andExpect(status().isOk());
        mvc.perform(post("/api/batches/{id}/reject", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"qc-wang\",\"reason\":\"整批报废\"}"))
                .andExpect(status().isOk());
        release(batchId).andExpect(status().isConflict());
        mvc.perform(post("/api/batches/{id}/reject", batchId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectedBy\":\"qc-wang\",\"reason\":\"重复驳回\"}"))
                .andExpect(status().isConflict());
    }
}