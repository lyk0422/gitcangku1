package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.RestrictionReplaceRequest;
import com.example.starter.consent.dto.StructuredRecordWriteRequest;

/**
 * 字段级限制 API 集成测试：覆盖结构化写入、限制替换、投影读取的主流程、失败分支、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StructuredRecordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConsentService consentService;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM structured_record");
        jdbc.update("DELETE FROM field_restriction");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM idempotency_request");
    }

    private ResultActions grant(String requestId, String subjectKey, String purpose) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/grants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s"}
                        """.formatted(requestId, subjectKey, purpose)));
    }

    private ResultActions revoke(String requestId, String subjectKey, String purpose, int epoch) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/revocations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","epoch":%d}
                        """.formatted(requestId, subjectKey, purpose, epoch)));
    }

    private ResultActions writeStructured(String requestId, String subjectKey, String purpose,
                                          String recordKey, String fieldsJson) throws Exception {
        return mockMvc.perform(post("/api/v1/structured-records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","recordKey":"%s","fields":%s}
                        """.formatted(requestId, subjectKey, purpose, recordKey, fieldsJson)));
    }

    private ResultActions readStructured(String subjectKey, String purpose, String recordKey,
                                         String fieldsParam) throws Exception {
        var request = get("/api/v1/structured-records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey);
        if (fieldsParam != null) {
            request = request.param("fields", fieldsParam);
        }
        return mockMvc.perform(request);
    }

    private ResultActions restrict(String requestId, String subjectKey, String purpose,
                                   int expectedVersion, String forbiddenJsonArray) throws Exception {
        return mockMvc.perform(post("/api/v1/consents/restrictions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","expectedRestrictionVersion":%d,"forbiddenFields":%s}
                        """.formatted(requestId, subjectKey, purpose, expectedVersion, forbiddenJsonArray)));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：写入、限制替换、投影读取 ----------

    @Test
    void writeThenReadRoundtripAndWriteResponseCarriesNoPayload() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\",\"y\":\"beta\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields").doesNotExist())
                .andExpect(content().string(not(containsString("alpha"))));

        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields.x").value("alpha"))
                .andExpect(jsonPath("$.fields.y").value("beta"));
    }

    @Test
    void restrictThenReadProjectsAllowedFieldsAndEmptyObjectWhenAllForbidden() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\",\"y\":\"beta\"}")
                .andExpect(status().isOk());

        // 限制 x：查询仅返回 y 及限制版本 1
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1))
                .andExpect(jsonPath("$.forbiddenFields[0]").value("x"));
        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1))
                .andExpect(jsonPath("$.fields.x").doesNotExist())
                .andExpect(jsonPath("$.fields.y").value("beta"));

        // 再限制 y：返回空对象而不是旧载荷
        restrict("s-2", "subj-a", "RESEARCH", 1, "[\"x\",\"y\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2));
        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2))
                .andExpect(jsonPath("$.fields").isEmpty())
                .andExpect(content().string(not(containsString("alpha"))))
                .andExpect(content().string(not(containsString("beta"))));

        // 重放写入也不能泄露 x 或 y
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\",\"y\":\"beta\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").doesNotExist())
                .andExpect(content().string(not(containsString("alpha"))))
                .andExpect(content().string(not(containsString("beta"))));
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
    }

    @Test
    void readWithFieldsParamSelectsSubsetAndDropsForbidden() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1",
                "{\"x\":\"alpha\",\"y\":\"beta\",\"z\":\"gamma\"}").andExpect(status().isOk());

        readStructured("subj-a", "RESEARCH", "rec-1", "x,z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.x").value("alpha"))
                .andExpect(jsonPath("$.fields.z").value("gamma"))
                .andExpect(jsonPath("$.fields.y").doesNotExist());

        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        // 指定集合中的禁止字段被去除，不出现
        readStructured("subj-a", "RESEARCH", "rec-1", "x,y")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1))
                .andExpect(jsonPath("$.fields.x").doesNotExist())
                .andExpect(jsonPath("$.fields.y").value("beta"));
    }

    @Test
    void readUnknownFieldReturns400NotSilentlyIgnored() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        readStructured("subj-a", "RESEARCH", "rec-1", "x,unknown_field")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
    }

    @Test
    void restrictWithEmptyForbiddenListClearsRestriction() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(jsonPath("$.fields").isEmpty());

        // 空集合替换：版本继续递增，限制清空
        restrict("s-2", "subj-a", "RESEARCH", 1, "[]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2))
                .andExpect(jsonPath("$.forbiddenFields").isEmpty());
        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2))
                .andExpect(jsonPath("$.fields.x").value("alpha"));
    }

    // ---------- 版本与幂等 ----------

    @Test
    void restrictWithMismatchedExpectedVersionReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 3, "[\"x\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTRICTION_VERSION_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(0);
    }

    @Test
    void restrictReplaySameRequestIdReturnsOriginalVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1));
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 's-1'")).isEqualTo(1);
    }

    @Test
    void restrictSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"y\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void structuredWriteReplaySameRequestIdReturnsOriginalMetadata() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.fields").doesNotExist());
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
    }

    @Test
    void structuredWriteSameRequestIdWithDifferentFieldsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"other\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void structuredWriteSameKeyDifferentFieldsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"other\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_PAYLOAD_CONFLICT"));
        // 同 key 同字段去重，不重复写入
        writeStructured("w-3", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}")
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
    }

    // ---------- 字段校验 400 ----------

    @Test
    void invalidFieldDefinitionsReturn400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 空字段集合
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{}")
                .andExpect(status().isBadRequest());
        // 11 个字段超过上限
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-1",
                "{\"f1\":\"v\",\"f2\":\"v\",\"f3\":\"v\",\"f4\":\"v\",\"f5\":\"v\",\"f6\":\"v\","
                        + "\"f7\":\"v\",\"f8\":\"v\",\"f9\":\"v\",\"f10\":\"v\",\"f11\":\"v\"}")
                .andExpect(status().isBadRequest());
        // 字段名含非法字符
        writeStructured("w-3", "subj-a", "RESEARCH", "rec-1", "{\"bad-name\":\"v\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));
        // 字段值超过 100 字符
        writeStructured("w-4", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"" + "v".repeat(101) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));
        // 限制清单字段名非法
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"bad-name\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(0);
    }

    // ---------- 撤回、重新授权与隔离 ----------

    @Test
    void revokeMakesStructuredOperationsGoneIncludingReplay() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-2", "{\"x\":\"alpha\"}")
                .andExpect(status().isGone());
        restrict("s-2", "subj-a", "RESEARCH", 1, "[]")
                .andExpect(status().isGone());
        // 撤回后重放原写入与原限制替换也必须 410，不能凭旧快照恢复
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}")
                .andExpect(status().isGone());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isGone());
    }

    @Test
    void regrantStartsWithEmptyRestrictionList() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\",\"y\":\"beta\"}")
                .andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\",\"y\"]").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 新代次默认清单为空：同 recordKey 重写后全部字段可读，版本归零
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"new-alpha\",\"y\":\"new-beta\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.restrictionVersion").value(0));
        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields.x").value("new-alpha"))
                .andExpect(jsonPath("$.fields.y").value("new-beta"));
    }

    @Test
    void restrictionDoesNotCrossPurposes() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}").andExpect(status().isOk());
        writeStructured("w-2", "subj-a", "PERSONALIZATION", "rec-1", "{\"x\":\"personal\"}")
                .andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());

        readStructured("subj-a", "RESEARCH", "rec-1", null)
                .andExpect(jsonPath("$.fields").isEmpty());
        // 另一用途不受限制影响
        readStructured("subj-a", "PERSONALIZATION", "rec-1", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields.x").value("personal"));
    }

    @Test
    void plainStringRecordIsNotDecomposedOrRestricted() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/records")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"w-1","subjectKey":"subj-a","purpose":"RESEARCH","recordKey":"plain-1","payload":"plain-payload"}
                                """))
                .andExpect(status().isOk());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        // 普通旧字符串记录不做自动拆解，也不受字段限制影响
        mockMvc.perform(get("/api/v1/records")
                        .param("subjectKey", "subj-a")
                        .param("purpose", "RESEARCH")
                        .param("recordKey", "plain-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload").value("plain-payload"));
    }

    // ---------- 失败不占键 ----------

    @Test
    void failedStructuredWriteDoesNotConsumeRequestId() throws Exception {
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}")
                .andExpect(status().isNotFound());
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"alpha\"}")
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-1'")).isEqualTo(1);
    }

    @Test
    void failedRestrictDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 版本不匹配失败，requestId 不应被占用
        restrict("s-1", "subj-a", "RESEARCH", 7, "[\"x\"]")
                .andExpect(status().isConflict());
        restrict("s-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentRestrictSameExpectedVersionHasSingleWinner() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "s-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    return consentService.replaceRestriction(new RestrictionReplaceRequest(
                            requestId, "subj-a", Purpose.RESEARCH, 0, List.of("x"))).restrictionVersion();
                } catch (ApiException conflict) {
                    assertThat(conflict.getStatus().value()).isEqualTo(409);
                    assertThat(conflict.getCode()).isEqualTo("RESTRICTION_VERSION_CONFLICT");
                    return -1;
                }
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int winners = 0;
        for (Future<Integer> future : futures) {
            if (future.get(20, TimeUnit.SECONDS) == 1) {
                winners++;
            }
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM field_restriction WHERE version = 1")).isEqualTo(1);
    }

    @Test
    void concurrentStructuredWritesSameKeySameFieldsProduceSingleRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        Map<String, String> fields = Map.of("x", "alpha", "y", "beta");
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "w-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return consentService.writeStructured(new StructuredRecordWriteRequest(
                        requestId, "subj-a", Purpose.RESEARCH, "rec-1", fields)).recordKey();
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<String> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo("rec-1");
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'STRUCTURED_WRITE'"))
                .isEqualTo(threads);
    }
}
