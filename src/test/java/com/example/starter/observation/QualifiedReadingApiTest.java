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
 * 带限定符读数（EXACT/LT/GT + 非负十进制值）的 API 测试：
 * 校验、等价规则、整组三方合并（422）、整组人工裁决、历史冻结、幂等与墓碑边界。
 * 使用真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualifiedReadingApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-04T08:00:00Z");

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
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions create(String requestId, String observationId, String location,
                                 String reading, String readingQualifier, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        if (readingQualifier != null) {
            body.put("readingQualifier", readingQualifier);
        }
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion, String location,
                                String reading, String readingQualifier, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        if (readingQualifier != null) {
            body.put("readingQualifier", readingQualifier);
        }
        body.put("note", note);
        return mockMvc.perform(post("/api/observations/{id}/merge", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion, String location,
                                  String reading, String readingQualifier, String note,
                                  Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", reading);
        if (readingQualifier != null) {
            body.put("readingQualifier", readingQualifier);
        }
        body.put("note", note);
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
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

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/versions/{version}", observationId, version));
    }

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    // ---------- 创建与校验 ----------

    @Test
    void createWithQualifierReturnsQualifierAndValue() throws Exception {
        create("req-q1", "obs-q1", "站点A", "4.500", "LT", "低于检出限")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("4.500"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));

        // 查询同时给出限定符和值，读数原文保留（4.500 不被改写为 4.5）
        getCurrent("obs-q1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("4.500"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        getVersion("obs-q1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("4.500"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
    }

    @Test
    void createWithoutQualifierDefaultsToExact() throws Exception {
        create("req-q2", "obs-q2", "站点A", "5", null, "旧普通读数")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
    }

    @Test
    void invalidReadingOrQualifierReturns400() throws Exception {
        // 负值不允许：读数为非负十进制
        create("req-q3", "obs-q3", "站点A", "-1.5", "EXACT", "负值")
                .andExpect(status().isBadRequest());
        // 超过三位小数
        create("req-q4", "obs-q3", "站点A", "1.2345", "GT", "四位小数")
                .andExpect(status().isBadRequest());
        // 非法限定符
        create("req-q5", "obs-q3", "站点A", "1.5", "ABOUT", "非法限定符")
                .andExpect(status().isBadRequest());
        // 限定符小写不接受（仅 EXACT/LT/GT）
        create("req-q6", "obs-q3", "站点A", "1.5", "lt", "小写限定符")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-q3")).isZero();
    }

    // ---------- 等价规则 ----------

    @Test
    void sameQualifierWithNumericallyEqualValueIsEquivalent() throws Exception {
        create("req-e1", "obs-e1", "站点A", "5.0", "EXACT", "备注").andExpect(status().isCreated());
        // 候选 EXACT 5.000 与基线 EXACT 5.0 数值相等且限定符相同：视为未改，不加版本
        merge("req-e2", "obs-e1", 1, "站点A", "5.000", "EXACT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("5.0"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-e1")).isEqualTo(1);
    }

    @Test
    void sameValueWithDifferentQualifierIsNotEquivalent() throws Exception {
        create("req-e3", "obs-e2", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        // EXACT 5 与 LT 5 不同：服务端未改，候选改限定符被接受，版本加一
        merge("req-e4", "obs-e2", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(versionCount("obs-e2")).isEqualTo(2);
    }
    // ---------- 整组三方合并：合成基线场景 ----------

    /**
     * 题干合成场景：基线 EXACT 5，本地改限定符为 LT 5，服务端改数值为 EXACT 6，
     * 一侧改限定符、另一侧改数值且两组不同，普通合并必须 422，且不写入任何部分结果。
     */
    @Test
    void qualifierChangedOnOneSideAndValueOnOtherReturns422() throws Exception {
        create("req-s1", "obs-s1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        // 服务端前进到 v2：EXACT 6
        merge("req-s2", "obs-s1", 1, "站点A", "6", "EXACT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("6"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        // 本地基于 v1 提交 LT 5：422，冲突字段为 reading，不写入任何部分结果
        merge("req-s3", "obs-s1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("obs-s1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("6"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-s1")).isEqualTo(2);
        // 422 失败不占幂等键
        assertThat(requestLogCount("req-s3")).isZero();
    }

    @Test
    void bothSidesChangedValueOnlyKeeps409() throws Exception {
        create("req-s4", "obs-s2", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-s5", "obs-s2", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 两侧都只改数值（限定符均未变）：普通字段冲突语义，仍为 409
        merge("req-s6", "obs-s2", 1, "站点A", "7", "EXACT", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"));
        assertThat(versionCount("obs-s2")).isEqualTo(2);
    }

    @Test
    void bothSidesChangedQualifierOnlyKeeps409() throws Exception {
        create("req-s7", "obs-s3", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-s8", "obs-s3", 1, "站点A", "5", "GT", "备注").andExpect(status().isOk());
        // 两侧都只改限定符（数值均未变）：不属于“一侧改限定符、另一侧改数值”，仍为 409
        merge("req-s9", "obs-s3", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"));
        assertThat(versionCount("obs-s3")).isEqualTo(2);
    }

    @Test
    void bothSidesChangedToSameGroupIsAccepted() throws Exception {
        create("req-s10", "obs-s4", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-s11", "obs-s4", 1, "站点A", "5.00", "LT", "备注").andExpect(status().isOk());
        // 两侧都改为 LT 5（5 与 5.00 数值相等）：整组相同，接受且与当前相同，不加版本
        merge("req-s12", "obs-s4", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("5.00"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(versionCount("obs-s4")).isEqualTo(2);
    }

    // ---------- 整组人工裁决 ----------

    /**
     * 合成场景裁决：选本地（CANDIDATE）后结果必须是完整的 LT 5，
     * 绝不能拼成无人提交过的 LT 6；解决记录冻结限定符、读数原文与来源侧选择。
     */
    @Test
    void resolveCandidateAppliesCompleteLocalGroup() throws Exception {
        create("req-r1", "obs-r1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r2", "obs-r1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        merge("req-r3", "obs-r1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());

        resolve("req-r4", "res-r1", "obs-r1", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.selections.reading").value("CANDIDATE"))
                .andExpect(jsonPath("$.resolvedAtUtc").value("2026-10-04T08:00:00Z"))
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));

        getCurrent("obs-r1")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));

        // 解决记录冻结限定符、读数原文与来源侧选择
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT candidate_reading, candidate_reading_qualifier, field_selections, operator "
                        + "FROM conflict_resolution WHERE resolution_id = 'res-r1'");
        assertThat(row.get("CANDIDATE_READING")).isEqualTo("5");
        assertThat(row.get("CANDIDATE_READING_QUALIFIER")).isEqualTo("LT");
        assertThat((String) row.get("FIELD_SELECTIONS")).contains("\"reading\":\"CANDIDATE\"");
        assertThat(row.get("OPERATOR")).isEqualTo("operator-a");

        // 旧版本不被默认值补写：v1 仍是 EXACT 5，v2 仍是 EXACT 6
        getVersion("obs-r1", 1)
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        getVersion("obs-r1", 2)
                .andExpect(jsonPath("$.reading").value("6"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-r1")).isEqualTo(3);
    }

    @Test
    void resolveCurrentKeepsCompleteServerGroup() throws Exception {
        create("req-r5", "obs-r2", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r6", "obs-r2", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        merge("req-r7", "obs-r2", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());

        // 选服务端（CURRENT）：完整保留 EXACT 6，内容无变化不加版本，但保存解决记录
        resolve("req-r8", "res-r2", "obs-r2", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CURRENT"), "operator-b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.reading").value("6"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        getCurrent("obs-r2")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-r2")).isEqualTo(2);
    }

    @Test
    void resolveAcceptsCompleteNewGroupSubmittedAsCandidate() throws Exception {
        create("req-r9", "obs-r3", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r10", "obs-r3", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 本地提交完整新组 GT 7（两侧都未提交过的组，但作为候选完整一组提交）
        merge("req-r11", "obs-r3", 1, "站点A", "7", "GT", "备注")
                .andExpect(status().isUnprocessableEntity());
        resolve("req-r12", "res-r3", "obs-r3", 1, 2, "站点A", "7", "GT", "备注",
                Map.of("reading", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("7"))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
        getCurrent("obs-r3")
                .andExpect(jsonPath("$.reading").value("7"))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
    }

    @Test
    void failedResolveAddsNoHistoryVersion() throws Exception {
        create("req-r13", "obs-r4", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r14", "obs-r4", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 版本边界不匹配：409，不新增历史版本、不占键
        resolve("req-r15", "res-r4", "obs-r4", 1, 9, "站点A", "5", "LT", "备注",
                Map.of("reading", "CANDIDATE"), "op")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(versionCount("obs-r4")).isEqualTo(2);
        assertThat(requestLogCount("req-r15")).isZero();
        Integer resolutions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE observation_id = 'obs-r4'", Integer.class);
        assertThat(resolutions).isZero();
    }
    // ---------- 幂等与结构化身份 ----------

    @Test
    void sameRequestIdReplaysAndChangedQualifierIs409() throws Exception {
        create("req-i1", "obs-i1", "站点A", "5", "LT", "备注").andExpect(status().isCreated());
        // 同键同参重放：返回首次完整响应，不产生新版本
        create("req-i1", "obs-i1", "站点A", "5", "LT", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(versionCount("obs-i1")).isEqualTo(1);
        // 同键异参（限定符不同）：409
        create("req-i1", "obs-i1", "站点A", "5", "GT", "备注")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-i1")).isEqualTo(1);
    }

    @Test
    void omittedQualifierAndExplicitExactShareSameIdentity() throws Exception {
        create("req-i2", "obs-i2", "站点A", "5", null, "旧普通读数").andExpect(status().isCreated());
        // 缺省限定符与显式 EXACT 是同一身份：同键重放而非 409
        create("req-i2", "obs-i2", "站点A", "5", "EXACT", "旧普通读数")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-i2")).isEqualTo(1);
    }

    @Test
    void structuredIdentityDoesNotCollideOnSeparatorLikeContent() throws Exception {
        // 参数内容包含类似分隔符的控制字符：结构化身份不发生碰撞，同键异参必须 409 而非错误重放
        create("req-i3", "obs-i3", "A", "1.0", null, "B\u0001C")
                .andExpect(status().isCreated());
        create("req-i3", "obs-i3", "A\u00011.0\u0001B", "1.0", null, "C")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-i3")).isEqualTo(1);
        assertThat(requestLogCount("req-i3")).isEqualTo(1);
    }

    @Test
    void failedQualifiedWriteDoesNotOccupyRequestId() throws Exception {
        create("req-i4", "obs-i4", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-i5", "obs-i4", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 422 冲突失败：不占键
        merge("req-i6", "obs-i4", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());
        assertThat(requestLogCount("req-i6")).isZero();
        // 同一 requestId 换上合法参数后正常执行
        merge("req-i6", "obs-i4", 2, "站点A", "5", "LT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
    }

    // ---------- 墓碑与历史不可变 ----------

    @Test
    void tombstoneCarriesNoReadingFields() throws Exception {
        create("req-t1", "obs-t1", "站点A", "5", "LT", "备注").andExpect(status().isCreated());
        delete("req-t2", "obs-t1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.readingQualifier").doesNotExist());
        getCurrent("obs-t1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.readingQualifier").doesNotExist());
        getVersion("obs-t1", 2)
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.readingQualifier").doesNotExist());
        // 历史版本仍冻结当时的限定符与读数
        getVersion("obs-t1", 1)
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
    }

    @Test
    void resolutionRecordRemainsFrozenAfterLaterChanges() throws Exception {
        create("req-f1", "obs-f1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-f2", "obs-f1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        merge("req-f3", "obs-f1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());
        resolve("req-f4", "res-f1", "obs-f1", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 后续合并继续前进：解决记录仍指向解决时的版本与整组内容
        merge("req-f5", "obs-f1", 3, "站点A", "9", "GT", "备注").andExpect(status().isOk());
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-f1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"))
                .andExpect(jsonPath("$.selections.reading").value("CANDIDATE"))
                .andExpect(jsonPath("$.resolvedAtUtc").value("2026-10-04T08:00:00Z"));
        getCurrent("obs-f1")
                .andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.reading").value("9"))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
    }

    @Test
    void resolveReplayWithSameParamsReturnsOriginalQualifiedResult() throws Exception {
        create("req-p1", "obs-p1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-p2", "obs-p1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        merge("req-p3", "obs-p1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());
        for (int i = 0; i < 2; i++) {
            resolve("req-p4", "res-p1", "obs-p1", 1, 2, "站点A", "5", "LT", "备注",
                    Map.of("reading", "CANDIDATE"), "op")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(3))
                    .andExpect(jsonPath("$.reading").value("5"))
                    .andExpect(jsonPath("$.readingQualifier").value("LT"));
        }
        // 同 resolutionId 改限定符：改参 409
        resolve("req-p5", "res-p1", "obs-p1", 1, 3, "站点A", "5", "GT", "备注",
                Map.of("reading", "CANDIDATE"), "op")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-p1")).isEqualTo(3);
        Integer resolutions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE observation_id = 'obs-p1'", Integer.class);
        assertThat(resolutions).isEqualTo(1);
    }
}