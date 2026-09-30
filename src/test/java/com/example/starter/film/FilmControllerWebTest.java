package com.example.starter.film;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层真实 H2 测试：验证状态码（201/422/409/404）、幂等重放与查询结构。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilmControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private String code;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM delivery_receipt");
        jdbc.update("DELETE FROM master_segment");
        jdbc.update("DELETE FROM master");
        jdbc.update("DELETE FROM segment_version");
        jdbc.update("DELETE FROM film");
        code = "W-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void 完整业务链路的HTTP状态与报文() throws Exception {
        // 注册影片（两段）。
        mockMvc.perform(post("/api/films")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", code, "plannedSegments", 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code));

        // 摘要格式错误 -> 422。
        mockMvc.perform(post("/api/films/" + code + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("segmentNo", 1, "contentDigest", "sha256:zz",
                                "requestId", "bad"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 缺段审核 -> 422。
        mockMvc.perform(post("/api/films/" + code + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("segmentNo", 1, "contentDigest", TestData.digest("1"),
                                "requestId", "s1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.versionNo").value(1))
                .andExpect(jsonPath("$.replayed").value(false));

        mockMvc.perform(post("/api/films/" + code + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", "rv-early"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MISSING_SEGMENTS"));

        // 第 2 段提交；基于错误版次 -> 409。
        mockMvc.perform(post("/api/films/" + code + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("segmentNo", 2, "contentDigest", TestData.digest("2"),
                                "basedOnVersion", 5, "requestId", "s2-stale"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(post("/api/films/" + code + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("segmentNo", 2, "contentDigest", TestData.digest("2"),
                                "requestId", "s2"))))
                .andExpect(status().isCreated());

        // 同 requestId 异参 -> 409。
        mockMvc.perform(post("/api/films/" + code + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("segmentNo", 1, "contentDigest", TestData.digest("other"),
                                "requestId", "s1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        // 同 requestId 同参重放 -> 201 + replayed=true。
        mockMvc.perform(post("/api/films/" + code + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("segmentNo", 1, "contentDigest", TestData.digest("1"),
                                "requestId", "s1"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true));

        // 审核通过，取母版编号。
        MvcResult reviewResult = mockMvc.perform(post("/api/films/" + code + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("requestId", "rv"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andReturn();
        JsonNode reviewBody = objectMapper.readTree(reviewResult.getResponse().getContentAsString());
        String masterNo = reviewBody.get("masterNo").asText();

        // 交付 + 重试：同一回执编号。
        MvcResult d1 = mockMvc.perform(post("/api/films/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("masterNo", masterNo, "requestId", "dv"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String receiptNo = objectMapper.readTree(d1.getResponse().getContentAsString())
                .get("receiptNo").asText();

        mockMvc.perform(post("/api/films/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("masterNo", masterNo, "requestId", "dv"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.receiptNo").value(receiptNo));

        // 撤销后新交付 409，原 requestId 仍可重放。
        mockMvc.perform(post("/api/films/masters/" + masterNo + "/revoke"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/films/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("masterNo", masterNo, "requestId", "dv-new"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MASTER_REVOKED"));
        mockMvc.perform(post("/api/films/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("masterNo", masterNo, "requestId", "dv"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo").value(receiptNo));

        // 查询：版次链、母版、回执。
        mockMvc.perform(get("/api/films/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segmentChain.length()").value(2))
                .andExpect(jsonPath("$.masters.length()").value(1))
                .andExpect(jsonPath("$.masters[0].status").value("REVOKED"))
                .andExpect(jsonPath("$.receipts.length()").value(1))
                .andExpect(jsonPath("$.receipts[0].masterNo").value(masterNo));

        // 不存在资源 404。
        mockMvc.perform(get("/api/films/NO-SUCH-FILM"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/films/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("masterNo", "M-NOPE", "requestId", "x"))))
                .andExpect(status().isNotFound());
    }
}
