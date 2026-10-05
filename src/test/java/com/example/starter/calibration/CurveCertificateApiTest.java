package com.example.starter.calibration;

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
 * 曲线证书创建测试：节点数量与递增校验、小数位校验、类型互斥参数、
 * 与线性证书的区间重叠互斥（相邻合法）、撤销后同区间可重建。
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
                {"instrumentId":"%s","validFrom":"%s","validTo":"%s","type":"CURVE","nodes":%s}
                """.formatted(instrument, from, to, nodes);
    }

    private String defaultNodes() {
        return """
                [{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},{"raw":"6","corrected":"4"}]
                """;
    }

    @Test
    void createCurveCertificateReturns201WithNodes() throws Exception {
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C1", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                defaultNodes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.type").value("CURVE"))
                .andExpect(jsonPath("$.nodes.length()").value(3))
                .andExpect(jsonPath("$.nodes[0].raw").value("0"))
                .andExpect(jsonPath("$.nodes[0].corrected").value("0"))
                .andExpect(jsonPath("$.nodes[1].raw").value("3"))
                .andExpect(jsonPath("$.nodes[1].corrected").value("1"))
                .andExpect(jsonPath("$.nodes[2].raw").value("6"))
                .andExpect(jsonPath("$.nodes[2].corrected").value("4"))
                .andExpect(jsonPath("$.revoked").value(false));

        Integer nodeCount = jdbc.queryForObject("SELECT COUNT(*) FROM certificate_node", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(3, nodeCount);
    }

    @Test
    void curveNodeCountMustBeThreeToEight() throws Exception {
        // 2 个节点 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"1\",\"corrected\":\"1\"}]")))
                .andExpect(status().isBadRequest());
        // 9 个节点 → 400
        String nodes9 = nodeArray(9);
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                nodes9)))
                .andExpect(status().isBadRequest());
        // 8 个节点 → 201
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C2", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                nodeArray(8))))
                .andExpect(status().isCreated());
    }

    private String nodeArray(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"raw\":\"").append(i).append("\",\"corrected\":\"0\"}");
        }
        return json.append("]").toString();
    }

    @Test
    void curveNodesMustBeStrictlyIncreasingAndWithinSixDecimals() throws Exception {
        // 原始值非严格递增 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C3", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"1\"},"
                                        + "{\"raw\":\"3\",\"corrected\":\"2\"}]")))
                .andExpect(status().isBadRequest());
        // 原始值递减 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C3", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3\",\"corrected\":\"1\"},"
                                        + "{\"raw\":\"2\",\"corrected\":\"2\"}]")))
                .andExpect(status().isBadRequest());
        // 节点小数位超过 6 位 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C3", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"3.0000001\",\"corrected\":\"1\"},"
                                        + "{\"raw\":\"6\",\"corrected\":\"4\"}]")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void curveRejectsLinearParamsAndLinearRejectsNodes() throws Exception {
        // CURVE 携带 a/b → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C4","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","type":"CURVE","a":"1","b":"0",
                                 "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},
                                          {"raw":"6","corrected":"4"}]}
                                """))
                .andExpect(status().isBadRequest());
        // LINEAR 携带 nodes → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C4","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","type":"LINEAR","a":"1","b":"0",
                                 "nodes":[{"raw":"0","corrected":"0"},{"raw":"3","corrected":"1"},
                                          {"raw":"6","corrected":"4"}]}
                                """))
                .andExpect(status().isBadRequest());
        // 未知类型 → 400
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C4","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","type":"EXP","a":"1","b":"0"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void curveAndLinearOverlapConflictButAdjacentAllowed() throws Exception {
        // 线性证书先占用 2026 全年
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C5","validFrom":"2026-01-01T00:00:00Z",
                                 "validTo":"2027-01-01T00:00:00Z","a":"1","b":"0"}
                                """))
                .andExpect(status().isCreated());
        // 曲线证书与线性证书区间重叠 → 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C5", "2026-06-01T00:00:00Z", "2027-06-01T00:00:00Z",
                                defaultNodes())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CERTIFICATE_OVERLAP"));
        // 曲线与线性相邻（端点相接）→ 201
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C5", "2027-01-01T00:00:00Z", "2028-01-01T00:00:00Z",
                                defaultNodes())))
                .andExpect(status().isCreated());
        // 线性再与曲线重叠 → 409
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instrumentId":"INS-C5","validFrom":"2027-06-01T00:00:00Z",
                                 "validTo":"2028-06-01T00:00:00Z","a":"2","b":"0"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void revokedCurveCertificateAllowsRecreateAndQueryReturnsNodes() throws Exception {
        String body = mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C6", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                defaultNodes())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.id")).longValue();

        mvc.perform(get("/api/certificates/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CURVE"))
                .andExpect(jsonPath("$.nodes.length()").value(3));

        mvc.perform(post("/api/certificates/{id}/revoke", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true))
                .andExpect(jsonPath("$.nodes.length()").value(3));

        // 撤销后同区间可重建曲线证书
        mvc.perform(post("/api/certificates").contentType(MediaType.APPLICATION_JSON)
                        .content(curveJson("INS-C6", "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z",
                                "[{\"raw\":\"0\",\"corrected\":\"0\"},{\"raw\":\"5\",\"corrected\":\"2\"},"
                                        + "{\"raw\":\"9\",\"corrected\":\"9\"}]")))
                .andExpect(status().isCreated());
    }
}
