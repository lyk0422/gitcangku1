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
 * 带限定符读数（EXACT/LT/GT）的主流程、拒绝分支与幂等边界测试（真实 H2 内存库，MySQL 兼容模式）。
 * 读数限定符与数值是不可拆分的一组：数值相等且限定符相同才是等价。
 */
@SpringBootTest
@AutoConfigureMockMvc
class QualifiedReadingApiTest {

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

    private String storedQualifier(String observationId, int version) {
        return jdbcTemplate.queryForObject(
                "SELECT reading_qualifier FROM observation_version WHERE observation_id = ? AND version = ?",
                String.class, observationId, version);
    }

    // ---------- 创建与查询 ----------

    @Test
    void createWithQualifierReturnsAndPersistsGroup() throws Exception {
        create("req-q1", "obs-1", "站点A", "5", "LT", "低于检出限")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));

        getCurrent("obs-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        getVersion("obs-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        // 成功写入冻结限定符原文
        assertThat(storedQualifier("obs-1", 1)).isEqualTo("LT");
    }

    @Test
    void createWithoutQualifierDefaultsToExact() throws Exception {
        create("req-q2", "obs-1", "站点A", "5.0", null, "旧普通读数")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("5.0"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        // 新写入即使缺省也冻结 EXACT，不依赖读取期默认
        assertThat(storedQualifier("obs-1", 1)).isEqualTo("EXACT");
    }

    @Test
    void createWithLowercaseQualifierIsNormalized() throws Exception {
        create("req-q3", "obs-1", "站点A", "5", "gt", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
        assertThat(storedQualifier("obs-1", 1)).isEqualTo("GT");
    }

    @Test
    void invalidQualifierOrValueReturns400() throws Exception {
        create("req-q4", "obs-1", "站点A", "5", "AROUND", "非法限定符")
                .andExpect(status().isBadRequest());
        create("req-q5", "obs-1", "站点A", "-1.5", "LT", "负数不允许")
                .andExpect(status().isBadRequest());
        create("req-q6", "obs-1", "站点A", "-1.5", null, "旧格式负数同样不允许")
                .andExpect(status().isBadRequest());
        create("req-q7", "obs-1", "站点A", "1.2345", "EXACT", "四位小数")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-1")).isZero();
    }

    // ---------- 等价与整组比较 ----------

    @Test
    void sameQualifierEquivalentValueDoesNotAddVersion() throws Exception {
        create("req-q8", "obs-1", "站点A", "5.0", "EXACT", "备注").andExpect(status().isCreated());
        // 5.000 与 5.0 数值相等、限定符相同：整组等价，不产生版本变化
        merge("req-q9", "obs-1", 1, "站点A", "5.000", "EXACT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.reading").value("5.0"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    @Test
    void exactFiveDiffersFromLtFive() throws Exception {
        create("req-q10", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        // EXACT 5 与 LT 5 数值相同但限定符不同：是有效变更，版本加一
        merge("req-q11", "obs-1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }
    // ---------- 三方合并：限定符与数值不可拆分 ----------

    @Test
    void splitQualifierAndValueChangeReturns422AndWritesNothing() throws Exception {
        // 合成场景：基线 EXACT 5，服务端 EXACT 6，本地 LT 5
        create("req-s1", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-s2", "obs-1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 一侧改数值、另一侧改限定符且两组不同：422，不拼成无人提交过的组合
        merge("req-s3", "obs-1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("6"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-1")).isEqualTo(2);

        // 失败不占键：同一 requestId 换上合法参数后正常执行
        merge("req-s3", "obs-1", 1, "站点A", "6", "EXACT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void bothSidesChangeValueOnlyKeepsExisting409() throws Exception {
        create("req-s4", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-s5", "obs-1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 两侧都只改数值（限定符未变）：沿用原有 409 合并冲突
        merge("req-s6", "obs-1", 1, "站点A", "7", "EXACT", "备注")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void bothSidesChangeToSameGroupIsAccepted() throws Exception {
        create("req-s7", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-s8", "obs-1", 1, "站点A", "7", "LT", "备注").andExpect(status().isOk());
        // 两边改为同一组（LT 7.0 与 LT 7 等价）：接受且与当前相同，不加版本
        merge("req-s9", "obs-1", 1, "站点A", "7.0", "LT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("7"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void mergeAcceptsCandidateGroupWhenCurrentUnchanged() throws Exception {
        create("req-s10", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        // 服务端未改：接受候选整组 GT 0.5
        merge("req-s11", "obs-1", 1, "站点A", "0.5", "GT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("0.5"))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
        assertThat(storedQualifier("obs-1", 2)).isEqualTo("GT");
    }

    // ---------- 人工裁决：必须选择完整一侧或提交完整新组 ----------

    @Test
    void resolveSelectsWholeCandidateGroupNotSpliced() throws Exception {
        // 合成场景：基线 EXACT 5，服务端 EXACT 6，本地 LT 5
        create("req-r1", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r2", "obs-1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        merge("req-r3", "obs-1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());

        // 裁决选本地：结果必须是完整候选组 LT 5，不能拼成 LT 6
        resolve("req-r4", "res-1", "obs-1", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"))
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.selections.reading").value("CANDIDATE"));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        // 解决记录冻结候选限定符与数值原文
        mockMvc.perform(get("/api/observations/resolutions/{resolutionId}", "res-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        String frozen = jdbcTemplate.queryForObject(
                "SELECT candidate_reading_qualifier FROM conflict_resolution WHERE resolution_id = 'res-1'",
                String.class);
        assertThat(frozen).isEqualTo("LT");
    }

    @Test
    void resolveSelectsCurrentKeepsServerGroup() throws Exception {
        create("req-r5", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r6", "obs-1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        merge("req-r7", "obs-1", 1, "站点A", "5", "LT", "备注")
                .andExpect(status().isUnprocessableEntity());

        // 裁决选当前：保留服务端整组 EXACT 6，内容无变化不加版本，但保存解决记录
        resolve("req-r8", "res-2", "obs-1", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CURRENT"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.reading").value("6"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void resolveSubmitsCompleteNewGroup() throws Exception {
        create("req-r9", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-r10", "obs-1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        // 候选 GT 7 是两侧都未提交过的完整新组：裁决接受后整组生效
        resolve("req-r11", "res-3", "obs-1", 1, 2, "站点A", "7", "GT", "备注",
                Map.of("reading", "CANDIDATE"), "operator-a")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("7"))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
        getCurrent("obs-1")
                .andExpect(jsonPath("$.reading").value("7"))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
    }
    // ---------- 墓碑、历史与旧数据 ----------

    @Test
    void tombstoneHasNoReadingFields() throws Exception {
        create("req-t1", "obs-1", "站点A", "5", "LT", "备注").andExpect(status().isCreated());
        delete("req-t2", "obs-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.readingQualifier").doesNotExist());
        getCurrent("obs-1")
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.readingQualifier").doesNotExist());
        getVersion("obs-1", 2)
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.readingQualifier").doesNotExist());
    }

    @Test
    void versionHistoryKeepsFrozenQualifier() throws Exception {
        create("req-t3", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-t4", "obs-1", 1, "站点A", "5", "LT", "备注").andExpect(status().isOk());
        // 每个历史版本冻结各自限定符，旧版本不被后续写回
        getVersion("obs-1", 1)
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        getVersion("obs-1", 2)
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(storedQualifier("obs-1", 1)).isEqualTo("EXACT");
        assertThat(storedQualifier("obs-1", 2)).isEqualTo("LT");
    }

    @Test
    void legacyNullQualifierReadsAsExactAndIsNotBackfilled() throws Exception {
        create("req-t5", "obs-1", "站点A", "5", null, "备注").andExpect(status().isCreated());
        // 模拟旧普通读数：存储层限定符为 NULL
        jdbcTemplate.update("UPDATE observation_current SET reading_qualifier = NULL WHERE observation_id = 'obs-1'");
        jdbcTemplate.update("UPDATE observation_version SET reading_qualifier = NULL WHERE observation_id = 'obs-1'");

        // 读取时按 EXACT 处理
        getCurrent("obs-1")
                .andExpect(jsonPath("$.reading").value("5"))
                .andExpect(jsonPath("$.readingQualifier").value("EXACT"));
        // 与 EXACT 5 整组等价的合并不产生版本变化
        merge("req-t6", "obs-1", 1, "站点A", "5.0", "EXACT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        // 旧版本不被默认值补写：存储仍为 NULL
        assertThat(storedQualifier("obs-1", 1)).isNull();
        assertThat(versionCount("obs-1")).isEqualTo(1);
    }

    // ---------- 幂等：限定符参与请求身份 ----------

    @Test
    void sameRequestIdReplaysAndQualifierChangeIs409() throws Exception {
        create("req-i1", "obs-1", "站点A", "5", "LT", "备注").andExpect(status().isCreated());
        // 同键同参重放首次完整响应
        create("req-i1", "obs-1", "站点A", "5", "LT", "备注")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        assertThat(versionCount("obs-1")).isEqualTo(1);

        // 同键异参：仅改限定符或仅改数值都是不同请求，409
        create("req-i1", "obs-1", "站点A", "5", "GT", "备注").andExpect(status().isConflict());
        create("req-i1", "obs-1", "站点A", "6", "LT", "备注").andExpect(status().isConflict());
        assertThat(versionCount("obs-1")).isEqualTo(1);

        merge("req-i2", "obs-1", 1, "站点A", "7", "GT", "备注").andExpect(status().isOk());
        merge("req-i2", "obs-1", 1, "站点A", "7", "GT", "备注")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.readingQualifier").value("GT"));
        merge("req-i2", "obs-1", 1, "站点A", "7", "LT", "备注").andExpect(status().isConflict());
        assertThat(versionCount("obs-1")).isEqualTo(2);
    }

    @Test
    void resolveRequestIdIdentityIncludesQualifier() throws Exception {
        create("req-i3", "obs-1", "站点A", "5", "EXACT", "备注").andExpect(status().isCreated());
        merge("req-i4", "obs-1", 1, "站点A", "6", "EXACT", "备注").andExpect(status().isOk());
        resolve("req-i5", "res-i1", "obs-1", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readingQualifier").value("LT"));
        // 同键同参重放
        resolve("req-i5", "res-i1", "obs-1", 1, 2, "站点A", "5", "LT", "备注",
                Map.of("reading", "CANDIDATE"), "op")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 同 resolutionId 改限定符：409
        resolve("req-i6", "res-i1", "obs-1", 1, 2, "站点A", "5", "GT", "备注",
                Map.of("reading", "CANDIDATE"), "op")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-1")).isEqualTo(3);
    }
}