package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 圆弧观测 API 测试：创建/整组三方合并/删除墓碑/类型不匹配/幂等边界（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ArcObservationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM arc_adjudication");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions createArc(String requestId, String observationId,
                                    int startAngle, int arcLength, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("startAngle", startAngle);
        body.put("arcLength", arcLength);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/arcs")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergeArc(String requestId, String observationId, int baseVersion,
                                   int startAngle, int arcLength, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("startAngle", startAngle);
        body.put("arcLength", arcLength);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/arcs/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions createPlain(String requestId, String observationId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", "站点A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions mergePlain(String requestId, String observationId, int baseVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "站点B");
        body.put("reading", "2.0");
        body.put("note", "备注");
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions delete(String requestId, String observationId, int expectedVersion) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("expectedVersion", expectedVersion);
        return mockMvc.perform(post("/api/observations/{id}/delete", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    // ---------- 创建 ----------

    @Test
    void createArcReturnsVersionOne() throws Exception {
        createArc("req-a1", "arc-1", 350000, 20000, "operator-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.observationId").value("arc-1"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.deleted").value(false))
                .andExpect(jsonPath("$.obsType").value("ARC"))
                .andExpect(jsonPath("$.startAngle").value(350000))
                .andExpect(jsonPath("$.arcLength").value(20000))
                .andExpect(jsonPath("$.location").doesNotExist());

        getCurrent("arc-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.obsType").value("ARC"))
                .andExpect(jsonPath("$.startAngle").value(350000))
                .andExpect(jsonPath("$.arcLength").value(20000));
        assertThat(versionCount("arc-1")).isEqualTo(1);
    }

    @Test
    void createArcWithOutOfRangeAngleReturns400() throws Exception {
        createArc("req-a2", "arc-1", 360000, 1000, "operator-1").andExpect(status().isBadRequest());
        createArc("req-a3", "arc-1", -1, 1000, "operator-1").andExpect(status().isBadRequest());
        createArc("req-a4", "arc-1", 0, 0, "operator-1").andExpect(status().isBadRequest());
        createArc("req-a5", "arc-1", 0, 180001, "operator-1").andExpect(status().isBadRequest());
        assertThat(versionCount("arc-1")).isZero();
    }

    @Test
    void createArcWithBlankOperatorReturns400() throws Exception {
        createArc("req-a6", "arc-1", 1000, 1000, "  ").andExpect(status().isBadRequest());
        assertThat(versionCount("arc-1")).isZero();
    }

    @Test
    void createArcDuplicateIdReturns409AcrossTypes() throws Exception {
        createArc("req-a7", "arc-1", 1000, 1000, "operator-1").andExpect(status().isCreated());
        createArc("req-a8", "arc-1", 2000, 1000, "operator-1").andExpect(status().isConflict());
        createPlain("req-a9", "plain-1").andExpect(status().isCreated());
        createArc("req-a10", "plain-1", 1000, 1000, "operator-1").andExpect(status().isConflict());
        assertThat(versionCount("arc-1")).isEqualTo(1);
        assertThat(versionCount("plain-1")).isEqualTo(1);
    }

    // ---------- 整组三方合并 ----------

    @Test
    void arcMergeAcceptsCandidateGroupWhenCurrentUnchanged() throws Exception {
        createArc("req-m1", "arc-1", 1000, 500, "operator-1").andExpect(status().isCreated());
        mergeArc("req-m2", "arc-1", 1, 2000, 800, "operator-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.startAngle").value(2000))
                .andExpect(jsonPath("$.arcLength").value(800));
        assertThat(versionCount("arc-1")).isEqualTo(2);
    }

    @Test
    void arcMergeConflictDoesNotMixStartAngleWithOtherArcLength() throws Exception {
        createArc("req-m3", "arc-1", 1000, 500, "operator-1").andExpect(status().isCreated());
        // 服务端把弧长改为 600（起始角未改）
        mergeArc("req-m4", "arc-1", 1, 1000, 600, "operator-1").andExpect(status().isOk());
        // 离线端基于 v1 把起始角改为 2000（弧长未改）：整组冲突，不能拼成 (2000, 600)
        mergeArc("req-m5", "arc-1", 1, 2000, 500, "operator-2")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("arc"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("arc-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.startAngle").value(1000))
                .andExpect(jsonPath("$.arcLength").value(600));
        assertThat(versionCount("arc-1")).isEqualTo(2);
    }

    @Test
    void arcMergeSameGroupAsCurrentDoesNotAddVersion() throws Exception {
        createArc("req-m6", "arc-1", 1000, 500, "operator-1").andExpect(status().isCreated());
        mergeArc("req-m7", "arc-1", 1, 1000, 500, "operator-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("arc-1")).isEqualTo(1);
    }

    // ---------- 类型不匹配与墓碑 ----------

    @Test
    void plainMergeOnArcObservationReturns422() throws Exception {
        createArc("req-t1", "arc-1", 1000, 500, "operator-1").andExpect(status().isCreated());
        mergePlain("req-t2", "arc-1", 1).andExpect(status().isUnprocessableEntity());
        getCurrent("arc-1").andExpect(jsonPath("$.startAngle").value(1000));
    }

    @Test
    void arcMergeOnPlainObservationReturns422() throws Exception {
        createPlain("req-t3", "plain-1").andExpect(status().isCreated());
        mergeArc("req-t4", "plain-1", 1, 1000, 500, "operator-1")
                .andExpect(status().isUnprocessableEntity());
        getCurrent("plain-1").andExpect(jsonPath("$.location").value("站点A"));
    }

    @Test
    void deleteArcCreatesTombstone() throws Exception {
        createArc("req-t5", "arc-1", 1000, 500, "operator-1").andExpect(status().isCreated());
        delete("req-t6", "arc-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.startAngle").doesNotExist());
        mergeArc("req-t7", "arc-1", 1, 2000, 500, "operator-1").andExpect(status().isGone());
        assertThat(versionCount("arc-1")).isEqualTo(2);
    }

    // ---------- 幂等 ----------

    @Test
    void arcIdempotencyReplayAndConflict() throws Exception {
        createArc("req-i1", "arc-1", 1000, 500, "operator-1").andExpect(status().isCreated());
        createArc("req-i1", "arc-1", 1000, 500, "operator-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1));
        assertThat(versionCount("arc-1")).isEqualTo(1);
        // 同键异参：409
        createArc("req-i1", "arc-1", 2000, 500, "operator-1").andExpect(status().isConflict());
        createArc("req-i1", "arc-2", 1000, 500, "operator-1").andExpect(status().isConflict());
        // 失败不占键：基线版本不存在 404 后，同一 requestId 换合法参数正常执行
        mergeArc("req-i2", "arc-1", 9, 2000, 500, "operator-1").andExpect(status().isNotFound());
        mergeArc("req-i2", "arc-1", 1, 2000, 500, "operator-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }
}
