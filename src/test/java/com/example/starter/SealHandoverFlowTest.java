package com.example.starter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封存与交接：20 件整包交接、第 21 件整包 422、跨层位/未采集/重复编号拒绝、交接锁定。
 */
class SealHandoverFlowTest extends BaseApiTest {

    @BeforeEach
    void seedStrataAndSamples() throws Exception {
        registerStratum("SITE-T", "L");
        registerStratum("SITE-T", "M");
        for (int i = 1; i <= 21; i++) {
            collect("REQ-SEED-L" + i, String.format("L-%02d", i), "L", "G-" + String.format("%02d", i));
        }
        collect("REQ-SEED-M1", "M-01", "M", "G-01");
    }

    private Map<String, Object> sealBody(String requestId, String packageNo, String stratumNo, List<String> sampleNos) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("packageNo", packageNo);
        body.put("siteCode", "SITE-T");
        body.put("stratumNo", stratumNo);
        body.put("sealNo", "SEAL-" + packageNo);
        body.put("sampleNos", sampleNos);
        body.put("operator", "合成操作员甲");
        return body;
    }

    private static List<String> range(String prefix, int from, int to) {
        List<String> nos = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            nos.add(prefix + String.format("-%02d", i));
        }
        return nos;
    }

    private Map<String, Object> handoverBody(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "合成交接员乙");
        body.put("receiver", "合成实验室丙");
        return body;
    }

    @Test
    void sealTwentySamplesThenHandoverAndQueryContext() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-1", "PKG-L-1", "L", range("L", 1, 20)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.sampleCount").value(20))
                .andExpect(jsonPath("$.status").value("SEALED"));
        postJson("/api/packages/PKG-L-1/handover", handoverBody("REQ-HO-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.sealNo").value("SEAL-PKG-L-1"));
        mvc.perform(get("/api/samples/L-01/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"))
                .andExpect(jsonPath("$.packages[0].version").value(1))
                .andExpect(jsonPath("$.packages[0].sealNo").value("SEAL-PKG-L-1"))
                .andExpect(jsonPath("$.packages[0].events[*].eventType",
                        containsInAnyOrder("SEALED", "HANDED_OVER")));
    }

    @Test
    void twentyFirstSampleRejectsWholePackage() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-21", "PKG-L-21", "L", range("L", 1, 21)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("20")));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM seal_package WHERE package_no='PKG-L-21'")
                .query(Long.class).single(), "整包失败不得留下封存包");
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM request_log WHERE request_id='REQ-SEAL-21'")
                .query(Long.class).single(), "失败事务不得消耗请求键");
        mvc.perform(get("/api/samples/L-21/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COLLECTED"))
                .andExpect(jsonPath("$.packages.length()").value(0));
    }

    @Test
    void crossStratumMixReturns422() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-X", "PKG-X", "L", List.of("L-01", "M-01")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("跨层位")));
    }

    @Test
    void uncollectedSampleInPackageReturns422() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-G", "PKG-G", "L", List.of("L-01", "GHOST-1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("未采集")));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM seal_package WHERE package_no='PKG-G'")
                .query(Long.class).single());
    }

    @Test
    void duplicatePackageNoReturns409() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-D1", "PKG-D", "L", List.of("L-01")))
                .andExpect(status().isCreated());
        postJson("/api/packages/seal", sealBody("REQ-SEAL-D2", "PKG-D", "L", List.of("L-02")))
                .andExpect(status().isConflict());
    }

    @Test
    void sampleAlreadySealedReturns409() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-E1", "PKG-E1", "L", List.of("L-01")))
                .andExpect(status().isCreated());
        postJson("/api/packages/seal", sealBody("REQ-SEAL-E2", "PKG-E2", "L", List.of("L-01", "L-02")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", containsString("占用")));
        assertEquals(0, jdbc.sql("SELECT COUNT(*) FROM seal_package WHERE package_no='PKG-E2'")
                .query(Long.class).single(), "失败事务不得消耗样本归属");
    }

    @Test
    void handoverTwiceReturns409AndMissingPackage422() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-SEAL-F1", "PKG-F", "L", List.of("L-01")))
                .andExpect(status().isCreated());
        postJson("/api/packages/PKG-F/handover", handoverBody("REQ-HO-F1")).andExpect(status().isOk());
        postJson("/api/packages/PKG-F/handover", handoverBody("REQ-HO-F2")).andExpect(status().isConflict());
        postJson("/api/packages/PKG-NONE/handover", handoverBody("REQ-HO-F3"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sealSameRequestIdReplaysAndDifferentParams409() throws Exception {
        Map<String, Object> body = sealBody("REQ-SEAL-R1", "PKG-R", "L", List.of("L-01"));
        String first = postJson("/api/packages/seal", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postJson("/api/packages/seal", body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals(1, countRows("seal_package"));
        Map<String, Object> changed = sealBody("REQ-SEAL-R1", "PKG-R2", "L", List.of("L-02"));
        postJson("/api/packages/seal", changed).andExpect(status().isConflict());
    }
}