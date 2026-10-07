package com.example.starter.calibration;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 参考漂移并发测试：档案撤销与批量放行并发时按事务提交顺序生效，
 * 不出现“撤销后仍被放行”；两种结局的状态与历史必须自洽。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DriftConcurrencyApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM drift_reference_archive");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM request_dedup");
    }

    @Test
    void archiveRevokeAndReleaseConcurrentFollowCommitOrder() throws Exception {
        // 重复多轮，验证任意调度下都不出现“撤销后仍被放行”
        for (int round = 0; round < 12; round++) {
            final int r = round;
            mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":"INS-DR%d","validFrom":"2026-01-01T00:00:00Z",
                                     "validTo":"2028-01-01T00:00:00Z","a":"1","b":"0"}""".formatted(r)))
                    .andExpect(status().isCreated());
            String archiveBody = mvc.perform(post("/api/drift-archives")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":"INS-DR%d","referencedAt":"2026-06-01T10:00:00Z",
                                     "offset":"0"}""".formatted(r)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long archiveId0 = ((Number) com.jayway.jsonpath.JsonPath.read(archiveBody, "$.id")).longValue();
            String archiveBody1 = mvc.perform(post("/api/drift-archives")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"instrumentId":"INS-DR%d","referencedAt":"2026-06-01T11:00:00Z",
                                     "offset":"2"}""".formatted(r)))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            long archiveId1 = ((Number) com.jayway.jsonpath.JsonPath.read(archiveBody1, "$.id")).longValue();

            mvc.perform(post("/api/measurements/drift").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"measurementKey":"MD-%d","instrumentId":"INS-DR%d",
                                     "measuredAt":"2026-06-01T10:30:00Z","reading":"5",
                                     "lowerLimit":"0","upperLimit":"9","submittedBy":"alice",
                                     "archiveId0":%d,"archiveId1":%d}""".formatted(r, r, archiveId0, archiveId1)))
                    .andExpect(status().isCreated());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> revokeFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/drift-archives/{id}/revoke", archiveId0))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> releaseFuture = pool.submit(() -> {
                start.await();
                return mvc.perform(post("/api/measurements/release")
                                .header("X-Actor-Id", "carol")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"keys\":[\"MD-" + r + "\"]}"))
                        .andReturn().getResponse().getStatus();
            });
            start.countDown();
            int revokeStatus = revokeFuture.get(30, TimeUnit.SECONDS);
            int releaseStatus = releaseFuture.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertEquals(200, revokeStatus);
            assertTrue(releaseStatus == 200 || releaseStatus == 409);

            Map<String, Object> archive = jdbc.queryForMap(
                    "SELECT * FROM drift_reference_archive WHERE id = ?", archiveId0);
            assertEquals(Boolean.TRUE, archive.get("revoked"), "撤销必须最终生效");
            Map<String, Object> measurement = jdbc.queryForMap(
                    "SELECT * FROM measurement WHERE measurement_key = ?", "MD-" + r);
            Integer releaseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM release_record WHERE measurement_id = ?",
                    Integer.class, ((Number) measurement.get("id")).longValue());

            if (releaseCount > 0) {
                // 放行先提交：历史保留、状态已放行；撤销在其后生效
                assertEquals(200, releaseStatus);
                assertEquals("RELEASED", measurement.get("status"));
                java.sql.Timestamp revokedAt = (java.sql.Timestamp) archive.get("revoked_at");
                Map<String, Object> release = jdbc.queryForMap(
                        "SELECT * FROM release_record WHERE measurement_id = ?",
                        ((Number) measurement.get("id")).longValue());
                assertFalse(((java.sql.Timestamp) release.get("released_at"))
                        .after(revokedAt), "撤销提交后不得再放行引用该档案的结果");
            } else {
                // 撤销先提交：放行必须 409，测量保持待放行
                assertEquals(409, releaseStatus);
                assertEquals("PENDING", measurement.get("status"));
            }
        }
    }
}
