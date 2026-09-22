package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语版本绑定与违规译文拦截测试：术语命中、全量违规、版本失效、历史快照与幂等。
 */
class TermApiTest extends AbstractIntegrationTest {

    private static final String RULES_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"}]";

    @Test
    @DisplayName("新增术语版本：201，术语版本与草稿版本各加一；当前/指定版本可查询，不存在版本 404")
    void createTermVersionAndQuery() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        ApiResult created = updateTerms(docId, 0, RULES_V1, newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("ruleCount").asInt()).isEqualTo(2);
        assertThat(created.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult current = getJson("/api/documents/" + docId + "/terms");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("rules")).hasSize(2);
        assertThat(current.body().get("rules").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(current.body().get("rules").get(0).get("requiredTranslation").asText())
                .isEqualTo("machine learning");

        ApiResult specified = getJson("/api/documents/" + docId + "/terms/1");
        assertThat(specified.status()).isEqualTo(200);
        assertThat(specified.body().get("rules")).hasSize(2);

        assertThat(getJson("/api/documents/" + docId + "/terms/2").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/terms").status()).isEqualTo(404);

        // 空规则集也允许：术语版本加一、规则数为 0
        ApiResult empty = updateTerms(docId, 1, "[]", newRequestId());
        assertThat(empty.status()).isEqualTo(201);
        assertThat(empty.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(empty.body().get("ruleCount").asInt()).isEqualTo(0);
        // 旧版本不可覆盖，历史版本规则集不变
        assertThat(getJson("/api/documents/" + docId + "/terms/1").body().get("rules")).hasSize(2);
    }

    @Test
    @DisplayName("新增术语版本：期望版本不符 409；规则重复/语言越界 422；超 100 条或空必译 400")
    void createTermVersionFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        ApiResult mismatch = updateTerms(docId, 3, RULES_V1, newRequestId());
        assertThat(mismatch.status()).isEqualTo(409);

        ApiResult duplicate = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"EN\","
                        + "\"requiredTranslation\":\"machine-learning\"}]", newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        ApiResult wrongLang = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"fr\",\"requiredTranslation\":\"apprentissage\"}]",
                newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("{\"sourceTerm\":\"术语").append(i)
                    .append("\",\"language\":\"en\",\"requiredTranslation\":\"term").append(i).append("\"}");
        }
        tooMany.append(']');
        assertThat(updateTerms(docId, 0, tooMany.toString(), newRequestId()).status()).isEqualTo(400);

        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"\"}]",
                newRequestId()).status()).isEqualTo(400);
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"\",\"language\":\"en\",\"requiredTranslation\":\"x\"}]",
                newRequestId()).status()).isEqualTo(400);

        // 全部失败不占版本：当前术语版本仍为 0
        assertThat(getJson("/api/documents/" + docId + "/terms").body().get("termVersion").asInt()).isZero();
    }

    @Test
    @DisplayName("译文提交术语命中：违规 422 返回全部违规术语且不写译文；未命中术语的规则不参与校验")
    void submitTranslationTermViolation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习与神经网络\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());

        // 两条规则均命中且均违规：422 返回全部违规术语
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "hello world", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violated.body().get("violations")).hasSize(2);
        assertThat(violated.body().get("violations").toString())
                .contains("machine learning", "neural network");

        // 未写译文：术语状态查询无译文记录
        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("translations")).isEmpty();

        // 只满足部分规则仍 422，且只返回剩余违规术语
        ApiResult partial = submitTranslation(docId, "s1", "en", "alice",
                "machine learning is fun", 1, newRequestId());
        assertThat(partial.status()).isEqualTo(422);
        assertThat(partial.body().get("violations")).hasSize(1);
        assertThat(partial.body().get("violations").get(0).get("sourceTerm").asText()).isEqualTo("神经网络");

        // 全部满足：提交成功并绑定当前术语版本
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice",
                "machine learning and neural network", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("termVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("术语匹配：区分大小写连续子串；源文不含术语的规则不参与校验")
    void termMatchingIsCaseSensitiveSubstring() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"apple pie\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"Apple\",\"language\":\"en\",\"requiredTranslation\":\"Apple Inc.\"},"
                        + "{\"sourceTerm\":\"app\",\"language\":\"en\",\"requiredTranslation\":\"app\"}]",
                newRequestId());

        // "Apple" 大小写不匹配不参与校验；"app" 是 "apple" 的连续子串，命中且译文含 "app" 故通过
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "an apple", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);

        // 源文不含 "香蕉"：对应规则不参与校验
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"苹果\"}]");
        updateTerms(docId2, 0,
                "[{\"sourceTerm\":\"香蕉\",\"language\":\"en\",\"requiredTranslation\":\"banana\"}]",
                newRequestId());
        ApiResult notHit = submitTranslation(docId2, "s1", "en", "alice", "apple", 1, newRequestId());
        assertThat(notHit.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("术语版本失效：术语更新后旧译文过期发布 422，重新提交并批准后发布成功")
    void termUpdateInvalidatesTranslations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3

        // 术语更新：新增规则，术语版本 2，草稿版本 4
        ApiResult updated = updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        assertThat(updated.status()).isEqualTo(201);
        assertThat(updated.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(updated.body().get("draftVersion").asInt()).isEqualTo(4);

        // 旧译文保留但术语过期：术语状态可见，发布 422
        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        assertThat(status.body().get("termVersion").asInt()).isEqualTo(2);
        var entry = status.body().get("translations").get(0);
        assertThat(entry.get("termVersion").asInt()).isEqualTo(1);
        assertThat(entry.get("termStale").asBoolean()).isTrue();

        ApiResult stalePublish = publish(docId, 4, 0, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);

        // 旧批准不再满足发布条件：基于当前术语版本重新提交并批准
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);
        approve(docId, "s1", "en", "bob", 2, newRequestId());

        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照固化术语版本与实际规则集
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(release.body().get("terms")).hasSize(1);
        assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("termVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("历史快照固化：发布后术语更新不改写历史发布查询")
    void releaseSnapshotFreezesTerms() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 术语更新到新版本
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());

        // 历史发布查询仍返回发布时的术语版本与规则集
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("terms")).hasSize(2);
        assertThat(release.body().get("terms").toString()).contains("machine learning", "neural network");

        // 当前术语版本已切换
        ApiResult current = getJson("/api/documents/" + docId + "/terms");
        assertThat(current.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(current.body().get("rules")).hasSize(1);
    }

    @Test
    @DisplayName("术语写操作幂等：同键同参重放、同键异参 409、失败不占键")
    void termUpdateIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String requestId = newRequestId();

        ApiResult first = updateTerms(docId, 0, RULES_V1, requestId);
        assertThat(first.status()).isEqualTo(201);

        // 同键同参：重放原结果，不产生新版本
        ApiResult replay = updateTerms(docId, 0, RULES_V1, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 同键异参：409
        ApiResult conflict = updateTerms(docId, 0, "[]", requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先以某 requestId 触发 422（规则重复），再用同键修正参数后成功
        String failKey = newRequestId();
        ApiResult failed = updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ml\"}]",
                failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]", failKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("termVersion").asInt()).isEqualTo(2);

        // 术语快照、草稿版本与去重结果原子提交：失败后未残留部分版本
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("发布时术语规则复核：直接构造的违规译文在发布侧同样被拦截")
    void publishRechecksTermRules() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        // 先提交并批准（无术语版本），再建立术语版本：译文术语过期
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        updateTerms(docId, 0, RULES_V1, newRequestId());

        // 术语过期 422（当前草稿版本 3）
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 重新提交仍违反术语规则：提交侧 422，译文未被覆盖，发布侧依旧拦截
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "hello v2", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 合规提交并批准后发布成功
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
    }
}
