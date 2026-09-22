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
 * 冲突解决提交与人工选择记录的 API 测试（真实 H2 内存库，MySQL 兼容模式）：
 * 覆盖冲突重算、选择校验、无变化解决、双重幂等与解决历史查询。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationResolutionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_resolution");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
    }

    private ResultActions create(String requestId, String observationId,
                                 String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion,
                                String location, String reading, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
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

    private ResultActions resolve(String resolutionId, String requestId, String observationId,
                                  int baseVersion, int expectedCurrentVersion,
                                  String location, String reading, String note,
                                  Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resolutionId", resolutionId);
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private ResultActions getResolution(String resolutionId) throws Exception {
        return mockMvc.perform(get("/api/observations/resolutions/{rid}", resolutionId));
    }

    private ResultActions listResolutions(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/resolutions", observationId));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int resolutionCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_resolution", Integer.class);
        return count == null ? 0 : count;
    }

    private static Map<String, String> selections(String field, String choice) {
        Map<String, String> selections = new LinkedHashMap<>();
        selections.put(field, choice);
        return selections;
    }

    private static Map<String, String> selections(String field1, String choice1, String field2, String choice2) {
        Map<String, String> selections = selections(field1, choice1);
        selections.put(field2, choice2);
        return selections;
    }

    // ---------- 主流程 ----------

    @Test
    void resolveAfterConflictCreatesNewVersionAndImmutableRecord() throws Exception {
        create("req-r1", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r2", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 普通合并冲突：离线端基于版本 1 把地点改为站点C
        merge("req-r3", "obs-1", 1, "站点C", "1.0", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        resolve("res-1", "req-r4", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionId").value("res-1"))
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.baseVersion").value(1))
                .andExpect(jsonPath("$.previousVersion").value(2))
                .andExpect(jsonPath("$.resultVersion").value(3))
                .andExpect(jsonPath("$.versionCreated").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.selections.location").value("CANDIDATE"))
                .andExpect(jsonPath("$.operator").value("巡检员甲"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty());

        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));
        assertThat(versionCount("obs-1")).isEqualTo(3);
        assertThat(resolutionCount()).isEqualTo(1);

        // 解决记录可按 resolutionId 与 observationId 查询，内容与解决响应一致
        getResolution("res-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationId").value("obs-1"))
                .andExpect(jsonPath("$.resultVersion").value(3))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.selections.location").value("CANDIDATE"));
        listResolutions("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resolutionId").value("res-1"));
    }

    @Test
    void resolveRecomputesConflictsAndAutoMergesNonConflictFields() throws Exception {
        create("req-r10", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r11", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 候选：地点冲突；备注服务端未改应自动合并；读数 1.00 与基线 1.0 数值相等视为未改
        resolve("res-2", "req-r12", "obs-1", 1, 2, "站点C", "1.00", "离线备注",
                selections("location", "CANDIDATE"), "巡检员乙")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflictFields.length()").value(1))
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.resultVersion").value(3));

        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.reading").value("1.0"))
                .andExpect(jsonPath("$.note").value("离线备注"));
    }

    @Test
    void resolveMultipleConflictsAppliesPerFieldChoices() throws Exception {
        create("req-r20", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r21", "obs-1", 1, "站点B", "2.0", "备注").andExpect(status().isOk());
        // 地点与读数均冲突：地点选 CURRENT（保留站点B），读数选 CANDIDATE（取 3.0）；备注自动合并
        resolve("res-3", "req-r22", "obs-1", 1, 2, "站点C", "3.0", "新备注",
                selections("location", "CURRENT", "reading", "CANDIDATE"), "巡检员丙")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conflictFields[0]").value("location"))
                .andExpect(jsonPath("$.conflictFields[1]").value("reading"))
                .andExpect(jsonPath("$.selections.location").value("CURRENT"))
                .andExpect(jsonPath("$.selections.reading").value("CANDIDATE"));

        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点B"))
                .andExpect(jsonPath("$.reading").value("3.0"))
                .andExpect(jsonPath("$.note").value("新备注"));
    }

    // ---------- 选择校验 ----------

    @Test
    void resolveWithSelectionOnNonConflictFieldReturns400() throws Exception {
        create("req-r30", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r31", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 备注未冲突（服务端未改），不得对其选择
        resolve("res-4", "req-r32", "obs-1", 1, 2, "站点C", "1.0", "离线备注",
                selections("location", "CANDIDATE", "note", "CURRENT"), "巡检员甲")
                .andExpect(status().isBadRequest());
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
        assertThat(resolutionCount()).isZero();
    }

    @Test
    void resolveWithMissingSelectionReturns400() throws Exception {
        create("req-r40", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r41", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-5", "req-r42", "obs-1", 1, 2, "站点C", "1.0", "备注",
                Map.of(), "巡检员甲")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isEqualTo(2);
        assertThat(resolutionCount()).isZero();
    }

    @Test
    void resolveWithBaseChoiceReturns400() throws Exception {
        create("req-r50", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r51", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-6", "req-r52", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "BASE"), "巡检员甲")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isEqualTo(2);
        assertThat(resolutionCount()).isZero();
    }

    @Test
    void resolveWithUnknownFieldReturns400() throws Exception {
        create("req-r60", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r61", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-7", "req-r62", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE", "temperature", "CURRENT"), "巡检员甲")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isEqualTo(2);
        assertThat(resolutionCount()).isZero();
    }

    @Test
    void failedResolveDoesNotOccupyRequestIdOrResolutionId() throws Exception {
        create("req-r70", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r71", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 选择校验失败：requestId 与 resolutionId 均不占键
        resolve("res-8", "req-r72", "obs-1", 1, 2, "站点C", "1.0", "备注",
                Map.of(), "巡检员甲")
                .andExpect(status().isBadRequest());
        resolve("res-8", "req-r72", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(3));
        assertThat(resolutionCount()).isEqualTo(1);
    }

    // ---------- 版本与存在性 ----------

    @Test
    void resolveWithMismatchedExpectedCurrentVersionReturns409() throws Exception {
        create("req-r80", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r81", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-9", "req-r82", "obs-1", 1, 9, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(versionCount("obs-1")).isEqualTo(2);
        assertThat(resolutionCount()).isZero();
    }

    @Test
    void resolveWithMissingBaseVersionReturns404() throws Exception {
        create("req-r90", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        resolve("res-10", "req-r91", "obs-1", 9, 1, "站点B", "1.0", "备注",
                Map.of(), "巡检员甲")
                .andExpect(status().isNotFound());
        assertThat(resolutionCount()).isZero();
    }

    @Test
    void resolveOnMissingObservationReturns404() throws Exception {
        resolve("res-11", "req-r100", "obs-x", 1, 1, "站点B", "1.0", "备注",
                Map.of(), "巡检员甲")
                .andExpect(status().isNotFound());
    }

    @Test
    void resolveOnDeletedObservationReturns410() throws Exception {
        create("req-r110", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        delete("req-r111", "obs-1", 1).andExpect(status().isOk());
        resolve("res-12", "req-r112", "obs-1", 1, 2, "站点B", "1.0", "备注",
                Map.of(), "巡检员甲")
                .andExpect(status().isGone());
        getCurrent("obs-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(resolutionCount()).isZero();
    }

    // ---------- 无变化解决 ----------

    @Test
    void resolveWithNoChangeStoresRecordWithoutNewVersion() throws Exception {
        create("req-r120", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r121", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        // 冲突字段选择 CURRENT：解决后内容与当前完全相同，不加版本但仍保存解决记录
        resolve("res-13", "req-r122", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CURRENT"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionCreated").value(false))
                .andExpect(jsonPath("$.previousVersion").value(2))
                .andExpect(jsonPath("$.resultVersion").value(2));

        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.location").value("站点B"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
        getResolution("res-13")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(2))
                .andExpect(jsonPath("$.versionCreated").value(false));
    }

    // ---------- 双重幂等 ----------

    @Test
    void sameResolutionIdAndParamsReplaysOriginalResult() throws Exception {
        create("req-r130", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r131", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-14", "req-r132", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(3));
        // 同一 resolutionId、相同参数、新 requestId 重放：返回原结果，不产生新版本与新记录
        resolve("res-14", "req-r133", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(3))
                .andExpect(jsonPath("$.versionCreated").value(true));
        assertThat(versionCount("obs-1")).isEqualTo(3);
        assertThat(resolutionCount()).isEqualTo(1);
    }

    @Test
    void sameResolutionIdWithDifferentParamsReturns409() throws Exception {
        create("req-r140", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r141", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-15", "req-r142", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk());
        // 同一 resolutionId 改参（候选值不同）：409
        resolve("res-15", "req-r143", "obs-1", 1, 2, "站点D", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isConflict());
        // 同一 resolutionId 改参（选择不同）：409
        resolve("res-15", "req-r144", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CURRENT"), "巡检员甲")
                .andExpect(status().isConflict());
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.location").value("站点C"));
        assertThat(resolutionCount()).isEqualTo(1);
    }

    @Test
    void resolveRequestIdReplayAndDifferentParams() throws Exception {
        create("req-r150", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r151", "obs-1", 1, "站点B", "1.0", "备注").andExpect(status().isOk());
        resolve("res-16", "req-r152", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(3));
        // 同 requestId 同参重放：返回原结果
        resolve("res-16", "req-r152", "obs-1", 1, 2, "站点C", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultVersion").value(3));
        // 同 requestId 异参：409
        resolve("res-17", "req-r152", "obs-1", 1, 2, "站点D", "1.0", "备注",
                selections("location", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-1")).isEqualTo(3);
        assertThat(resolutionCount()).isEqualTo(1);
    }

    // ---------- 解决历史查询 ----------

    @Test
    void resolutionHistoryQueries() throws Exception {
        getResolution("res-missing").andExpect(status().isNotFound());
        listResolutions("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        create("req-r160", "obs-1", "站点A", "1.0", "备注").andExpect(status().isCreated());
        merge("req-r161", "obs-1", 1, "站点B", "2.0", "备注").andExpect(status().isOk());
        resolve("res-20", "req-r162", "obs-1", 1, 2, "站点C", "3.0", "备注",
                selections("location", "CANDIDATE", "reading", "CANDIDATE"), "巡检员甲")
                .andExpect(status().isOk());
        merge("req-r163", "obs-1", 3, "站点D", "3.0", "备注").andExpect(status().isOk());
        resolve("res-21", "req-r164", "obs-1", 3, 4, "站点E", "3.0", "备注",
                selections("location", "CURRENT"), "巡检员乙")
                .andExpect(status().isOk());

        listResolutions("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].resolutionId").value("res-20"))
                .andExpect(jsonPath("$[0].conflictFields.length()").value(2))
                .andExpect(jsonPath("$[1].resolutionId").value("res-21"))
                .andExpect(jsonPath("$[1].operator").value("巡检员乙"));
        // 历史版本查询保持兼容：解决生成的版本可正常读取
        mockMvc.perform(get("/api/observations/{id}/versions/{version}", "obs-1", 3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.location").value("站点C"))
                .andExpect(jsonPath("$.reading").value("3.0"));
    }
}
