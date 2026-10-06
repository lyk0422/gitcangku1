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
 * 带量纲读数 API 测试：创建固定量纲、规范化等价合并、跨量纲 400、收件溯源、
 * 墓碑保护、幂等身份与冲突解决冻结（真实 H2 内存库，MySQL 兼容模式，固定 UTC 时钟）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DimensionalReadingApiTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-10-06T08:30:00Z");

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
        jdbcTemplate.update("DELETE FROM reading_receipt");
        jdbcTemplate.update("DELETE FROM conflict_resolution");
        jdbcTemplate.update("DELETE FROM request_log");
        jdbcTemplate.update("DELETE FROM observation_version");
        jdbcTemplate.update("DELETE FROM observation_current");
        Mockito.when(clock.instant()).thenReturn(FIXED_NOW);
        Mockito.when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ResultActions create(String requestId, String observationId, String location, String reading,
                                 String note, String dimension, String readingUnit, String operator)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("observationId", observationId);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("dimension", dimension);
        body.put("readingUnit", readingUnit);
        body.put("operator", operator);
        return mockMvc.perform(post("/api/observations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions merge(String requestId, String observationId, int baseVersion, String location,
                                String reading, String note, String dimension, String readingUnit)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("baseVersion", baseVersion);
        body.put("location", location);
        body.put("reading", reading);
        body.put("note", note);
        body.put("dimension", dimension);
        body.put("readingUnit", readingUnit);
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

    private ResultActions resolve(String requestId, String resolutionId, String observationId,
                                  int baseVersion, int expectedCurrentVersion, String location, String reading,
                                  String note, Map<String, String> selections, String operator,
                                  String dimension, String readingUnit) throws Exception {
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
        body.put("dimension", dimension);
        body.put("readingUnit", readingUnit);
        return mockMvc.perform(post("/api/observations/{id}/resolve", observationId)
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

    private int versionCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_version WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int receiptCount(String observationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reading_receipt WHERE observation_id = ?", Integer.class, observationId);
        return count == null ? 0 : count;
    }

    private int requestLogCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        return count == null ? 0 : count;
    }
    // ---------- 创建与规范化 ----------

    @Test
    void createLengthReadingKeepsOriginalUnitAndStoresNormalized() throws Exception {
        create("req-u1", "obs-len", "站点A", "100", "首次记录", "LENGTH", "cm", "op-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.dimension").value("LENGTH"))
                .andExpect(jsonPath("$.reading").value("100"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.normalizedReading").value("1"));

        getCurrent("obs-len")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("100"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.normalizedReading").value("1"));

        // 创建成功即收件：原值、单位与规范值齐全，时刻来自可控时钟
        getReceipts("obs-len")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value("req-u1"))
                .andExpect(jsonPath("$[0].operation").value("CREATE"))
                .andExpect(jsonPath("$[0].dimension").value("LENGTH"))
                .andExpect(jsonPath("$[0].originalReading").value("100"))
                .andExpect(jsonPath("$[0].originalUnit").value("cm"))
                .andExpect(jsonPath("$[0].normalizedReading").value("1"))
                .andExpect(jsonPath("$[0].operator").value("op-1"))
                .andExpect(jsonPath("$[0].receivedAtUtc").value("2026-10-06T08:30:00Z"));
    }

    @Test
    void createTemperatureKelvinNormalizesToCelsius() throws Exception {
        create("req-u2", "obs-tmp", "站点B", "273.15", "冰点", "TEMPERATURE", "K", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dimension").value("TEMPERATURE"))
                .andExpect(jsonPath("$.reading").value("273.15"))
                .andExpect(jsonPath("$.readingUnit").value("K"))
                .andExpect(jsonPath("$.normalizedReading").value("0"));
    }

    @Test
    void createRejectsInvalidDimensionalInputAndWritesNothing() throws Exception {
        // 单位与量纲必须成对出现
        create("req-b1", "obs-b1", "站点A", "1.0", "备注", null, "cm", null)
                .andExpect(status().isBadRequest());
        create("req-b2", "obs-b2", "站点A", "1.0", "备注", "LENGTH", null, null)
                .andExpect(status().isBadRequest());
        // 不支持的单位与量纲
        create("req-b3", "obs-b3", "站点A", "1.0", "备注", "LENGTH", "mm", null)
                .andExpect(status().isBadRequest());
        create("req-b4", "obs-b4", "站点A", "1.0", "备注", "MASS", "kg", null)
                .andExpect(status().isBadRequest());
        // 单位不属于所给量纲
        create("req-b5", "obs-b5", "站点A", "1", "备注", "LENGTH", "C", null)
                .andExpect(status().isBadRequest());
        // 值超过三位小数
        create("req-b6", "obs-b6", "站点A", "1.2345", "备注", "LENGTH", "m", null)
                .andExpect(status().isBadRequest());

        Integer observations = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM observation_current", Integer.class);
        assertThat(observations).isZero();
        assertThat(requestLogCount()).isZero();
        Integer receipts = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM reading_receipt", Integer.class);
        assertThat(receipts).isZero();
    }

    // ---------- 规范化等价合并 ----------

    @Test
    void syntheticScenarioServerWinsAndLocalReceiptIsKept() throws Exception {
        // 合成基线 1m；服务端推进到 1.1m；本地离线提交 100cm（与基线等价）
        create("req-s1", "obs-syn", "站点A", "1", "基线", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        merge("req-s2", "obs-syn", 1, "站点A", "1.1", "基线", "LENGTH", "m")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.normalizedReading").value("1.1"));
        // 100cm 与基线 1m 等价：候选未改，采纳服务端 1.1m，不增版本，但留下 100cm 收件来源
        merge("req-s3", "obs-syn", 1, "站点A", "100", "基线", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.reading").value("1.1"))
                .andExpect(jsonPath("$.readingUnit").value("m"))
                .andExpect(jsonPath("$.normalizedReading").value("1.1"));

        assertThat(versionCount("obs-syn")).isEqualTo(2);
        getReceipts("obs-syn")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].requestId").value("req-s3"))
                .andExpect(jsonPath("$[2].operation").value("MERGE"))
                .andExpect(jsonPath("$[2].originalReading").value("100"))
                .andExpect(jsonPath("$[2].originalUnit").value("cm"))
                .andExpect(jsonPath("$[2].normalizedReading").value("1"));
    }

    @Test
    void equivalentTemperatureDoesNotBumpVersionButKeepsReceipt() throws Exception {
        create("req-t1", "obs-eq", "站点A", "0", "冰点", "TEMPERATURE", "C", null)
                .andExpect(status().isCreated());
        // 273.15K 与 0C 等价：候选未改，不加版本，仍保存收件
        merge("req-t2", "obs-eq", 1, "站点A", "273.15", "冰点", "TEMPERATURE", "K")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.normalizedReading").value("0"));
        assertThat(versionCount("obs-eq")).isEqualTo(1);
        getReceipts("obs-eq")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].originalReading").value("273.15"))
                .andExpect(jsonPath("$[1].originalUnit").value("K"))
                .andExpect(jsonPath("$[1].normalizedReading").value("0"));
    }

    @Test
    void mergeComparisonDoesNotRoundNormalizedValues() throws Exception {
        // 1.234 cm 规范化为 0.01234 m（超过三位小数）；候选 0.012 m 与之精确不等
        create("req-p1", "obs-prec", "站点A", "1.234", "备注", "LENGTH", "cm", null)
                .andExpect(status().isCreated());
        merge("req-p2", "obs-prec", 1, "站点A", "0.012", "备注", "LENGTH", "m")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.normalizedReading").value("0.012"));
        assertThat(versionCount("obs-prec")).isEqualTo(2);
    }

    @Test
    void historyVersionsKeepOriginalUnits() throws Exception {
        create("req-h1", "obs-hist", "站点A", "100", "备注", "LENGTH", "cm", null)
                .andExpect(status().isCreated());
        merge("req-h2", "obs-hist", 1, "站点A", "1.5", "备注", "LENGTH", "m")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // 历史版本不改单位：v1 仍是 100cm，v2 是 1.5m
        getVersion("obs-hist", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("100"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.normalizedReading").value("1"));
        getVersion("obs-hist", 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reading").value("1.5"))
                .andExpect(jsonPath("$.readingUnit").value("m"))
                .andExpect(jsonPath("$.normalizedReading").value("1.5"));
    }

    // ---------- 跨量纲与失败分支 ----------

    @Test
    void crossDimensionSubmissionIsRejected() throws Exception {
        create("req-x1", "obs-len2", "站点A", "1", "长度记录", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        // 用 1C 修改长度记录：400
        merge("req-x2", "obs-len2", 1, "站点A", "1", "长度记录", "TEMPERATURE", "C")
                .andExpect(status().isBadRequest());
        // 量纲对但单位不属于量纲：400
        merge("req-x3", "obs-len2", 1, "站点A", "1", "长度记录", "LENGTH", "C")
                .andExpect(status().isBadRequest());
        // 带量纲记录缺量纲提交：400
        merge("req-x4", "obs-len2", 1, "站点A", "2", "长度记录", null, null)
                .andExpect(status().isBadRequest());
        // 旧无单位记录不接受量纲提交（不自动推断量纲）：400
        create("req-x5", "obs-legacy", "站点A", "1.0", "旧记录", null, null, null)
                .andExpect(status().isCreated());
        merge("req-x6", "obs-legacy", 1, "站点A", "1", "旧记录", "LENGTH", "m")
                .andExpect(status().isBadRequest());

        // 失败不产生版本、不占键、不留收件
        getCurrent("obs-len2")
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.normalizedReading").value("1"));
        assertThat(versionCount("obs-len2")).isEqualTo(1);
        assertThat(receiptCount("obs-len2")).isEqualTo(1);
        assertThat(requestLogCount()).isEqualTo(2);
    }

    @Test
    void failedMergeLeavesNoReceiptAndNoRequestKey() throws Exception {
        create("req-f1", "obs-fail", "站点A", "1", "备注", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        // 基线版本不存在：404，不留收件
        merge("req-f2", "obs-fail", 9, "站点A", "2", "备注", "LENGTH", "m")
                .andExpect(status().isNotFound());
        // 三方冲突：409，不留收件
        merge("req-f3", "obs-fail", 1, "站点A", "2", "备注", "LENGTH", "m")
                .andExpect(status().isOk());
        merge("req-f4", "obs-fail", 1, "站点A", "3", "备注", "LENGTH", "m")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"));

        assertThat(receiptCount("obs-fail")).isEqualTo(2);
        // 只有成功的创建与第一次合并占键
        assertThat(requestLogCount()).isEqualTo(2);
        getReceipts("obs-fail")
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].requestId").value("req-f3"))
                .andExpect(jsonPath("$[1].normalizedReading").value("2"));
    }

    @Test
    void receiptsOfMissingObservationReturn404() throws Exception {
        getReceipts("obs-none").andExpect(status().isNotFound());
    }
    // ---------- 幂等身份 ----------

    @Test
    void sameRequestIdReplaysAndEquivalentButDifferentParamsConflict() throws Exception {
        create("req-i1", "obs-idem", "站点A", "100", "备注", "LENGTH", "cm", "op-a")
                .andExpect(status().isCreated());
        // 同键同参重放：返回首次完整响应，不重复收件
        create("req-i1", "obs-idem", "站点A", "100", "备注", "LENGTH", "cm", "op-a")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.normalizedReading").value("1"));
        assertThat(receiptCount("obs-idem")).isEqualTo(1);
        assertThat(versionCount("obs-idem")).isEqualTo(1);

        // 同键异参：1m 与 100cm 规范值等价，但参数不同，身份含全部参数，必须 409
        create("req-i1", "obs-idem", "站点A", "1", "备注", "LENGTH", "m", "op-a")
                .andExpect(status().isConflict());
        // 操作者属于身份：仅操作者不同也 409
        create("req-i1", "obs-idem", "站点A", "100", "备注", "LENGTH", "cm", "op-b")
                .andExpect(status().isConflict());
        assertThat(receiptCount("obs-idem")).isEqualTo(1);

        // 合并重放：同键同参返回原结果，不重复收件
        merge("req-i2", "obs-idem", 1, "站点A", "150", "备注", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.normalizedReading").value("1.5"));
        merge("req-i2", "obs-idem", 1, "站点A", "150", "备注", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(receiptCount("obs-idem")).isEqualTo(2);
        assertThat(versionCount("obs-idem")).isEqualTo(2);
        // 同键换等价表示（1.5m）：异参 409
        merge("req-i2", "obs-idem", 1, "站点A", "1.5", "备注", "LENGTH", "m")
                .andExpect(status().isConflict());
    }

    // ---------- 删除与墓碑 ----------

    @Test
    void deletedRecordRejectsEquivalentMergeAndKeepsTombstone() throws Exception {
        create("req-d1", "obs-del", "站点A", "1", "备注", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        delete("req-d2", "obs-del", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true));

        // 删除后不能用等价请求（100cm == 1m）重新写入墓碑
        merge("req-d3", "obs-del", 1, "站点A", "100", "备注", "LENGTH", "cm")
                .andExpect(status().isGone());
        getCurrent("obs-del")
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.reading").doesNotExist())
                .andExpect(jsonPath("$.normalizedReading").doesNotExist());
        assertThat(versionCount("obs-del")).isEqualTo(2);
        // 失败的合并不留收件
        assertThat(receiptCount("obs-del")).isEqualTo(1);
    }

    // ---------- 冲突解决 ----------

    @Test
    void resolveFreezesChosenSourceAndExactNormalizedValue() throws Exception {
        // 基线 1m；服务端 2m；离线端 300cm（=3m）：读数冲突
        create("req-r1", "obs-res", "站点A", "1", "备注", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        merge("req-r2", "obs-res", 1, "站点A", "2", "备注", "LENGTH", "m")
                .andExpect(status().isOk());
        merge("req-r3", "obs-res", 1, "站点A", "300", "备注", "LENGTH", "cm")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.currentVersion").value(2));

        // 人工选择候选：冻结所选来源（300cm）与精确规范值（3），收件与新版本同事务保存
        resolve("req-r4", "res-r1", "obs-res", 1, 2, "站点A", "300", "备注",
                Map.of("reading", "CANDIDATE"), "op-r", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.contentChanged").value(true))
                .andExpect(jsonPath("$.conflictFields[0]").value("reading"))
                .andExpect(jsonPath("$.selections.reading").value("CANDIDATE"))
                .andExpect(jsonPath("$.reading").value("300"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.normalizedReading").value("3"))
                .andExpect(jsonPath("$.dimension").value("LENGTH"))
                .andExpect(jsonPath("$.resolvedAtUtc").value("2026-10-06T08:30:00Z"));

        getCurrent("obs-res")
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.reading").value("300"))
                .andExpect(jsonPath("$.readingUnit").value("cm"))
                .andExpect(jsonPath("$.normalizedReading").value("3"));
        // 解决收件与业务版本在同一事务：v3 与 RESOLVE 收件同时存在
        assertThat(versionCount("obs-res")).isEqualTo(3);
        getReceipts("obs-res")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].operation").value("RESOLVE"))
                .andExpect(jsonPath("$[2].originalReading").value("300"))
                .andExpect(jsonPath("$[2].originalUnit").value("cm"))
                .andExpect(jsonPath("$[2].normalizedReading").value("3"))
                .andExpect(jsonPath("$[2].operator").value("op-r"));
        // 解决记录冻结精确规范值
        String frozen = jdbcTemplate.queryForObject(
                "SELECT resolved_reading_normalized FROM conflict_resolution WHERE resolution_id = 'res-r1'",
                String.class);
        assertThat(frozen).isEqualTo("3");
        String candidateUnit = jdbcTemplate.queryForObject(
                "SELECT candidate_reading_unit FROM conflict_resolution WHERE resolution_id = 'res-r1'",
                String.class);
        assertThat(candidateUnit).isEqualTo("cm");

        // 失败的冲突合并不留收件：req-r3 不在收件历史里
        getReceipts("obs-res").andExpect(jsonPath("$[?(@.requestId == 'req-r3')]").isEmpty());
    }

    @Test
    void resolveKeepingCurrentDoesNotBumpVersionButStoresReceipt() throws Exception {
        create("req-n1", "obs-noc", "站点A", "1", "备注", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        merge("req-n2", "obs-noc", 1, "站点A", "2", "备注", "LENGTH", "m")
                .andExpect(status().isOk());
        merge("req-n3", "obs-noc", 1, "站点A", "300", "备注", "LENGTH", "cm")
                .andExpect(status().isConflict());

        // 选择保留当前：内容无变化不加版本，但候选输入仍收件，解决记录冻结当前规范值
        resolve("req-n4", "res-n1", "obs-noc", 1, 2, "站点A", "300", "备注",
                Map.of("reading", "CURRENT"), "op-n", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.contentChanged").value(false))
                .andExpect(jsonPath("$.normalizedReading").value("2"));
        assertThat(versionCount("obs-noc")).isEqualTo(2);
        getReceipts("obs-noc")
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].operation").value("RESOLVE"))
                .andExpect(jsonPath("$[2].originalReading").value("300"));
        String frozen = jdbcTemplate.queryForObject(
                "SELECT resolved_reading_normalized FROM conflict_resolution WHERE resolution_id = 'res-n1'",
                String.class);
        assertThat(frozen).isEqualTo("2");
    }

    @Test
    void resolveWithCrossDimensionCandidateIsRejected() throws Exception {
        create("req-c1", "obs-cd", "站点A", "1", "备注", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        merge("req-c2", "obs-cd", 1, "站点A", "2", "备注", "LENGTH", "m")
                .andExpect(status().isOk());
        // 用温度候选解决长度记录的冲突：400
        resolve("req-c3", "res-c1", "obs-cd", 1, 2, "站点A", "300", "备注",
                Map.of("reading", "CANDIDATE"), "op-c", "TEMPERATURE", "K")
                .andExpect(status().isBadRequest());
        assertThat(versionCount("obs-cd")).isEqualTo(2);
        assertThat(receiptCount("obs-cd")).isEqualTo(2);
    }

    @Test
    void resolveReplayWithSameParamsAndConflictOnDifferentParams() throws Exception {
        create("req-v1", "obs-rep", "站点A", "1", "备注", "LENGTH", "m", null)
                .andExpect(status().isCreated());
        merge("req-v2", "obs-rep", 1, "站点A", "2", "备注", "LENGTH", "m")
                .andExpect(status().isOk());
        merge("req-v3", "obs-rep", 1, "站点A", "300", "备注", "LENGTH", "cm")
                .andExpect(status().isConflict());

        resolve("req-v4", "res-v1", "obs-rep", 1, 2, "站点A", "300", "备注",
                Map.of("reading", "CANDIDATE"), "op-v", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3));
        // 同 resolutionId 同参（不同 requestId）重放：返回原结果
        resolve("req-v5", "res-v1", "obs-rep", 1, 2, "站点A", "300", "备注",
                Map.of("reading", "CANDIDATE"), "op-v", "LENGTH", "cm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.resolutionId").value("res-v1"));
        // 同 resolutionId 异参（候选换成等价但不同的 3m）：409
        resolve("req-v6", "res-v1", "obs-rep", 1, 2, "站点A", "3", "备注",
                Map.of("reading", "CANDIDATE"), "op-v", "LENGTH", "m")
                .andExpect(status().isConflict());
        assertThat(versionCount("obs-rep")).isEqualTo(3);
        Integer resolutions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM conflict_resolution WHERE resolution_id = 'res-v1'", Integer.class);
        assertThat(resolutions).isEqualTo(1);
    }
}
