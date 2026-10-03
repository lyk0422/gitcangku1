package com.example.starter.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 主流程集成测试：合法轨迹放行、8分钟空档拒绝与补录复检、超差、边界缺失、
 * 重复时间戳、requestId 幂等、放行后拒绝写入。真实 H2（MODE=MySQL）。
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:kiln_test_flow;MODE=MySQL;DB_CLOSE_DELAY=-1")
class KilnFlowIT extends KilnApiIT {

    @Test
    void happyPathReleaseAndDetail() throws Exception {
        long programId = createProgram("GLAZE-A", 1);
        long batchId = createBatch("B-HAPPY-1", programId);
        upload(batchId, "req-h1", fullTrajectory()).andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(9))
                .andExpect(header().string("X-Idempotent-Replay", "false"));

        clock.setInstant(T0.plusSeconds(3600));
        release(batchId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.basis.programVersionId").value(programId))
                .andExpect(jsonPath("$.basis.readingCount").value(9))
                .andExpect(jsonPath("$.basis.checkedAt").value("2026-02-01T01:00:00Z"));

        JsonNode detail = detail(batchId);
        assertEquals("RELEASED", detail.get("batch").get("status").asText());
        assertEquals(programId, detail.get("programVersion").get("id").asLong());
        JsonNode devs = detail.get("segmentDeviations");
        assertEquals(0, devs.get(0).get("worstDeviationC").decimalValue()
                .compareTo(new java.math.BigDecimal("12.00")));
        assertEquals(0, devs.get(1).get("worstDeviationC").decimalValue()
                .compareTo(new java.math.BigDecimal("10.00")));
        JsonNode release = detail.get("release");
        assertEquals(9, release.get("readingCount").asInt());
        assertTrue(release.get("readingMaxId").asLong() > 0);
        assertTrue(release.get("basisJson").asText().contains("segmentDeviations"));
    }

    @Test
    void eightMinuteGapRejectedThenBackfillAndRelease() throws Exception {
        long programId = createProgram("GLAZE-B", 1);
        long batchId = createBatch("B-GAP-1", programId);
        int[][] withGap = {{0, 600}, {5, 605}, {10, 610}, {18, 612}, {20, 800},
                {25, 805}, {30, 808}, {35, 810}, {40, 800}};
        upload(batchId, "req-g1", withGap).andExpect(status().isOk());

        release(batchId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.violations[0].type").value("SAMPLING_GAP"))
                .andExpect(jsonPath("$.details.violations[0].segmentSeq").value(1))
                .andExpect(jsonPath("$.details.violations[0].time")
                        .value("2026-02-01T00:18:00Z"));

        // 批次未驳回，补录合法点后重新检查通过
        upload(batchId, "req-g2", new int[][] {{15, 611}}).andExpect(status().isOk());
        release(batchId).andExpect(status().isOk())
                .andExpect(jsonPath("$.basis.readingCount").value(10));
    }

    @Test
    void outOfToleranceRejectedWithSegmentAndTime() throws Exception {
        long programId = createProgram("GLAZE-C", 1);
        long batchId = createBatch("B-TOL-1", programId);
        int[][] points = fullTrajectory();
        points[2] = new int[] {10, 700};
        upload(batchId, "req-t1", points).andExpect(status().isOk());
        release(batchId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.violations[0].type").value("OUT_OF_TOLERANCE"))
                .andExpect(jsonPath("$.details.violations[0].segmentSeq").value(1))
                .andExpect(jsonPath("$.details.violations[0].time")
                        .value("2026-02-01T00:10:00Z"));
    }

    @Test
    void missingEndBoundaryRejected() throws Exception {
        long programId = createProgram("GLAZE-D", 1);
        long batchId = createBatch("B-BND-1", programId);
        int[][] points = java.util.Arrays.copyOf(fullTrajectory(), 8);
        upload(batchId, "req-b1", points).andExpect(status().isOk());
        release(batchId).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.details.violations[0].type").value("MISSING_END_BOUNDARY"))
                .andExpect(jsonPath("$.details.violations[0].time")
                        .value("2026-02-01T00:40:00Z"));
    }

    @Test
    void duplicateTimestampConflict() throws Exception {
        long programId = createProgram("GLAZE-E", 1);
        long batchId = createBatch("B-DUP-1", programId);
        upload(batchId, "req-d1", new int[][] {{0, 600}, {5, 605}}).andExpect(status().isOk());
        upload(batchId, "req-d2", new int[][] {{5, 606}}).andExpect(status().isConflict());
        upload(batchId, "req-d3", new int[][] {{10, 610}, {10, 611}})
                .andExpect(status().isConflict());
    }

    @Test
    void requestIdReplayAndConflict() throws Exception {
        long programId = createProgram("GLAZE-F", 1);
        long batchId = createBatch("B-IDM-1", programId);
        upload(batchId, "req-i1", new int[][] {{0, 600}, {5, 605}}).andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "false"));
        // 同参重放：返回首次结果，不产生新测温点
        upload(batchId, "req-i1", new int[][] {{0, 600}, {5, 605}}).andExpect(status().isOk())
                .andExpect(header().string("X-Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.acceptedCount").value(2));
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE batch_id = ?",
                Integer.class, batchId);
        assertEquals(2, count);
        // 异参：409
        upload(batchId, "req-i1", new int[][] {{0, 600}, {5, 606}})
                .andExpect(status().isConflict());
    }

    @Test
    void releasedBatchRejectsFurtherWrites() throws Exception {
        long programId = createProgram("GLAZE-G", 1);
        long batchId = createBatch("B-REL-1", programId);
        upload(batchId, "req-r1", fullTrajectory()).andExpect(status().isOk());
        release(batchId).andExpect(status().isOk());
        release(batchId).andExpect(status().isConflict());
        upload(batchId, "req-r2", new int[][] {{3, 602}}).andExpect(status().isConflict());
    }
}