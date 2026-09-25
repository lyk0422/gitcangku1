package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语冻结测试：冻结创建与查询、freezeKey 重放、批量修订事务、发布快照固化、
 * 文档版本边界、撤销语义与段落诊断。
 */
class FreezeApiTest extends AbstractIntegrationTest {

    private static final String ENTRIES_ML_NN =
            "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"machine learning\"]},"
                    + "{\"term\":\"神经网络\",\"language\":\"en\",\"allowedTranslations\":[\"neural network\"]}]";

    @Test
    @DisplayName("创建冻结：201 且条目规范化存储；当前/指定版本可查询，不存在返回 404")
    void createFreezeAndQuery() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习 与 神经网络\"}]");

        ApiResult created = createFreeze(docId, "alice",
                "[{\"term\":\" 机器学习 \",\"language\":\"EN\","
                        + "\"allowedTranslations\":[\"Machine   Learning\",\"ML\"]},"
                        + "{\"term\":\"神经网络\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"neural network\"]}]", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("documentVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.body().get("entryCount").asInt()).isEqualTo(3);
        assertThat(created.body().get("freezeKey").asText()).hasSize(64);

        ApiResult current = getJson("/api/documents/" + docId + "/freezes/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("current").asBoolean()).isTrue();
        assertThat(current.body().get("operator").asText()).isEqualTo("alice");
        assertThat(current.body().get("entries")).hasSize(2);
        var first = current.body().get("entries").get(0);
        assertThat(first.get("term").asText()).isEqualTo("机器学习");
        assertThat(first.get("language").asText()).isEqualTo("en");
        assertThat(first.get("allowedTranslations").toString())
                .contains("machine learning", "ml");
        assertThat(current.body().get("entries").get(1).get("term").asText()).isEqualTo("神经网络");

        ApiResult specified = getJson("/api/documents/" + docId + "/freezes/1");
        assertThat(specified.status()).isEqualTo(200);
        assertThat(specified.body().get("entries")).hasSize(2);

        assertThat(getJson("/api/documents/" + docId + "/freezes/2").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/freezes/current").status()).isEqualTo(404);

        long otherDoc = createDocument(newRequestId(), "[\"en\"]", "[]");
        assertThat(getJson("/api/documents/" + otherDoc + "/freezes/current").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("创建冻结失败分支：语言越界/条目重复 422，参数非法 400，同版本重复冻结 409；失败不占键")
    void createFreezeFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        ApiResult wrongLang = createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"fr\",\"allowedTranslations\":[\"apprentissage\"]}]",
                newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        // 规范化后重复的允许译法：422，且该 requestId 与 freezeKey 均不被占用
        String failKey = newRequestId();
        ApiResult duplicate = createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"ML\",\" ml  \"]}]",
                failKey);
        assertThat(duplicate.status()).isEqualTo(422);
        ApiResult fixed = createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"ML\"]}]", failKey);
        assertThat(fixed.status()).isEqualTo(201);
        assertThat(fixed.body().get("freezeVersion").asInt()).isEqualTo(1);

        // 参数校验：空允许译法数组 400、空白术语 400
        assertThat(createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[]}]",
                newRequestId()).status()).isEqualTo(400);
        assertThat(createFreeze(docId, "alice",
                "[{\"term\":\"  \",\"language\":\"en\",\"allowedTranslations\":[\"x\"]}]",
                newRequestId()).status()).isEqualTo(400);

        // 同一文档版本已存在有效冻结：409 FREEZE_CONFLICT
        ApiResult conflict = createFreeze(docId, "bob",
                "[{\"term\":\"深度学习\",\"language\":\"en\",\"allowedTranslations\":[\"deep learning\"]}]",
                newRequestId());
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("error").asText()).isEqualTo("FREEZE_CONFLICT");

        // 全部失败不产生半成品：仍只有一份冻结
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("freezeKey 同键重放：相同文档版本+条目+操作者重放原冻结；换操作者则 409")
    void freezeKeyReplay() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        ApiResult first = createFreeze(docId, "carol", ENTRIES_ML_NN, newRequestId());
        assertThat(first.status()).isEqualTo(201);

        // 同内容同操作者、不同 requestId：同键重放，不产生新冻结
        ApiResult replay = createFreeze(docId, "carol", ENTRIES_ML_NN, newRequestId());
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(replay.body().get("freezeKey").asText())
                .isEqualTo(first.body().get("freezeKey").asText());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 换操作者：指纹不同，但同文档版本已有有效冻结，409
        ApiResult otherOperator = createFreeze(docId, "dave", ENTRIES_ML_NN, newRequestId());
        assertThat(otherOperator.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("批量修订主流程：冻结下合规批次 200 整批应用，草稿版本加一，诊断全部满足")
    void batchRevisionWithFreeze() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"神经网络\"}]");
        createFreeze(docId, "alice", ENTRIES_ML_NN, newRequestId());

        ApiResult batch = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"neural network\","
                        + "\"sourceVersion\":1}]", newRequestId());
        assertThat(batch.status()).isEqualTo(200);
        assertThat(batch.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(batch.body().get("applied").asInt()).isEqualTo(2);
        assertThat(batch.body().get("revisions")).hasSize(2);
        assertThat(batch.body().get("revisions").get(0).get("translationVersion").asInt()).isEqualTo(1);

        ApiResult diagnostics = getJson("/api/documents/" + docId + "/freeze-diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("translations")).hasSize(2);
        for (var translation : diagnostics.body().get("translations")) {
            assertThat(translation.get("checks")).hasSize(1);
            assertThat(translation.get("checks").get(0).get("satisfied").asBoolean()).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("批量修订冻结违规：422 稳定列出段落与术语，整批回滚不留译文")
    void batchRevisionFreezeViolationRollsBack() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"神经网络\"}]");
        createFreeze(docId, "alice", ENTRIES_ML_NN, newRequestId());

        // 两条均违规：按段落稳定排序返回全部违规
        ApiResult both = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"bad two\",\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"bad one\","
                        + "\"sourceVersion\":1}]", newRequestId());
        assertThat(both.status()).isEqualTo(422);
        assertThat(both.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        var violations = both.body().get("freezeViolations");
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(violations.get(0).get("term").asText()).isEqualTo("机器学习");
        assertThat(violations.get(0).get("allowedTranslations").get(0).asText())
                .isEqualTo("machine learning");
        assertThat(violations.get(1).get("segmentId").asText()).isEqualTo("s2");
        assertThat(violations.get(1).get("term").asText()).isEqualTo("神经网络");

        // 部分违规：整批回滚，合规的那条也不写入
        ApiResult partial = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"bad\","
                        + "\"sourceVersion\":1}]", newRequestId());
        assertThat(partial.status()).isEqualTo(422);
        assertThat(partial.body().get("freezeViolations")).hasSize(1);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("批量修订基本校验：源文版本不匹配/批内重复/语言越界 422，段落缺失 404，均整批回滚且失败不占键")
    void batchRevisionBasicValidationAtomic() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");

        String failKey = newRequestId();
        ApiResult staleSource = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"text one\",\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"text two\","
                        + "\"sourceVersion\":5}]", failKey);
        assertThat(staleSource.status()).isEqualTo(422);
        assertThat(staleSource.body().get("error").asText()).isEqualTo("UNPROCESSABLE");

        ApiResult duplicated = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"a\",\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"content\":\"b\","
                        + "\"sourceVersion\":1}]", newRequestId());
        assertThat(duplicated.status()).isEqualTo(422);

        ApiResult wrongLang = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"fr\",\"content\":\"a\",\"sourceVersion\":1}]",
                newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        ApiResult missingSegment = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s9\",\"language\":\"en\",\"content\":\"a\",\"sourceVersion\":1}]",
                newRequestId());
        assertThat(missingSegment.status()).isEqualTo(404);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 失败不占键：同一 requestId 修正参数后成功
        ApiResult fixed = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"text one\",\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"text two\","
                        + "\"sourceVersion\":1}]", failKey);
        assertThat(fixed.status()).isEqualTo(200);
        assertThat(fixed.body().get("applied").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("未冻结文档：批量修订仍遵守既有术语规则，违规 422 且整批回滚")
    void unfrozenBatchFollowsTermRules() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"requiredTranslation\":\"machine learning\"}]", newRequestId());

        ApiResult violated = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"hello\",\"sourceVersion\":1}]",
                newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();

        ApiResult ok = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1}]", newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("revisions").get(0).get("termVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("发布固化冻结版本：违规发布 422，合规发布快照含冻结版本；撤销不重写既有快照且只影响后续")
    void publishFreezesSnapshotAndRevokeSemantics() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        // 先提交并批准（无冻结），再冻结：发布侧必须拦截不合规译文
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult freeze = createFreeze(docId, "carol",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"machine learning\"]}]",
                newRequestId());
        assertThat(freeze.status()).isEqualTo(201);

        ApiResult blocked = publish(docId, 2, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        assertThat(blocked.body().get("freezeViolations")).hasSize(1);
        assertThat(blocked.body().get("freezeViolations").get(0).get("segmentId").asText())
                .isEqualTo("s1");
        // 发布失败不产生部分快照
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 合规修订并批准后发布成功，快照固化冻结版本与条目
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("freezeTerms")).hasSize(1);
        assertThat(release.body().get("freezeTerms").get(0).get("term").asText()).isEqualTo("机器学习");
        assertThat(release.body().get("freezeTerms").get(0).get("allowedTranslations").get(0).asText())
                .isEqualTo("machine learning");

        // 撤销冻结：既有快照不重写
        ApiResult revoked = revokeFreeze(docId, 1, newRequestId());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().get("status").asText()).isEqualTo("REVOKED");
        ApiResult releaseAfterRevoke = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseAfterRevoke.body().get("freezeVersion").asInt()).isEqualTo(1);

        // 撤销只影响后续：新修订不再按冻结校验
        ApiResult afterRevoke = submitTranslation(docId, "s1", "en", "alice", "hello again", 1,
                newRequestId());
        assertThat(afterRevoke.status()).isEqualTo(200);

        // 重复撤销 409、撤销不存在 404；撤销后可再次冻结（版本号递增）
        assertThat(revokeFreeze(docId, 1, newRequestId()).status()).isEqualTo(409);
        assertThat(revokeFreeze(docId, 99, newRequestId()).status()).isEqualTo(404);
        ApiResult refrozen = createFreeze(docId, "carol",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"ML\"]}]",
                newRequestId());
        assertThat(refrozen.status()).isEqualTo(201);
        assertThat(refrozen.body().get("freezeVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("文档版本边界：源文修订后旧冻结失效不能复用，重新冻结自动撤销旧冻结")
    void documentVersionInvalidatesFreeze() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"machine learning\"]}]",
                newRequestId());

        // 源文修订：文档版本加一，旧冻结失效（current=false）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"机器学习与深度学习\"}");
        ApiResult current = getJson("/api/documents/" + docId + "/freezes/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("current").asBoolean()).isFalse();

        // 旧冻结不能复用：修订不再按旧冻结校验
        ApiResult notEnforced = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"hello\",\"sourceVersion\":2}]",
                newRequestId());
        assertThat(notEnforced.status()).isEqualTo(200);

        // 重新冻结：新版本冻结生效，旧冻结自动撤销
        ApiResult refrozen = createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"machine learning\"]}]",
                newRequestId());
        assertThat(refrozen.status()).isEqualTo(201);
        assertThat(refrozen.body().get("freezeVersion").asInt()).isEqualTo(2);
        assertThat(refrozen.body().get("documentVersion").asInt()).isEqualTo(2);
        ApiResult oldFreeze = getJson("/api/documents/" + docId + "/freezes/1");
        assertThat(oldFreeze.body().get("status").asText()).isEqualTo("REVOKED");

        // 新冻结生效：违规 422，合规 200
        ApiResult violated = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"hello again\","
                        + "\"sourceVersion\":2}]", newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        ApiResult ok = submitBatch(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":2}]", newRequestId());
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("单条译文提交同样受有效冻结约束：违规 422 返回冻结明细，合规 200")
    void singleSubmitRespectsFreeze() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "alice",
                "[{\"term\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"machine learning\"]}]",
                newRequestId());

        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        assertThat(violated.body().get("freezeViolations")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();

        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "machine learning", 1,
                newRequestId());
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("冻结诊断：无有效冻结时 freezeVersion 为 null 且核对列表为空")
    void diagnosticsWithoutEffectiveFreeze() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        ApiResult diagnostics = getJson("/api/documents/" + docId + "/freeze-diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("freezeVersion").isNull()).isTrue();
        assertThat(diagnostics.body().get("translations")).hasSize(1);
        assertThat(diagnostics.body().get("translations").get(0).get("checks")).isEmpty();

        assertThat(getJson("/api/documents/999999/freeze-diagnostics").status()).isEqualTo(404);
    }
}
