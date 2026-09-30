package com.example.starter.salinity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 入口测试（真实 Spring MVC + H2）：验证 201/重放 200/409/422/404 状态码与题干示例流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalinityControllerTest {

    private static final String COLLECTED = "2026-09-30T08:00:00Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private Map<String, Object> sampleBody(String voyage, String station, int depth, String salinity,
                                           String sampleNo, String requestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("voyageCode", voyage);
        body.put("station", station);
        body.put("depthM", depth);
        body.put("salinityPsu", new BigDecimal(salinity));
        body.put("collectedAtUtc", Instant.parse(COLLECTED).toString());
        body.put("sampleNo", sampleNo);
        body.put("requestId", requestId);
        return body;
    }

    private String json(Map<String, Object> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("示例：站位 S 缺 50 米封存 422；补齐采纳后封存成功并按 0/50/100 输出")
    void missingDepthThenCompleteExample() throws Exception {
        String voyage = "EX-" + UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(sampleBody(voyage, "S", 0, "35.000", "EX-0", "EX-R-0"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(sampleBody(voyage, "S", 100, "34.000", "EX-100", "EX-R-100"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/voyages/" + voyage + "/adoptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voyageCode":"%s","sampleNo":"EX-0","requestId":"EX-A-0"}""".formatted(voyage)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/voyages/" + voyage + "/adoptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voyageCode":"%s","sampleNo":"EX-100","requestId":"EX-A-100"}""".formatted(voyage)))
                .andExpect(status().isOk());

        // 缺 50 米：封存 422。
        mockMvc.perform(post("/api/voyages/" + voyage + "/seal"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("UNPROCESSABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("缺 50 米")));

        // 补齐 50 米有效样本并采纳。
        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(sampleBody(voyage, "S", 50, "34.500", "EX-50", "EX-R-50"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/voyages/" + voyage + "/adoptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"voyageCode":"%s","sampleNo":"EX-50","requestId":"EX-A-50"}""".formatted(voyage)))
                .andExpect(status().isOk());

        // 封存成功：固定 0、50、100 顺序。
        mockMvc.perform(post("/api/voyages/" + voyage + "/seal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealedProfile[0].depthM").value(0))
                .andExpect(jsonPath("$.sealedProfile[1].depthM").value(50))
                .andExpect(jsonPath("$.sealedProfile[2].depthM").value(100))
                .andExpect(jsonPath("$.sealedProfile[0].salinityPsu").value(35.000))
                .andExpect(jsonPath("$.sealedProfile[2].salinityPsu").value(34.000));

        // 查询返回原始样本、采纳版本、冻结剖面。
        mockMvc.perform(get("/api/voyages/" + voyage))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sealed").value(true))
                .andExpect(jsonPath("$.rawSamples.length()").value(3))
                .andExpect(jsonPath("$.adoptedVersions.length()").value(3))
                .andExpect(jsonPath("$.sealedProfile.length()").value(3));
    }

    @Test
    @DisplayName("同参重放返回 200 与同一 id；编号相同内容不同返回 409")
    void replay200AndConflict409() throws Exception {
        String voyage = "RP-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = sampleBody(voyage, "S", 0, "35.000", "RP-0", "RP-R-0");

        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleNo").value("RP-0"));

        Map<String, Object> conflict = sampleBody(voyage, "S", 0, "36.000", "RP-0", "RP-R-1");
        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON).content(json(conflict)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("请求体越界由 Bean Validation 拦截返回 422")
    void invalidBody422() throws Exception {
        String voyage = "IV-" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(sampleBody(voyage, "S", 600, "35.000", "IV-0", "IV-R-0"))))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("查询不存在航次返回 404")
    void notFound404() throws Exception {
        mockMvc.perform(get("/api/voyages/NO-SUCH-VOYAGE-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("封存后追加勘误 201，原剖面仍可查")
    void correctionAfterSeal() throws Exception {
        String voyage = "CR-" + UUID.randomUUID().toString().substring(0, 8);
        for (int[] d : new int[][]{{0, 0}, {50, 1}, {100, 2}}) {
            mockMvc.perform(post("/api/voyages/" + voyage + "/samples")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(sampleBody(voyage, "S", d[0],
                                    d[0] == 0 ? "35.000" : d[0] == 50 ? "34.500" : "34.000",
                                    "CR-" + d[0], "CR-R-" + d[0]))))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/voyages/" + voyage + "/adoptions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"voyageCode":"%s","sampleNo":"CR-%d","requestId":"CR-A-%d"}"""
                                    .formatted(voyage, d[0], d[0])))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/voyages/" + voyage + "/seal")).andExpect(status().isOk());

        mockMvc.perform(post("/api/voyages/" + voyage + "/corrections")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"station":"S","depthM":0,"correctedSalinityPsu":35.100,
                                 "note":"实验室复核修正","requestId":"CR-CORR-0"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.note").value("实验室复核修正"));

        mockMvc.perform(get("/api/voyages/" + voyage))
                .andExpect(jsonPath("$.corrections.length()").value(1))
                .andExpect(jsonPath("$.sealedProfile[0].salinityPsu").value(35.000));
    }
}
