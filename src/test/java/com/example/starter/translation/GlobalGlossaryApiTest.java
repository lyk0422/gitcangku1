package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享全局术语库、文档级覆盖/抑制优先级与引用升级的端到端 H2 测试。
 */
class GlobalGlossaryApiTest extends AbstractIntegrationTest {

    private static final String GLOBAL_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"}]";

    @Test
    @DisplayName("全局术语库：版本从 1 起不可变快照；期望版本不符 409；重复规则 422；超 200 条 400；空集可建版")
    void globalVersionLifecycle() throws Exception {
        ApiResult created = createGlobalTerms(0, GLOBAL_V1, newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("ruleCount").asInt()).isEqualTo(2);

        ApiResult view = getGlobalTerms(1);
        assertThat(view.status()).isEqualTo(200);
        assertThat(view.body().get("rules")).hasSize(2);
        assertThat(view.body().get("rules").get(0).get("requiredTranslation").asText())
                .isEqualTo("machine learning");
        assertThat(getGlobalTerms(2).status()).isEqualTo(404);
        assertThat(getGlobalTerms(0).status()).isEqualTo(404);

        // 期望版本不符：409
        assertThat(createGlobalTerms(0, "[]", newRequestId()).status()).isEqualTo(409);
        assertThat(createGlobalTerms(9, "[]", newRequestId()).status()).isEqualTo(409);

        // 规则按 sourceTerm+语言重复：422
        assertThat(createGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"EN\",\"requiredTranslation\":\"ML\"}]",
                newRequestId()).status()).isEqualTo(422);

