package com.example.starter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 封签异常：破损只追加记录并停止交接、原封签保留、重新封存生成新版本并引用旧异常。
 */
class SealAnomalyTest extends BaseApiTest {

    @BeforeEach
    void seed() throws Exception {
        registerStratum("SITE-T", "L");
        collect("REQ-AN-S1", "A-01", "L", "G-01");
        collect("REQ-AN-S2", "A-02", "L", "G-02");
    }

    private Map<String, Object> sealBody(String requestId, String packageNo, String sealNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("packageNo", packageNo);
        body.put("siteCode", "SITE-T");
        body.put("stratumNo", "L");
        body.put("sealNo", sealNo);
        body.put("sampleNos", List.of("A-01", "A-02"));
        body.put("operator", "合成操作员甲");
        return body;
    }

    private Map<String, Object> anomalyBody(String requestId, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("note", note);
        body.put("operator", "合成巡检员丁");
        return body;
    }

    private Map<String, Object> handoverBody(String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("operator", "合成交接员乙");
        body.put("receiver", "合成实验室丙");
        return body;
    }

    private long sealAndBreak(String packageNo, String sealNo) throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-AN-SEAL-" + packageNo, packageNo, sealNo))
                .andExpect(status().isCreated());
        String body = postJson("/api/packages/" + packageNo + "/anomaly",
                anomalyBody("REQ-AN-BRK-" + packageNo, "封签边缘撕裂"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sealNo").value(sealNo))
                .andExpect(jsonPath("$.status").value("SEAL_BROKEN"))
                .andReturn().getResponse().getContentAsString();
        return ((Number) mapper.readTree(body).get("anomalyId").asLong()).longValue();
    }

    @Test
    void brokenSealStopsHandoverAndKeepsOriginalSeal() throws Exception {
        sealAndBreak("PKG-AN-1", "SEAL-ORIG-1");
        postJson("/api/packages/PKG-AN-1/handover", handoverBody("REQ-AN-HO-1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("封签破损")));
        mvc.perform(get("/api/samples/A-01/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages[0].sealNo").value("SEAL-ORIG-1"))
                .andExpect(jsonPath("$.packages[0].status").value("SEAL_BROKEN"))
                .andExpect(jsonPath("$.packages[0].events[*].eventType", contains("SEALED", "SEAL_BROKEN")))
                .andExpect(jsonPath("$.packages[0].events[*].sealNo", contains("SEAL-ORIG-1", "SEAL-ORIG-1")));
    }

    @Test
    void resealCreatesNewVersionReferencingAnomaly() throws Exception {
        long anomalyId = sealAndBreak("PKG-AN-2", "SEAL-ORIG-2");
        Map<String, Object> reseal = new LinkedHashMap<>();
        reseal.put("requestId", "REQ-AN-RS-1");
        reseal.put("oldPackageNo", "PKG-AN-2");
        reseal.put("packageNo", "PKG-AN-2-V2");
        reseal.put("sealNo", "SEAL-NEW-2");
        reseal.put("operator", "合成操作员甲");
        postJson("/api/packages/reseal", reseal)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.lineageNo").value("PKG-AN-2"))
                .andExpect(jsonPath("$.prevAnomalyId").value(anomalyId))
                .andExpect(jsonPath("$.sampleCount").value(2));
        postJson("/api/packages/PKG-AN-2-V2/handover", handoverBody("REQ-AN-HO-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HANDED_OVER"));
        mvc.perform(get("/api/samples/A-01/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packages.length()").value(2))
                .andExpect(jsonPath("$.packages[0].version").value(1))
                .andExpect(jsonPath("$.packages[1].version").value(2))
                .andExpect(jsonPath("$.packages[1].events[0].eventType").value("RESEALED"));
    }

    @Test
    void resealWithoutBrokenSealReturns422() throws Exception {
        postJson("/api/packages/seal", sealBody("REQ-AN-SEAL-OK", "PKG-AN-3", "SEAL-OK"))
                .andExpect(status().isCreated());
        Map<String, Object> reseal = new LinkedHashMap<>();
        reseal.put("requestId", "REQ-AN-RS-2");
        reseal.put("oldPackageNo", "PKG-AN-3");
        reseal.put("packageNo", "PKG-AN-3-V2");
        reseal.put("sealNo", "SEAL-NEW-3");
        reseal.put("operator", "合成操作员甲");
        postJson("/api/packages/reseal", reseal)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", containsString("封签破损")));
    }
}