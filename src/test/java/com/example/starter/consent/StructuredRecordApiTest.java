package com.example.starter.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.starter.consent.dto.FieldRestrictionReplaceRequest;
import com.example.starter.consent.dto.StructuredRecordWriteRequest;

/**
 * 结构化记录与字段级限制 API 集成测试：覆盖主流程、失败分支、幂等与并发边界。
 * 使用独立命名的 H2 内存库（MODE=MySQL），仅同 JVM 状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
class StructuredRecordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StructuredRecordService structuredRecordService;

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
                                         String... fields) throws Exception {
        var request = get("/api/v1/structured-records")
                .param("subjectKey", subjectKey)
                .param("purpose", purpose)
                .param("recordKey", recordKey);
        for (String field : fields) {
            request = request.param("fields", field);
        }
        return mockMvc.perform(request);
    }

    private ResultActions replaceRestriction(String requestId, String subjectKey, String purpose,
                                             int expectedVersion, String restrictedJsonArray) throws Exception {
        return mockMvc.perform(post("/api/v1/field-restrictions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"%s","subjectKey":"%s","purpose":"%s","expectedRestrictionVersion":%d,"restrictedFields":%s}
                        """.formatted(requestId, subjectKey, purpose, expectedVersion, restrictedJsonArray)));
    }

    private int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }

    // ---------- 主流程：写入、读取、限制投影 ----------

    @Test
    void writeThenReadReturnsAllFieldsWithVersionZero() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.epoch").value(1))
                .andExpect(jsonPath("$.restrictionVersion").value(0));
        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields.x").value("a"))
                .andExpect(jsonPath("$.fields.y").value("b"));
    }

    @Test
    void writeResponseCarriesOnlyIdAndVersionMetadata() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").doesNotExist())
                .andExpect(jsonPath("$.payload").doesNotExist());
    }

    @Test
    void restrictFieldXThenReadReturnsOnlyYAndRestrictionVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1))
                .andExpect(jsonPath("$.restrictedFields[0]").value("x"));

        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1))
                .andExpect(jsonPath("$.fields.y").value("b"))
                .andExpect(jsonPath("$.fields.x").doesNotExist());
    }

    @Test
    void restrictAllFieldsReturnsEmptyObjectWithCurrentVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        replaceRestriction("fr-2", "subj-a", "RESEARCH", 1, "[\"x\",\"y\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2));

        // 全部被禁止：返回空对象而不是旧载荷，版本对应实际投影
        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2))
                .andExpect(jsonPath("$.fields").isEmpty())
                .andExpect(jsonPath("$.fields.x").doesNotExist())
                .andExpect(jsonPath("$.fields.y").doesNotExist());
    }

    @Test
    void replayedWriteCannotLeakRestrictedFields() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\",\"y\"]").andExpect(status().isOk());

        // 重放首次写入：返回首次完整响应（仅标识与版本元数据），不含字段内容
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields").doesNotExist())
                .andExpect(jsonPath("$.payload").doesNotExist());
        // 读取仍按当前清单投影，x 与 y 均不出现
        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").isEmpty());
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
    }

    @Test
    void readWithSelectedFieldsReturnsOnlyAllowedSubset() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1",
                "{\"x\":\"a\",\"y\":\"b\",\"z\":\"c\"}").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());

        // 指定字段集合：x 被禁止不出现，y 正常返回
        readStructured("subj-a", "RESEARCH", "rec-1", "x", "y")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1))
                .andExpect(jsonPath("$.fields.y").value("b"))
                .andExpect(jsonPath("$.fields.x").doesNotExist())
                .andExpect(jsonPath("$.fields.z").doesNotExist());
    }

    @Test
    void emptyRestrictionListIsAllowedAndClearsProjection() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        // 禁止字段集合可为空：替换为空清单后所有字段恢复可见
        replaceRestriction("fr-2", "subj-a", "RESEARCH", 1, "[]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2))
                .andExpect(jsonPath("$.restrictedFields").isEmpty());
        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(2))
                .andExpect(jsonPath("$.fields.x").value("a"));
    }

    // ---------- 400 分支：字段校验与未知字段 ----------

    @Test
    void unknownRequestedFieldReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        readStructured("subj-a", "RESEARCH", "rec-1", "x", "zzz")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"));
    }

    @Test
    void invalidFieldNameReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"bad-name\":\"a\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));
    }

    @Test
    void oversizedFieldValueReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        String tooLong = "v".repeat(101);
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"" + tooLong + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));
    }

    @Test
    void emptyOrTooManyFieldsReturn400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{}")
                .andExpect(status().isBadRequest());
        StringBuilder eleven = new StringBuilder("{");
        for (int i = 0; i < 11; i++) {
            eleven.append(i == 0 ? "" : ",").append("\"f").append(i).append("\":\"v\"");
        }
        eleven.append("}");
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-1", eleven.toString())
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRestrictionFieldNameReturns400() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"bad-name\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FIELD"));
    }

    // ---------- 409 分支：版本冲突与参数冲突 ----------

    @Test
    void restrictionVersionMismatchReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 3, "[\"x\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTRICTION_VERSION_CONFLICT"));
        replaceRestriction("fr-2", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        // 旧期望版本不再接受：更严格限制生效后旧凭据不能回退
        replaceRestriction("fr-3", "subj-a", "RESEARCH", 0, "[\"y\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTRICTION_VERSION_CONFLICT"));
    }

    @Test
    void restrictionReplaySameParamsReturnsOriginalVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1));
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(1);
    }

    @Test
    void restrictionSameRequestIdWithDifferentParamsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"y\"]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    @Test
    void writeSameKeyDifferentFieldsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"b\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECORD_PAYLOAD_CONFLICT"));
    }

    @Test
    void writeSameRequestIdWithDifferentFieldsReturns409() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-2", "{\"x\":\"a\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ID_CONFLICT"));
    }

    // ---------- 撤回、重新授权与用途隔离 ----------

    @Test
    void revokeMakesStructuredOperationsGone() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());

        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-2", "{\"x\":\"a\"}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
        // 撤回后重放原写入请求（同 requestId 同参数）也必须被拒绝
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CONSENT_REVOKED"));
    }

    @Test
    void regrantStartsWithEmptyRestrictionList() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());
        revoke("r-1", "subj-a", "RESEARCH", 1).andExpect(status().isOk());
        grant("g-2", "subj-a", "RESEARCH").andExpect(status().isOk());

        // 新代次默认无限制清单：同名字段全部可见，版本为 0
        writeStructured("w-2", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\",\"y\":\"b\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(2))
                .andExpect(jsonPath("$.restrictionVersion").value(0));
        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields.x").value("a"))
                .andExpect(jsonPath("$.fields.y").value("b"));
    }

    @Test
    void restrictionsDoNotCrossPurposes() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        grant("g-2", "subj-a", "PERSONALIZATION").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        writeStructured("w-2", "subj-a", "PERSONALIZATION", "rec-1", "{\"x\":\"a\"}")
                .andExpect(status().isOk());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]").andExpect(status().isOk());

        readStructured("subj-a", "RESEARCH", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields").isEmpty());
        // 另一用途不继承限制
        readStructured("subj-a", "PERSONALIZATION", "rec-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(0))
                .andExpect(jsonPath("$.fields.x").value("a"));
    }

    // ---------- 幂等边界 ----------

    @Test
    void writeReplaySameRequestIdReturnsIdenticalResponse() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}").andExpect(status().isOk());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordKey").value("rec-1"))
                .andExpect(jsonPath("$.epoch").value(1));
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-1'")).isEqualTo(1);
    }

    @Test
    void failedWriteDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 字段非法导致 400，requestId 不应被占用
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"bad-name\":\"a\"}")
                .andExpect(status().isBadRequest());
        writeStructured("w-1", "subj-a", "RESEARCH", "rec-1", "{\"x\":\"a\"}")
                .andExpect(status().isOk());
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'w-1'")).isEqualTo(1);
    }

    @Test
    void failedRestrictionReplaceDoesNotConsumeRequestId() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        // 期望版本错误导致 409，requestId 不应被占用
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 5, "[\"x\"]")
                .andExpect(status().isConflict());
        replaceRestriction("fr-1", "subj-a", "RESEARCH", 0, "[\"x\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictionVersion").value(1));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentRestrictionReplaceProducesSingleVersion() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "fr-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    return structuredRecordService.replaceRestriction(new FieldRestrictionReplaceRequest(
                            requestId, "subj-a", Purpose.RESEARCH, 0, List.of("x")))
                            .restrictionVersion() + "";
                } catch (ApiException e) {
                    return e.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        int successes = 0;
        int conflicts = 0;
        for (Future<String> future : futures) {
            String outcome = future.get(20, TimeUnit.SECONDS);
            if ("1".equals(outcome)) {
                successes++;
            } else if ("RESTRICTION_VERSION_CONFLICT".equals(outcome)) {
                conflicts++;
            }
        }
        pool.shutdown();
        // 同版本并发替换只有一个获胜，其余 409，不产生重复版本行
        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(threads - 1);
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(1);
    }

    @Test
    void concurrentStructuredWritesSameKeyProduceSingleRecord() throws Exception {
        grant("g-1", "subj-a", "RESEARCH").andExpect(status().isOk());
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "sw-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                return structuredRecordService.write(new StructuredRecordWriteRequest(
                        requestId, "subj-a", Purpose.RESEARCH, "rec-1",
                        java.util.Map.of("x", "a", "y", "b"))).epoch();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(1);
        }
        pool.shutdown();
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE operation = 'STRUCTURED_WRITE'"))
                .isEqualTo(threads);
    }

    // ---------- 主 schema 与本地入口兼容性 ----------

    @Test
    void mainSchemaExecutesOnH2MysqlMode() throws Exception {
        // 本地入口使用主 schema.sql 在 H2（MODE=MySQL）上自动建表；此处实际执行验证语法兼容
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("src/main/resources/schema.sql"));
        }
        assertThat(count("SELECT COUNT(*) FROM field_restriction")).isEqualTo(0);
        assertThat(count("SELECT COUNT(*) FROM structured_record")).isEqualTo(0);
    }
}