        // 超 200 条：400
        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("{\"sourceTerm\":\"术语").append(i)
                    .append("\",\"language\":\"en\",\"requiredTranslation\":\"term").append(i).append("\"}");
        }
        tooMany.append(']');
        assertThat(createGlobalTerms(1, tooMany.toString(), newRequestId()).status()).isEqualTo(400);

        // 空规则集建 v2；v1 快照不可覆盖
        ApiResult empty = createGlobalTerms(1, "[]", newRequestId());
        assertThat(empty.status()).isEqualTo(201);
        assertThat(empty.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(getGlobalTerms(1).body().get("rules")).hasSize(2);
        assertThat(getGlobalTerms(2).body().get("rules")).hasSize(0);
    }

    @Test
    @DisplayName("全局术语更新不属于任何文档：不改变文档 draftVersion 与引用版本")
    void globalUpdateDoesNotTouchDocuments() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        createGlobalTerms(0, GLOBAL_V1, newRequestId());

        ApiResult effective = getEffectiveTerms(docId);
        assertThat(effective.status()).isEqualTo(200);
        assertThat(effective.body().get("globalTermVersion").asInt()).isZero();
        assertThat(effective.body().get("rules")).isEmpty();
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer globalRef = jdbc.queryForObject(
                "SELECT global_term_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(1);
        assertThat(globalRef).isZero();
    }

    @Test
    @DisplayName("生效规则集：全局直出 GLOBAL；文档规则整条覆盖标 DOCUMENT；suppressed 取消全局规则标 SUPPRESSED；稳定排序")
    void effectiveSetComposition() throws Exception {
        createGlobalTerms(0, GLOBAL_V1, newRequestId());
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习与神经网络\"}]");
        // 升级引用 0→1：草稿版本 2
        ApiResult upgraded = upgradeGlobalReference(docId, 0, 1, newRequestId());
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(upgraded.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult globalOnly = getEffectiveTerms(docId);
        assertThat(globalOnly.body().get("rules")).hasSize(2);
        assertThat(globalOnly.body().get("rules")).extracting(rule -> rule.get("source").asText())
                .containsOnly("GLOBAL");

        // 文档术语 v1：机器学习整条覆盖为 ML；神经网络抑制
        ApiResult docTerms = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"suppressed\":true}]",
                newRequestId());
        assertThat(docTerms.status()).isEqualTo(201);

        ApiResult effective = getEffectiveTerms(docId);
        assertThat(effective.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(effective.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(effective.body().get("rules")).hasSize(2);
        var first = effective.body().get("rules").get(0);
        assertThat(first.get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(first.get("source").asText()).isEqualTo("DOCUMENT");
        assertThat(first.get("requiredTranslation").asText()).isEqualTo("ML");
        var second = effective.body().get("rules").get(1);
        assertThat(second.get("sourceTerm").asText()).isEqualTo("神经网络");
        assertThat(second.get("source").asText()).isEqualTo("SUPPRESSED");
        assertThat(second.get("requiredTranslation").isNull()).isTrue();

        // 文档原始术语版本视图保留 suppressed 标记与空必译文本
        ApiResult rawDocTerms = getJson("/api/documents/" + docId + "/terms/1");
        assertThat(rawDocTerms.body().get("rules").get(1).get("suppressed").asBoolean()).isTrue();
        assertThat(rawDocTerms.body().get("rules").get(1).get("requiredTranslation").isNull()).isTrue();
    }

    @Test
    @DisplayName("suppressed 入参校验：抑制规则携带 requiredTranslation 返回 400；普通规则缺必译文本返回 400")
    void suppressedValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\",\"suppressed\":true}]",
                newRequestId()).status()).isEqualTo(400);
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"suppressed\":true}]",
                newRequestId()).status()).isEqualTo(201);
        // 无对应全局规则的抑制声明也在生效集中标明 SUPPRESSED
        assertThat(getEffectiveTerms(docId).body().get("rules").get(0).get("source").asText())
                .isEqualTo("SUPPRESSED");

        long docId2 = createDocument(newRequestId(), "[\"en\"]", "[]");
        assertThat(updateTerms(docId2, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"\"}]",
                newRequestId()).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("译文校验基于生效规则集：覆盖后按 DOCUMENT 校验；被抑制术语不参与校验；违规 422 带来源")
    void submitUsesEffectiveRulesAndSource() throws Exception {
        // 全局 v1 与文档覆盖/抑制
        createGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"},"
                        + "{\"sourceTerm\":\"云\",\"language\":\"en\",\"requiredTranslation\":\"cloud\"}]",
                newRequestId());
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习与神经网络在云端\"}]");
        upgradeGlobalReference(docId, 0, 1, newRequestId());
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"suppressed\":true}]",
                newRequestId());

        // 按旧全局必译翻译且缺 cloud：违规 2 条；机器学习来源 DOCUMENT，云来源 GLOBAL；神经网络被抑制不报
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice",
                "machine learning neural network", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violated.body().get("violations")).hasSize(2);
        assertThat(violated.body().get("violations").toString())
                .contains("\"source\":\"DOCUMENT\"", "\"source\":\"GLOBAL\"", "ML", "cloud");
        assertThat(violated.body().get("violations").toString()).doesNotContain("neural network");

        // 失败不写译文
        assertThat(getJson("/api/documents/" + docId + "/terms/status").body().get("translations")).isEmpty();

        // 满足 DOCUMENT 覆盖值 ML，被抑制术语免译，GLOBAL 的 cloud 必译
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "ML in the cloud", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("globalTermVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("引用落后：新版全局库出现后旧批准不再满足发布条件（422）；显式升级后译文过期，重译重批方可发布")
    void staleReferenceBlocksPublishUntilUpgrade() throws Exception {
        createGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        upgradeGlobalReference(docId, 0, 1, newRequestId());
        // 草稿版本：建文档 1 → 升级 2 → 提交 3（批准不增草稿）
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 全局 v2 出现：文档引用落后，重发被拒，术语状态标过期
        createGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        ApiResult stalePublish = publish(docId, 3, 1, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);

        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        var entry = status.body().get("translations").get(0);
        assertThat(entry.get("termStale").asBoolean()).isTrue();
        assertThat(entry.get("globalTermVersion").asInt()).isEqualTo(1);

        // 期望引用版本不符 409；目标不大于当前 422；目标版本不存在 404
        assertThat(upgradeGlobalReference(docId, 0, 2, newRequestId()).status()).isEqualTo(409);
        assertThat(upgradeGlobalReference(docId, 1, 1, newRequestId()).status()).isEqualTo(422);
        assertThat(upgradeGlobalReference(docId, 1, 99, newRequestId()).status()).isEqualTo(404);

        // 显式升级 1→2：草稿版本 4；旧译文绑定全局版本 1，发布仍 422
        ApiResult upgraded = upgradeGlobalReference(docId, 1, 2, newRequestId());
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("draftVersion").asInt()).isEqualTo(4);
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(422);

        // 旧批准不再有效：按新生效规则重译（v2 要求 ML）并重批后发布
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(resubmit.body().get("globalTermVersion").asInt()).isEqualTo(2);
        // 旧译文批准失效：直接发布仍 422
        assertThat(publish(docId, 5, 1, newRequestId()).status()).isEqualTo(422);
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult second = publish(docId, 5, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("发布快照固化两个术语版本与实际生效规则集（含来源），后续更新不改写历史查询")
    void releaseSnapshotFreezesBothVersionsAndEffectiveSet() throws Exception {
        createGlobalTerms(0, GLOBAL_V1, newRequestId());
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习与神经网络\"}]");
        upgradeGlobalReference(docId, 0, 1, newRequestId());
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"suppressed\":true}]",
                newRequestId());
        // 草稿版本 1 建文档 → 2 升级 → 3 文档术语 → 4 提交（批准不增）
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // 全局 v2、文档术语 v2 并升级引用
        createGlobalTerms(1, "[]", newRequestId());
        upgradeGlobalReference(docId, 1, 2, newRequestId());
        updateTerms(docId, 1, "[]", newRequestId());

        // 历史发布 1 仍固化 globalTermVersion=1、文档 termVersion=1 与当时生效集
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.status()).isEqualTo(200);
        assertThat(release1.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(release1.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(release1.body().get("terms")).hasSize(2);
        assertThat(release1.body().get("terms").toString())
                .contains("\"source\":\"DOCUMENT\"", "\"source\":\"SUPPRESSED\"", "ML");
        assertThat(release1.body().get("terms").toString()).doesNotContain("GLOBAL");
        assertThat(release1.body().get("segments").get(0).get("translations").get(0)
                .get("globalTermVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("全局术语写与引用升级幂等：同参重放、异参 409、失败不占键")
    void globalWritesIdempotency() throws Exception {
        // 全局术语库创建幂等
        String globalKey = newRequestId();
        ApiResult first = createGlobalTerms(0, GLOBAL_V1, globalKey);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = createGlobalTerms(0, GLOBAL_V1, globalKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(createGlobalTerms(0, "[]", globalKey).status()).isEqualTo(409);
        // 失败不占键：先 422（规则重复），同键修正后成功为 v2
        String failKey = newRequestId();
        assertThat(createGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                failKey).status()).isEqualTo(422);
        ApiResult retried = createGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]", failKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("globalTermVersion").asInt()).isEqualTo(2);

        // 引用升级幂等：文档先升到 1，再用同键重放升级 1→2
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        upgradeGlobalReference(docId, 0, 1, newRequestId());
        String upgradeKey = newRequestId();
        ApiResult upgraded = upgradeGlobalReference(docId, 1, 2, upgradeKey);
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("draftVersion").asInt()).isEqualTo(3);
        ApiResult upgradeReplay = upgradeGlobalReference(docId, 1, 2, upgradeKey);
        assertThat(upgradeReplay.status()).isEqualTo(200);
        assertThat(upgradeReplay.body().get("globalTermVersion").asInt()).isEqualTo(2);
        Integer draftAfterReplay = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftAfterReplay).isEqualTo(3);
        // 同键异参 409
        assertThat(upgradeGlobalReference(docId, 1, 1, upgradeKey).status()).isEqualTo(409);
        // 升级失败不占键：期望版本不符 409 后同键改用正确参数成功（2→不可再升，故新建文档演示）
        long docId2 = createDocument(newRequestId(), "[\"en\"]", "[]");
        String upgradeFailKey = newRequestId();
        assertThat(upgradeGlobalReference(docId2, 5, 2, upgradeFailKey).status()).isEqualTo(409);
        ApiResult upgradeRetry = upgradeGlobalReference(docId2, 0, 2, upgradeFailKey);
        assertThat(upgradeRetry.status()).isEqualTo(200);
    }
}
