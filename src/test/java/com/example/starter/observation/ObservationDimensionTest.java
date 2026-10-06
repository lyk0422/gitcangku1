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
 * 带量纲读数 API 测试：单位规范化、等价合并不增版、跨量纲 400、收件溯源与历史版本单位保留。
 * 使用真实 H2 内存库（MODE=MySQL）与固定 UTC 时钟。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservationDimensionTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-06T08:00:00Z");

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
        jdbcTemplate.update("DELETE FROM observation_receipt");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions create(String requestId, String observationId, String location,
                                 String reading, String readingUnit, String dimension,
                                 String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("readingUnit", readingUnit);
        body.put("dimension", dimension);
        body.put("note", note);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion, String location,
                                String reading, String readingUnit, String note) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("readingUnit", readingUnit);
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

    private ResultActions getCurrent(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}", observationId));
    }

    private ResultActions getVersion(String observationId, int version) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/versions/{version}", observationId, version));
    }

    private ResultActions getReceipts(String observationId) throws Exception {
        return mockMvc.perform(get("/api/observations/{id}/receipts", observationId));
    }

    private int receiptCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_receipt WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId);
        return count == null ? 0 : count;
    }

    // ---------- 创建与规范化 ----------

    @Test
    void createLengthNormalizesCmToMeter() throws Exception {
        create("req-d1", "obs-l1", "站点A", "100", "cm", "LENGTH", "长度记录")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("1.00"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.dimension").value("LENGTH"));

        getCurrent("obs-l1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("1.00"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.dimension").value("LENGTH"));

        getReceipts("obs-l1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].operation").value("CREATE"))
                .andExpect(jsonPath("$[0].originalValue").value("100"))
                .andExpect(jsonPath("$[0].originalUnit").value("cm"))
                .andExpect(jsonPath("$[0].normalizedValue").value("1.00"))
                .andExpect(jsonPath("$[0].dimension").value("LENGTH"))
                .andExpect(jsonPath("$[0].resultVersion").value(1))
                .andExpect(jsonPath("$[0].receivedAtUtc").value("2026-10-06T08:00:00Z"));
    }

    @Test
    void createTemperatureNormalizesKelvinToCelsius() throws Exception {
        create("req-d2", "obs-t1", "站点B", "273.15", "K", "TEMPERATURE", "温度记录")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("0.00"))
                .andExpect(jsonPath("$.readingUnit").value("K"))
                .andExpect(jsonPath("$.dimension").value("TEMPERATURE"));
        assertThat(receiptCount("obs-t1")).isEqualTo(1);
    }

    @Test
    void createRejectsInvalidDimensionAndUnitCombinations() throws Exception {
        // 提供单位但缺量纲：不自动推断，400
        create("req-d3", "obs-v1", "站点A", "1", "m", null, "备注")
                .andExpect(status().isBadRequest());
        // 提供量纲但缺单位：400
        create("req-d4", "obs-v1", "站点A", "1", null, "LENGTH", "备注")
                .andExpect(status().isBadRequest());
        // 量纲不支持的单位：400
        create("req-d5", "obs-v1", "站点A", "1", "mm", "LENGTH", "备注")
                .andExpect(status().isBadRequest());
        // 非法量纲名：400
        create("req-d6", "obs-v1", "站点A", "1", "m", "SPEED", "备注")
                .andExpect(status().isBadRequest());
        // 失败不占键、不留收件：同一 requestId 修正参数后可成功
        create("req-d6", "obs-v1", "站点A", "1", "m", "LENGTH", "备注")
                .andExpect(status().isCreated());
        assertThat(receiptCount("obs-v1")).isEqualTo(1);
        assertThat(requestLogCount("req-d3")).isZero();
        assertThat(requestLogCount("req-d5")).isZero();
    }

    // ---------- 三方合并：精确规范值比较 ----------

    @Test
    void syntheticScenarioMergesServerValueAndKeepsLocalReceipt() throws Exception {
        // 合成基线 1m（版本 1）
        create("req-s1", "obs-m1", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        // 服务端推进到 1.1m（版本 2）
        merge("req-s2", "obs-m1", 1, "站点A", "1.1", "m", "基线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("1.1"));
        // 本地离线提交 100cm（规范值 1m，等于基线）：采纳当前 1.1m，不增版本，但留下 100cm 收件
        merge("req-s3", "obs-m1", 1, "站点A", "100", "cm", "基线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("1.1"))
                .andExpect(jsonPath("$.readingUnit").value("m"));

        getReceipts("obs-m1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].operation").value("MERGE"))
                .andExpect(jsonPath("$[2].originalValue").value("100"))
                .andExpect(jsonPath("$[2].originalUnit").value("cm"))
                .andExpect(jsonPath("$[2].normalizedValue").value("1.00"))
                .andExpect(jsonPath("$[2].resultVersion").value(2));
    }

    @Test
    void mergeTemperatureEquivalentKelvinDoesNotBumpVersion() throws Exception {
        create("req-t1", "obs-m2", "站点B", "0", "C", "TEMPERATURE", "基线")
                .andExpect(status().isCreated());
        merge("req-t2", "obs-m2", 1, "站点B", "1", "C", "基线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 273.15K 规范化为 0C，等于基线：保留当前 1C，不增版本，留下收件
        merge("req-t3", "obs-m2", 1, "站点B", "273.15", "K", "基线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("1"));
        getReceipts("obs-m2")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].originalUnit").value("K"))
                .andExpect(jsonPath("$[2].normalizedValue").value("0.00"));
    }

    @Test
    void mergeBothSidesEquivalentChangeAcceptsWithoutBump() throws Exception {
        create("req-b1", "obs-m3", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        merge("req-b2", "obs-m3", 1, "站点A", "2", "m", "基线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 本地 200cm 规范化为 2m，与当前等价：接受但不增版本
        merge("req-b3", "obs-m3", 1, "站点A", "200", "cm", "基线")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("2"));
        assertThat(receiptCount("obs-m3")).isEqualTo(3);
    }

    @Test
    void mergeCrossDimensionUnitRejectedWith400() throws Exception {
        create("req-x1", "obs-m4", "站点A", "1", "m", "LENGTH", "长度记录")
                .andExpect(status().isCreated());
        // 用 1C 修改长度记录：跨量纲，400；失败不留收件、不占键
        merge("req-x2", "obs-m4", 1, "站点A", "1", "C", "温度单位")
                .andExpect(status().isBadRequest());
        assertThat(receiptCount("obs-m4")).isEqualTo(1);
        assertThat(requestLogCount("req-x2")).isZero();
        getCurrent("obs-m4").andExpect(jsonPath("$.reading").value("1"));
        // 同一 requestId 修正为合法单位后可用
        merge("req-x2", "obs-m4", 1, "站点A", "2", "m", "修正提交")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void mergeRealConflictWithUnitsStill409() throws Exception {
        create("req-f1", "obs-m5", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        merge("req-f2", "obs-m5", 1, "站点A", "2", "m", "服务端2m")
                .andExpect(status().isOk());
        // 本地 300cm（3m）与服务端 2m 都偏离基线且互不等价：409 冲突
        merge("req-f3", "obs-m5", 1, "站点A", "300", "cm", "本地300cm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.currentVersion").value(2));
        assertThat(receiptCount("obs-m5")).isEqualTo(2);
    }

    // ---------- 量纲固定与旧记录兼容 ----------

    @Test
    void legacyRecordWithoutDimensionRejectsUnitAndKeepsNumericMerge() throws Exception {
        // 旧无单位读数：不自动推断量纲
        create("req-g1", "obs-m6", "站点A", "1.0", null, null, "旧记录")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reading").value("1.0"));
        getCurrent("obs-m6")
                .andExpect(jsonPath("$.readingUnit").doesNotExist())
                .andExpect(jsonPath("$.dimension").doesNotExist());
        // 携带单位提交：400
        merge("req-g2", "obs-m6", 1, "站点A", "2", "m", "带单位")
                .andExpect(status().isBadRequest());
        // 无单位提交：仍按数值合并
        merge("req-g3", "obs-m6", 1, "站点A", "2.0", null, "无单位")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("2.0"));
        getReceipts("obs-m6")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].originalUnit").doesNotExist())
                .andExpect(jsonPath("$[1].normalizedValue").value("2.0"));
    }

    @Test
    void dimensionedRecordRequiresUnitOnMerge() throws Exception {
        create("req-h1", "obs-m7", "站点A", "1", "m", "LENGTH", "长度记录")
                .andExpect(status().isCreated());
        merge("req-h2", "obs-m7", 1, "站点A", "2", null, "缺单位")
                .andExpect(status().isBadRequest());
        assertThat(receiptCount("obs-m7")).isEqualTo(1);
    }

    // ---------- 删除墓碑与历史版本 ----------

    @Test
    void deletedRecordRejectsEquivalentRewrite() throws Exception {
        create("req-z1", "obs-m8", "站点A", "1", "m", "LENGTH", "长度记录")
                .andExpect(status().isCreated());
        delete("req-z2", "obs-m8", 1).andExpect(status().isOk());
        // 删除后不能用等价请求（100cm ≡ 1m）重新写入墓碑：410，且不留收件
        merge("req-z3", "obs-m8", 1, "站点A", "100", "cm", "等价重写")
                .andExpect(status().isGone());
        assertThat(receiptCount("obs-m8")).isEqualTo(1);
        assertThat(requestLogCount("req-z3")).isZero();
    }

    @Test
    void historyVersionsKeepOriginalUnits() throws Exception {
        create("req-y1", "obs-m9", "站点A", "100", "cm", "LENGTH", "首版厘米")
                .andExpect(status().isCreated());
        merge("req-y2", "obs-m9", 1, "站点A", "2", "m", "次版米")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        // 历史版本不改单位：v1 仍是 cm 收件时的快照，v2 是 m
        getVersion("obs-m9", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("1.00"))
                .andExpect(jsonPath("$.readingUnit").value("cm"));
        getVersion("obs-m9", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("2"))
                .andExpect(jsonPath("$.readingUnit").value("m"));
        // 当前规范读数与历次输入分开查询
        getCurrent("obs-m9").andExpect(jsonPath("$.reading").value("2"));
        getReceipts("obs-m9")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].originalValue").value("100"))
                .andExpect(jsonPath("$[1].originalValue").value("2"));
    }

    // ---------- 幂等 ----------

    @Test
    void sameRequestIdReplayReturnsStoredResponseWithoutDuplicateReceipt() throws Exception {
        create("req-i1", "obs-ma", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        merge("req-i2", "obs-ma", 1, "站点A", "200", "cm", "本地提交")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("2.00"));
        // 同键同参重放：返回首次完整响应，不重复收件、不增版本
        merge("req-i2", "obs-ma", 1, "站点A", "200", "cm", "本地提交")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("2.00"));
        assertThat(receiptCount("obs-ma")).isEqualTo(2);
        // 同键异参（仅单位不同）：409
        merge("req-i2", "obs-ma", 1, "站点A", "2", "m", "本地提交")
                .andExpect(status().isConflict());
        assertThat(receiptCount("obs-ma")).isEqualTo(2);
    }

    // ---------- 人工裁决：冻结来源与精确规范值 ----------

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion, String location,
                                  String reading, String readingUnit, String note,
                                  Map<String, String> selections, String operator) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("resolutionId", resolutionId);
        body.put("baseVersion", baseVersion);
        body.put("expectedCurrentVersion", expectedCurrentVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("readingUnit", readingUnit);
        body.put("note", note);
        body.put("selections", selections);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    @Test
    void resolveFreezesChosenSourceAndExactNormalizedReading() throws Exception {
        create("req-r1", "obs-mb", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        merge("req-r2", "obs-mb", 1, "站点A", "2", "m", "基线")
                .andExpect(status().isOk());
        merge("req-r3", "obs-mb", 1, "站点A", "300", "cm", "基线")
                .andExpect(status().isConflict());
        // 人工裁决选择候选 300cm：冻结来源 CANDIDATE 与精确规范值 3.00
        resolve("req-r4", "res-1", "obs-mb", 1, 2, "站点A", "300", "cm", "基线",
                Map.of("reading", "CANDIDATE"), "operator-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.reading").value("3.00"))
                .andExpect(jsonPath("$.resolvedReading").value("3.00"))
                .andExpect(jsonPath("$.resolvedReadingSource").value("CANDIDATE"))
                .andExpect(jsonPath("$.candidateReadingUnit").value("cm"))
                .andExpect(jsonPath("$.resolvedAtUtc").value("2026-10-06T08:00:00Z"));
        // 裁决收件与业务版本同事务可见
        getReceipts("obs-mb")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].operation").value("RESOLVE"))
                .andExpect(jsonPath("$[2].originalValue").value("300"))
                .andExpect(jsonPath("$[2].originalUnit").value("cm"))
                .andExpect(jsonPath("$[2].normalizedValue").value("3.00"))
                .andExpect(jsonPath("$[2].resultVersion").value(3));
        // 不可变解决记录按 resolutionId 可查，冻结值不随后续变更改变
        mockMvc.perform(get("/api/observations/resolutions/{id}", "res-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedReading").value("3.00"))
                .andExpect(jsonPath("$.resolvedReadingSource").value("CANDIDATE"));
    }

    @Test
    void resolveKeepingCurrentDoesNotBumpVersionButLeavesReceipt() throws Exception {
        create("req-n1", "obs-mc", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        merge("req-n2", "obs-mc", 1, "站点A", "2", "m", "基线")
                .andExpect(status().isOk());
        merge("req-n3", "obs-mc", 1, "站点A", "3", "m", "基线")
                .andExpect(status().isConflict());
        // 裁决保留当前：内容无变化不加版本，但冻结来源 CURRENT 与规范值，并留下候选收件
        resolve("req-n4", "res-2", "obs-mc", 1, 2, "站点A", "3", "m", "基线",
                Map.of("reading", "CURRENT"), "operator-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.resolvedReading").value("2"))
                .andExpect(jsonPath("$.resolvedReadingSource").value("CURRENT"));
        getReceipts("obs-mc")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].operation").value("RESOLVE"))
                .andExpect(jsonPath("$[2].normalizedValue").value("3"))
                .andExpect(jsonPath("$[2].resultVersion").value(2));
    }

    @Test
    void resolveCrossDimensionUnitRejectedWith400() throws Exception {
        create("req-q1", "obs-md", "站点A", "1", "m", "LENGTH", "基线")
                .andExpect(status().isCreated());
        merge("req-q2", "obs-md", 1, "站点A", "2", "m", "服务端2m")
                .andExpect(status().isOk());
        // 跨量纲候选（1C）提交裁决：400，不留解决记录与收件
        resolve("req-q3", "res-3", "obs-md", 1, 2, "站点A", "1", "C", "裁决",
                Map.of("reading", "CANDIDATE"), "operator-3")
                .andExpect(status().isBadRequest());
        assertThat(receiptCount("obs-md")).isEqualTo(2);
        Integer resolutions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE observation_id = 'obs-md'", Integer.class);
        assertThat(resolutions).isZero();
    }
}
