package com.example.starter.observation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 观测更正附录 API 测试：主流程、未解决簇 422、删字段/覆盖拒绝、requestId 幂等与换字段 409、
 * 前置版本冲突、最新裁决约束、按版本读取原始值/有效值与旧版本不漂移（真实 H2 内存库，MySQL 兼容模式）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CorrigendumApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-01T08:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private Clock clock;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM corrigendum_appendix");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
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

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion,
                                  String location, String reading, String note,
                                  Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
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

    private ResultActions appendix(String requestId, String appendixId, String observationId,
                                   String resolutionId, int expectedAppendixVersion,
                                   String fieldName, String newValue, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("appendixId", appendixId);
        body.put("resolutionId", resolutionId);
        body.put("expectedAppendixVersion", expectedAppendixVersion);
        body.put("fieldName", fieldName);
        body.put("newValue", newValue);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/corrigenda", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions versioned(String observationId, int version) throws Exception {
        return mockMvc.perform(
                get("/api/observations/{id}/corrigenda/versions/{version}", observationId, version));
    }

    private int appendixCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM corrigendum_appendix WHERE observation_id = ?",
                Integer.class, observationId);
        return count == null ? 0 : count;
    }

    /**
     * 题干场景：簇 C 原始读数 10，地点冲突经裁决解决（读数保持 10），返回 resolutionId。
     */
    private String setupResolvedClusterWithReading10(String observationId) throws Exception {
        create("req-create-" + observationId, observationId, "站点A", "10", "备注")
                .andExpect(status().isCreated());
        merge("req-server-" + observationId, observationId, 1, "站点B", "10", "备注")
                .andExpect(status().isOk());
        merge("req-offline-" + observationId, observationId, 1, "站点C", "10", "备注")
                .andExpect(status().isConflict());
        String resolutionId = "res-" + observationId;
        resolve("req-resolve-" + observationId, resolutionId, observationId, 1, 2,
                "站点C", "10", "备注", Map.of("location", "CURRENT"), "reviewer-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("10"));
        return resolutionId;
    }

    // ---------- 主流程：题干场景 ----------

    @Test
    void resolvedClusterAppendixCorrectsReadingAndV0StaysOriginal() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-c");

        // V1 将读数 10 更正为 11
        appendix("req-ap-1", "ap-1", "obs-c", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixId").value("ap-1"))
                .andExpect(jsonPath("$.observationId").value("obs-c"))
                .andExpect(jsonPath("$.requestId").value("req-ap-1"))
                .andExpect(jsonPath("$.appendixVersion").value(1))
                .andExpect(jsonPath("$.previousAppendixVersion").doesNotExist())
                .andExpect(jsonPath("$.resolutionId").value(resolutionId))
                .andExpect(jsonPath("$.snapshotVersion").value(2))
                .andExpect(jsonPath("$.fieldName").value("reading"))
                .andExpect(jsonPath("$.oldValue").value("10"))
                .andExpect(jsonPath("$.newValue").value("11"))
                .andExpect(jsonPath("$.operator").value("recorder-a"))
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-10-01T08:30:00Z"))
                .andExpect(jsonPath("$.effectiveReading").value("11"))
                .andExpect(jsonPath("$.effectiveLocation").value("站点B"));

        // 读取 V0 仍为 10：原始值与当前有效值同时可见
        versioned("obs-c", 0)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.resolutionId").value(resolutionId))
                .andExpect(jsonPath("$.operator").doesNotExist())
                .andExpect(jsonPath("$.originalReading").value("10"))
                .andExpect(jsonPath("$.effectiveReading").value("10"))
                .andExpect(jsonPath("$.appendices.length()").value(0));

        // V1 视角：原始值 10、有效值 11，差异链含操作者/时刻/旧值/新值
        versioned("obs-c", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.operator").value("recorder-a"))
                .andExpect(jsonPath("$.correctedAtUtc").value("2026-10-01T08:30:00Z"))
                .andExpect(jsonPath("$.originalReading").value("10"))
                .andExpect(jsonPath("$.effectiveReading").value("11"))
                .andExpect(jsonPath("$.appendices.length()").value(1))
                .andExpect(jsonPath("$.appendices[0].appendixVersion").value(1))
                .andExpect(jsonPath("$.appendices[0].operator").value("recorder-a"))
                .andExpect(jsonPath("$.appendices[0].oldValue").value("10"))
                .andExpect(jsonPath("$.appendices[0].newValue").value("11"));

        // 当前视角即最新附录版本
        mockMvc.perform(get("/api/observations/{id}/corrigenda/current", "obs-c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.originalReading").value("10"))
                .andExpect(jsonPath("$.effectiveReading").value("11"));

        // 差异链列表
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-c"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].fieldName").value("reading"))
                .andExpect(jsonPath("$[0].resolutionId").value(resolutionId));

        // 原始观测快照保持只读：既有观测接口读数仍为 10，版本数不变
        mockMvc.perform(get("/api/observations/{id}", "obs-c"))
                .andExpect(jsonPath("$.reading").value("10"));
        Integer observationVersions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = 'obs-c'", Integer.class);
        assertThat(observationVersions).isEqualTo(2);
        assertThat(appendixCount("obs-c")).isEqualTo(1);
    }

    // ---------- 未解决冲突簇：422 ----------

    @Test
    void unresolvedClusterReturns422AndDoesNotConsumeVersion() throws Exception {
        // 从未发生冲突裁决的观测：同样附录返回 422
        create("req-u-0", "obs-u", "站点A", "10", "备注").andExpect(status().isCreated());
        appendix("req-u-1", "ap-u", "obs-u", "res-not-exist", 0, "reading", "11", "recorder-a")
                .andExpect(status().isUnprocessableEntity());

        // 冲突未解决（存在一次 409 但没有 resolve）的簇：422
        create("req-u-2", "obs-u2", "站点A", "10", "备注").andExpect(status().isCreated());
        merge("req-u-3", "obs-u2", 1, "站点B", "10", "备注").andExpect(status().isOk());
        merge("req-u-4", "obs-u2", 1, "站点C", "10", "备注").andExpect(status().isConflict());
        appendix("req-u-5", "ap-u2", "obs-u2", "res-not-exist", 0, "reading", "11", "recorder-a")
                .andExpect(status().isUnprocessableEntity());

        // 失败不占附录版本号：之后解决裁决，首个附录仍是 V1
        resolve("req-u-6", "res-u2", "obs-u2", 1, 2,
                "站点C", "10", "备注", Map.of("location", "CURRENT"), "reviewer-a")
                .andExpect(status().isOk());
        appendix("req-u-7", "ap-u2-v1", "obs-u2", "res-u2", 0, "reading", "11", "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixVersion").value(1))
                .andExpect(jsonPath("$.oldValue").value("10"));
        assertThat(appendixCount("obs-u")).isZero();
        // 失败不占 requestId：req-u-5 换合法参数后可成功（新 appendixId 指向已解决簇）
        appendix("req-u-5", "ap-u2-retry", "obs-u2", "res-u2", 1, "note", "复核备注", "recorder-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixVersion").value(2));
    }

    // ---------- requestId 幂等与换字段 409 ----------

    @Test
    void sameRequestIdReplaysFirstVersionAndDiffButChangingFieldReturns409() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-i");

        for (int i = 0; i < 2; i++) {
            appendix("req-i-1", "ap-i-1", "obs-i", resolutionId, 0, "reading", "11", "recorder-a")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.appendixVersion").value(1))
                    .andExpect(jsonPath("$.oldValue").value("10"))
                    .andExpect(jsonPath("$.newValue").value("11"));
        }
        assertThat(appendixCount("obs-i")).isEqualTo(1);

        // 同一 requestId 换字段：409，不产生 V2
        appendix("req-i-1", "ap-i-2", "obs-i", resolutionId, 1, "note", "新备注", "recorder-a")
                .andExpect(status().isConflict());
        // 同一 requestId 改新值：409
        appendix("req-i-1", "ap-i-1", "obs-i", resolutionId, 0, "reading", "12", "recorder-a")
                .andExpect(status().isConflict());
        assertThat(appendixCount("obs-i")).isEqualTo(1);
        versioned("obs-i", 1).andExpect(jsonPath("$.effectiveReading").value("11"));
    }

    @Test
    void sameAppendixIdWithNewRequestAndSameParamsReplaysButChangedParamsReturn409() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-a");

        appendix("req-a-1", "ap-a", "obs-a", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixVersion").value(1));
        // 新 requestId、相同参数、相同 appendixId：重放原结果，不产生第二条附录
        appendix("req-a-2", "ap-a", "obs-a", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appendixVersion").value(1))
                .andExpect(jsonPath("$.requestId").value("req-a-1"));
        assertThat(appendixCount("obs-a")).isEqualTo(1);
        // 相同 appendixId 改新值（新 requestId）：409，appendixId 不被第二次占用
        appendix("req-a-3", "ap-a", "obs-a", resolutionId, 0, "reading", "12", "recorder-a")
                .andExpect(status().isConflict());
        // 相同 appendixId 换字段：409
        appendix("req-a-4", "ap-a", "obs-a", resolutionId, 0, "note", "备注", "recorder-a")
                .andExpect(status().isConflict());
        assertThat(appendixCount("obs-a")).isEqualTo(1);
        Integer requestRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id IN ('req-a-1','req-a-2','req-a-3','req-a-4')",
                Integer.class);
        assertThat(requestRows).isEqualTo(2);
    }

    // ---------- 前置版本冲突与版本链 ----------

    @Test
    void staleExpectedAppendixVersionReturns409AndChainKeepsHistory() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-v");

        appendix("req-v-1", "ap-v-1", "obs-v", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixVersion").value(1));
        // 仍以 V0 为前置：版本冲突，响应给出当前附录版本
        appendix("req-v-2", "ap-v-2", "obs-v", resolutionId, 0, "note", "备注2", "recorder-a")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(1));
        assertThat(appendixCount("obs-v")).isEqualTo(1);

        // 以前置 V1 提交 V2：旧值为 V1 生效值 11
        appendix("req-v-3", "ap-v-3", "obs-v", resolutionId, 1, "reading", "12", "recorder-b")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixVersion").value(2))
                .andExpect(jsonPath("$.previousAppendixVersion").value(1))
                .andExpect(jsonPath("$.oldValue").value("11"))
                .andExpect(jsonPath("$.newValue").value("12"));

        // 旧版本查询始终返回当时值，不随 V2 漂移
        versioned("obs-v", 0).andExpect(jsonPath("$.effectiveReading").value("10"));
        versioned("obs-v", 1).andExpect(jsonPath("$.effectiveReading").value("11"));
        versioned("obs-v", 2).andExpect(jsonPath("$.effectiveReading").value("12"));
        // 超出当前附录版本：404
        versioned("obs-v", 3).andExpect(status().isNotFound());
        assertThat(appendixCount("obs-v")).isEqualTo(2);
    }

    // ---------- 最新裁决约束：拒绝绕过原裁决 ----------

    @Test
    void appendixReferencingNonLatestResolutionReturns422() throws Exception {
        // 第一次冲突与裁决 res-old（读数保持 10）
        String firstResolution = setupResolvedClusterWithReading10("obs-r");

        // 再次制造冲突并裁决：服务端 v3 改站点D，离线基于 v2 改站点E，resolve 选 CANDIDATE
        merge("req-r-1", "obs-r", 2, "站点D", "10", "备注").andExpect(status().isOk());
        merge("req-r-2", "obs-r", 2, "站点E", "10", "备注").andExpect(status().isConflict());
        resolve("req-r-3", "res-r-new", "obs-r", 2, 3,
                "站点E", "10", "备注", Map.of("location", "CANDIDATE"), "reviewer-b")
                .andExpect(status().isOk());

        // 引用旧裁决：拒绝（绕过原有/最新冲突裁决）
        appendix("req-r-4", "ap-r-old", "obs-r", firstResolution, 0, "reading", "11", "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        // 引用不存在的裁决：422（未解决簇判定先于 404，语义为该簇无此有效裁决依据）
        appendix("req-r-5", "ap-r-x", "obs-r", "res-missing", 0, "reading", "11", "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        // 引用最新裁决：成功
        appendix("req-r-6", "ap-r-new", "obs-r", "res-r-new", 0, "reading", "11", "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resolutionId").value("res-r-new"));
        assertThat(appendixCount("obs-r")).isEqualTo(1);
    }

    @Test
    void observationChangedAfterResolutionRejectsAppendixWith422() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-d");
        // 裁决之后又发生一次普通合并，观测版本已漂移：附录不能再挂在该裁决上
        merge("req-d-1", "obs-d", 2, "站点B", "10", "裁决后新备注").andExpect(status().isOk());
        appendix("req-d-2", "ap-d", "obs-d", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isUnprocessableEntity());
        assertThat(appendixCount("obs-d")).isZero();
    }

    // ---------- 删除字段、覆盖快照、非法值拒绝 ----------

    @Test
    void blankNewValueAndInvalidReadingAndNoOpChangeAreRejected() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-b");

        // 空白新值：视为删除既有字段，400
        appendix("req-b-1", "ap-b-1", "obs-b", resolutionId, 0, "reading", "   ", "recorder-a")
                .andExpect(status().isBadRequest());
        // 读数非法（四位小数）：400
        appendix("req-b-2", "ap-b-2", "obs-b", resolutionId, 0, "reading", "11.1234", "recorder-a")
                .andExpect(status().isBadRequest());
        // 新旧数值相同（10.00 与 10 按数值相等）：不构成差异，400
        appendix("req-b-3", "ap-b-3", "obs-b", resolutionId, 0, "reading", "10.00", "recorder-a")
                .andExpect(status().isBadRequest());
        // 非法字段名：400
        appendix("req-b-4", "ap-b-4", "obs-b", resolutionId, 0, "unknown", "x", "recorder-a")
                .andExpect(status().isBadRequest());
        // 全部失败不占版本号：下一条合法附录仍是 V1
        appendix("req-b-5", "ap-b-5", "obs-b", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appendixVersion").value(1));
        assertThat(appendixCount("obs-b")).isEqualTo(1);
        // 原始快照未被覆盖
        mockMvc.perform(get("/api/observations/{id}/versions/1", "obs-b"))
                .andExpect(jsonPath("$.reading").value("10"));
    }

    // ---------- 墓碑与不存在资源 ----------

    @Test
    void deletedObservationRejectsAppendixWith410() throws Exception {
        String resolutionId = setupResolvedClusterWithReading10("obs-g");
        mockMvc.perform(post("/api/observations/{id}/delete", "obs-g")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("requestId", "req-g-1", "expectedVersion", 2))))
                .andExpect(status().isOk());
        appendix("req-g-2", "ap-g", "obs-g", resolutionId, 0, "reading", "11", "recorder-a")
                .andExpect(status().isGone());
        assertThat(appendixCount("obs-g")).isZero();
    }

    @Test
    void appendixOnMissingObservationReturns404() throws Exception {
        appendix("req-m-1", "ap-m", "obs-missing", "res-x", 0, "reading", "11", "recorder-a")
                .andExpect(status().isNotFound());
        versioned("obs-missing", 0).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/observations/{id}/corrigenda", "obs-missing"))
                .andExpect(status().isNotFound());
    }
}
