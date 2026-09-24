package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局术语库测试：版本递增、不可变快照、失败分支、与文档草稿版本隔离及幂等。
 */
class GlobalGlossaryApiTest extends AbstractIntegrationTest {

    private static final String GLOBAL_RULES_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"}]";

    @Test
    @DisplayName("新增全局术语库版本：201 且版本从 1 递增；当前/指定版本可查询，不存在版本 404；已有版本不可覆盖")
    void createGlobalVersionAndQuery() throws Exception {
        ApiResult created = updateGlobalTerms(0, GLOBAL_RULES_V1, newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("ruleCount").asInt()).isEqualTo(2);

        ApiResult current = getJson("/api/glossary/terms");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("rules")).hasSize(2);
        assertThat(current.body().get("rules").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(current.body().get("rules").get(0).get("source")).isNull();

        ApiResult specified = getJson("/api/glossary/terms/1");
        assertThat(specified.status()).isEqualTo(200);
        assertThat(specified.body().get("rules")).hasSize(2);
        assertThat(getJson("/api/glossary/terms/2").status()).isEqualTo(404);

        // 空规则集也允许：版本加一、规则数为 0
        ApiResult empty = updateGlobalTerms(1, "[]", newRequestId());
        assertThat(empty.status()).isEqualTo(201);
        assertThat(empty.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(empty.body().get("ruleCount").asInt()).isZero();
        // 旧版本不可覆盖，历史版本规则集不变
        assertThat(getJson("/api/glossary/terms/1").body().get("rules")).hasSize(2);
        assertThat(getJson("/api/glossary/terms").body().get("globalTermVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("新增全局术语库版本：期望版本不符 409；规则重复 422；超 200 条或空必译 400；失败不占版本")
    void createGlobalVersionFailures() throws Exception {
        ApiResult mismatch = updateGlobalTerms(3, GLOBAL_RULES_V1, newRequestId());
        assertThat(mismatch.status()).isEqualTo(409);

        ApiResult duplicate = updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"EN\","
                        + "\"requiredTranslation\":\"machine-learning\"}]", newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("{\"sourceTerm\":\"术语").append(i)
                    .append("\",\"language\":\"en\",\"requiredTranslation\":\"term").append(i).append("\"}");
        }
        tooMany.append(']');
        assertThat(updateGlobalTerms(0, tooMany.toString(), newRequestId()).status()).isEqualTo(400);

        assertThat(updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"\"}]",
                newRequestId()).status()).isEqualTo(400);
        assertThat(updateGlobalTerms(0,
                "[{\"sourceTerm\":\"\",\"language\":\"en\",\"requiredTranslation\":\"x\"}]",
                newRequestId()).status()).isEqualTo(400);

        // 全部失败不占版本：当前全局版本仍为 0
        assertThat(getJson("/api/glossary/terms").body().get("globalTermVersion").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_term_version", Integer.class)).isZero();
    }

    @Test
    @DisplayName("全局术语库不属于任何文档：全局版本更新不改变文档 draftVersion")
    void globalUpdateDoesNotTouchDocumentDraft() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        Integer draftBefore = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);

        assertThat(updateGlobalTerms(0, GLOBAL_RULES_V1, newRequestId()).status()).isEqualTo(201);
        assertThat(updateGlobalTerms(1, "[]", newRequestId()).status()).isEqualTo(201);

        Integer draftAfter = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftAfter).isEqualTo(draftBefore);
    }

    @Test
    @DisplayName("全局术语写操作幂等：同键同参重放、同键异参 409、失败不占键")
    void globalUpdateIdempotency() throws Exception {
        String requestId = newRequestId();
        ApiResult first = updateGlobalTerms(0, GLOBAL_RULES_V1, requestId);
        assertThat(first.status()).isEqualTo(201);

        // 同键同参：重放原结果，不产生新版本
        ApiResult replay = updateGlobalTerms(0, GLOBAL_RULES_V1, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_term_version", Integer.class)).isEqualTo(1);

        // 同键异参：409
        assertThat(updateGlobalTerms(0, "[]", requestId).status()).isEqualTo(409);

        // 失败不占键：先以某 requestId 触发 409（期望版本不符），再用同键修正参数后成功
        String failKey = newRequestId();
        assertThat(updateGlobalTerms(5, GLOBAL_RULES_V1, failKey).status()).isEqualTo(409);
        ApiResult retried = updateGlobalTerms(1, GLOBAL_RULES_V1, failKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("globalTermVersion").asInt()).isEqualTo(2);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_term_version", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT current_version FROM global_glossary_state WHERE state_id = 1", Integer.class))
                .isEqualTo(2);
    }
}
