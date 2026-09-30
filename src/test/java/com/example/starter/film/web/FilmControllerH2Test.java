package com.example.starter.film.web;

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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端 HTTP 入口测试（真实 H2 + MockMvc）：覆盖 201/200、422、409、404 状态码与主流程。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FilmControllerH2Test {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE TABLE delivery_receipt");
        jdbc.execute("TRUNCATE TABLE master_segment");
        jdbc.execute("TRUNCATE TABLE master");
        jdbc.execute("TRUNCATE TABLE review_segment_baseline");
        jdbc.execute("TRUNCATE TABLE review_request");
        jdbc.execute("TRUNCATE TABLE segment_version");
        jdbc.execute("TRUNCATE TABLE idempotent_request");
        jdbc.execute("TRUNCATE TABLE film");
    }

    private long createFilm(String code, int planned) throws Exception {
        Map<String, Object> body = Map.of(
                "code", code,
                "title", "合成影片",
                "plannedSegments", planned
        );
        String resp = mvc.perform(post("/api/films")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return om.readTree(resp).get("id").asLong();
    }

    private void submit(long filmId, int seq, String digest, int base, String requestId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("seq", seq);
        body.put("digest", digest);
        body.put("baseVersion", base);
        body.put("requestId", requestId);
        mvc.perform(post("/api/films/" + filmId + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void httpMainFlow_andStatusCodeMapping() throws Exception {
        String code = "F-" + UUID.randomUUID().toString().substring(0, 8);
        long filmId = createFilm(code, 2);

        // 摘要格式错误 → 422
        Map<String, Object> badDigest = Map.of(
                "seq", 1, "digest", "含非法字符!", "baseVersion", 0,
                "requestId", "bad-digest");
        mvc.perform(post("/api/films/" + filmId + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(badDigest)))
                .andExpect(status().isUnprocessableEntity());

        submit(filmId, 1, "seg1-v1", 0, "s1-" + UUID.randomUUID());

        // 缺段审核 → 422
        mvc.perform(post("/api/films/" + filmId + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "rv-missing"))))
                .andExpect(status().isUnprocessableEntity());

        submit(filmId, 2, "seg2-v1", 0, "s2-" + UUID.randomUUID());

        // 基于旧版次提交 → 409
        Map<String, Object> stale = Map.of(
                "seq", 1, "digest", "seg1-v2", "baseVersion", 0,
                "requestId", "stale-" + UUID.randomUUID());
        mvc.perform(post("/api/films/" + filmId + "/segments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(stale)))
                .andExpect(status().isConflict());

        // 正常审核
        String reviewResp = mvc.perform(post("/api/films/" + filmId + "/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("requestId", "rv-ok"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long reviewId = om.readTree(reviewResp).get("id").asLong();

        // 通过
        String approveResp = mvc.perform(
                        post("/api/films/" + filmId + "/reviews/" + reviewId + "/approve")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(om.writeValueAsString(Map.of("requestId", "ap-ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.status").value("APPROVED"))
                .andExpect(jsonPath("$.master.masterNo").value("M-" + code + "-" + reviewId))
                .andReturn().getResponse().getContentAsString();
        JsonNode masterNode = om.readTree(approveResp).get("master");
        String masterNo = masterNode.get("masterNo").asText();

        // 交付
        mvc.perform(post("/api/films/" + filmId + "/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "masterNo", masterNo, "requestId", "dl-ok"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshot").value("1:1:seg1-v1|2:1:seg2-v1"));

        // 交付重试同参 → 原回执（200 幂等返回，不新增）
        mvc.perform(post("/api/films/" + filmId + "/deliveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of(
                                "masterNo", masterNo, "requestId", "dl-ok"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshot").value("1:1:seg1-v1|2:1:seg2-v1"));

        // 查询聚合
        mvc.perform(get("/api/films/" + filmId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.reviews[0].baselines.length()").value(2))
                .andExpect(jsonPath("$.masters[0].segments.length()").value(2))
                .andExpect(jsonPath("$.deliveries.length()").value(1));

        // 不存在影片 → 404
        mvc.perform(get("/api/films/999999")).andExpect(status().isNotFound());
    }
}
