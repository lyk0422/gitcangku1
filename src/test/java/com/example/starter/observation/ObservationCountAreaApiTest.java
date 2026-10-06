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
 * 计数面积组（individualCount + sampledArea）作为单一合并单元参与三方合并与裁决的测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationCountAreaApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions create(String requestId, String obsId,
                                 Integer count, Integer area) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", obsId);
        body.put("location", "样方A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("individualCount", count);
        }
        if (area != null) {
            body.put("sampledArea", area);
        }
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String obsId, int baseVersion,
                                Integer count, Integer area) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", "样方A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("individualCount", count);
        }
        if (area != null) {
            body.put("sampledArea", area);
        }
        return mockMvc.perform(post("/api/observations/{id}/merge", obsId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String requestId, String resolutionId, String obsId,
                                  int baseVersion, int expectedCurrentVersion,
                                  Integer count, Integer area, String selection) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", "样方A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        if (count != null) {
            body.put("individualCount", count);
        }
        if (area != null) {
            body.put("sampledArea", area);
        }
        body.put("selections", Map.of("countArea", selection));
        body.put("operator", "operator-a");
        return mockMvc.perform(post("/api/observations/{id}/resolve", obsId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String obsId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", obsId));
    }

    private int versionCount(String obsId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, obsId);
        return count == null ? 0 : count;
    }

    // ---------- 创建与组校验 ----------

    @Test
    void createWithCountAreaGroupReturnsAndPersistsFields() throws Exception {
        create("req-c1", "obs-1", 5, 10)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.individualCount").value(5))
                .andExpect(jsonPath("$.sampledArea").value(10));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.individualCount").value(5))
                .andExpect(jsonPath("$.sampledArea").value(10));
        Integer storedCount = jdbcTemplate.queryForObject(
                "SELECT individual_count FROM observation_version WHERE observation_id = 'obs-1' AND version = 1",
                Integer.class);
        assertThat(storedCount).isEqualTo(5);
    }

    @Test
    void createWithPartialOrInvalidGroupReturns400() throws Exception {
        // 只给计数或只给面积：违反成组约束
        create("req-c2", "obs-1", 5, null).andExpect(status().isBadRequest());
        create("req-c3", "obs-1", null, 10).andExpect(status().isBadRequest());
        // 计数为负、面积非正：违反非负/正整数约束
        create("req-c4", "obs-1", -1, 10).andExpect(status().isBadRequest());
        create("req-c5", "obs-1", 5, 0).andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isZero();
    }

    // ---------- 组参与三方合并 ----------

    @Test
    void mergeAcceptsWholeGroupWhenCurrentUnchanged() throws Exception {
        create("req-m1", "obs-1", 1, 3).andExpect(status().isCreated());
        merge("req-m2", "obs-1", 1, 5, 6)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.individualCount").value(5))
                .andExpect(jsonPath("$.sampledArea").value(6));
    }

    @Test
    void mergeDoesNotSpliceGroupFromDifferentSubmissions() throws Exception {
        create("req-m3", "obs-1", 1, 3).andExpect(status().isCreated());
        // 服务端只改计数：(2,3)，版本 2
        merge("req-m4", "obs-1", 1, 2, 3).andExpect(status().isOk());
        // 离线端基于 v1 只改面积：(1,4)。若允许拼组会得到 (2,4)；正确行为是整组冲突
        merge("req-m5", "obs-1", 1, 1, 4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("countArea"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        // 不写入任何部分结果：当前仍是 (2,3)
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.individualCount").value(2))
                .andExpect(jsonPath("$.sampledArea").value(3));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void mergeSameGroupChangeOnBothSidesAddsNoVersion() throws Exception {
        create("req-m6", "obs-1", 1, 3).andExpect(status().isCreated());
        merge("req-m7", "obs-1", 1, 2, 4).andExpect(status().isOk());
        // 离线端基于 v1 也改成 (2,4)：两边相同，接受且与当前相同，不加版本
        merge("req-m8", "obs-1", 1, 2, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.individualCount").value(2))
                .andExpect(jsonPath("$.sampledArea").value(4));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void mergeWithUnchangedGroupKeepsGroup() throws Exception {
        create("req-m9", "obs-1", 1, 3).andExpect(status().isCreated());
        // 只改备注（候选组与基线相同）：组保留当前值，版本正常前进
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-m10");
        body.put("baseVersion", 1);
        body.put("location", "样方A");
        body.put("reading", "1.0");
        body.put("note", "新备注");
        body.put("individualCount", 1);
        body.put("sampledArea", 3);
        mockMvc.perform(post("/api/observations/{id}/merge", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.individualCount").value(1))
                .andExpect(jsonPath("$.sampledArea").value(3));
    }

    // ---------- 组参与显式裁决 ----------

    @Test
    void resolveCountAreaConflictWithCandidateTakesWholeGroup() throws Exception {
        create("req-r1", "obs-1", 1, 3).andExpect(status().isCreated());
        // 服务端改为 (2,3)，版本 2
        merge("req-r2", "obs-1", 1, 2, 3).andExpect(status().isOk());
        // 离线端基于 v1 改为 (9,9)：整组冲突
        merge("req-r3", "obs-1", 1, 9, 9)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("countArea"));

        // 裁决选择候选：整组取 (9,9)，生成版本 3
        resolve("req-r4", "res-1", "obs-1", 1, 2, 9, 9, "CANDIDATE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("countArea"))
                .andExpect(jsonPath("$.individualCount").value(9))
                .andExpect(jsonPath("$.sampledArea").value(9));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.individualCount").value(9))
                .andExpect(jsonPath("$.sampledArea").value(9));
        assertThat(versionCount("obs-1")).isEqualTo(3);
    }

    @Test
    void resolveCountAreaConflictWithCurrentKeepsGroupAndAddsNoVersion() throws Exception {
        create("req-r5", "obs-1", 1, 3).andExpect(status().isCreated());
        merge("req-r6", "obs-1", 1, 2, 3).andExpect(status().isOk());
        merge("req-r7", "obs-1", 1, 9, 9).andExpect(status().isConflict());

        // 裁决选择当前：保留 (2,3)，内容无变化，不加版本
        resolve("req-r8", "res-2", "obs-1", 1, 2, 9, 9, "CURRENT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.individualCount").value(2))
                .andExpect(jsonPath("$.sampledArea").value(3));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.individualCount").value(2))
                .andExpect(jsonPath("$.sampledArea").value(3));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void resolveWithMissingCountAreaSelectionReturns400() throws Exception {
        create("req-r9", "obs-1", 1, 3).andExpect(status().isCreated());
        merge("req-r10", "obs-1", 1, 2, 3).andExpect(status().isOk());
        merge("req-r11", "obs-1", 1, 9, 9).andExpect(status().isConflict());

        // 选择未覆盖重算出的 countArea 冲突字段：400
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", "req-r12");
        body.put("resolutionId", "res-3");
        body.put("baseVersion", 1);
        body.put("expectedCurrentVersion", 2);
        body.put("location", "样方A");
        body.put("reading", "1.0");
        body.put("note", "备注");
        body.put("individualCount", 9);
        body.put("sampledArea", 9);
        body.put("selections", Map.of("location", "CANDIDATE"));
        body.put("operator", "operator-a");
        mockMvc.perform(post("/api/observations/{id}/resolve", "obs-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }
}
