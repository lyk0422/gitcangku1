package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 曲线证书接口测试：创建主流程、节点校验、与线性证书的区间互斥、
 * 并发混合创建只留下不重叠集合、撤销语义。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurveCertificateApiTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM release_record");
        jdbc.update("DELETE FROM measurement");
        jdbc.update("DELETE FROM certificate_node");
        jdbc.update("DELETE FROM calibration_certificate");
        jdbc.update("DELETE FROM instrument_lock");
        jdbc.update("DELETE FROM idempotency_record");
    }

    private String curveJson(String instrument, String from, String to, String nodes) {
        return """
                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","nodes":%s}
                """.formatted(instrument, from, to, nodes);
    }

    private static final String NODES_036 =
            "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"1\"},"
            + "{\"raw\":\"6\",\"corrected\":\"4\"}]";

    @Test
    void createCurveCertificateReturns201WithNodes() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", NODES_036)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CURVE"))
                .andExpect(jsonPath("$.a").doesNotExist())
                .andExpect(jsonPath("$.nodes.length()").value(3))
                .andExpect(jsonPath("$.nodes[0].raw").value("0"))
                .andExpect(jsonPath("$.nodes[1].corrected").value("1"))
                .andExpect(jsonPath("$.nodes[2].raw").value("6"))
                .andExpect(jsonPath("$.revoked").value(false));
    }

    @Test
    void curveNodeValidationFailures() throws Exception {
        // 仅 2 个节点 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"1\",\"corrected\":\"1\"}]")))
                .andExpect(status().isBadRequest());
        // 9 个节点 → 400
        StringBuilder nine = new StringBuilder("[");
        for (int i = 0; i < 9; i++) {
            nine.append(i == 0 ? "" : ",")
                    .append("{\"raw\":\"").append(i).append("\",\"corrected\":\"0\"}");
        }
        nine.append("]");
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", nine.toString())))
                .andExpect(status().isBadRequest());
        // 原始值不严格递增（相等）→ 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"1\"},"
                                + "{\"raw\":\"3\",\"corrected\":\"2\"}]")))
                .andExpect(status().isBadRequest());
        // 原始值递减 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"6\",\"corrected\":\"1\"},"
                                + "{\"raw\":\"3\",\"corrected\":\"2\"}]")))
                .andExpect(status().isBadRequest());
        // 节点小数位超过 6 位 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"1\"},"
                                + "{\"raw\":\"6.0000001\",\"corrected\":\"2\"}]")))
                .andExpect(status().isBadRequest());
        // 同时提供 nodes 与 a/b → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C2","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0","nodes":%s}
                                """.formatted(NODES_036)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void curveAndLinearOverlapRejectedButAdjacentAllowed() throws Exception {
        // 线性证书 [2026, 2027)
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C3","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}"""))
                .andExpect(status().isCreated());
        // 曲线与线性重叠 → 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C3", "2026-06-01T00:00:00Z", "2027-06-01T00:00:00Z", NODES_036)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));
        // 曲线与线性相邻 → 201
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C3", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z", NODES_036)))
                .andExpect(status().isCreated());
        // 曲线与曲线重叠 → 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C3", "2027-06-01T00:00:00Z", "2028-06-01T00:00:00Z", NODES_036)))
                .andExpect(status().isConflict());
    }

    @Test
    void revokeCurveCertificateThenRecreateSameRange() throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C4", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", NODES_036)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mvc.perform(post("/api/certificates/{id}/revoke", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CURVE"))
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.nodes.length()").value(3));

        // 撤销后同区间可重建曲线证书
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C4", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", NODES_036)))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentCurveAndLinearCreateLeavesNonOverlappingSet() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            boolean curve = i % 2 == 0;
            results.add(pool.submit(() -> {
                start.await();
                String body = curve
                        ? curveJson("INS-C5", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", NODES_036)
                        : """
                          {"instrumentId":"INS-C5","validFrom":"2026-01-01T00:00:00Z",
                           "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}""";
                return mvc.perform(post("/api/certificates")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        for (Future<Integer> future : results) {
            int status = future.get(30, TimeUnit.SECONDS);
            if (status == 201) {
                created.incrementAndGet();
            } else if (status == 409) {
                conflicts.incrementAndGet();
            }
        }
        pool.shutdown();
        assertEquals(1, created.get(), "曲线与线性混合并发创建重叠区间必须恰好一张成功");
        assertEquals(threads - 1, conflicts.get());
        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM calibration_certificate WHERE instrument_id = 'INS-C5' AND revoked = FALSE",
                Integer.class);
        assertEquals(1, active, "最终只能留下不重叠的证书集合");
    }
}
