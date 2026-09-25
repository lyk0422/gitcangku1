package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语冻结测试：冻结创建与查询、freezeKey 幂等、批量修订门禁、发布快照固化、
 * 撤销语义、术语版本边界与段落诊断。
 */
class FreezeApiTest extends AbstractIntegrationTest {

    private static final String FREEZE_ENTRIES =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                    + "\"allowedTranslations\":[\"machine learning\",\"ML\"]},"
                    + "{\"sourceTerm\":\"深度学习\",\"language\":\"en\","
                    + "\"allowedTranslations\":[\"deep learning\"]}]";

    @Test
    @DisplayName("创建冻结：201 且条目规范化（去空白、译法去重排序）；当前/指定版本可查询；同版本重复冻结 409")
    void createFreezeAndQuery() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");

        ApiResult created = createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\" 机器学习 \",\"language\":\"EN\","
                        + "\"allowedTranslations\":[\"machine learning\",\"ML\",\"machine learning\"]}]",
                "alice", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("termVersion").asInt()).isZero();
        assertThat(created.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(created.body().get("entryCount").asInt()).isEqualTo(1);
        assertThat(created.body().get("fingerprint").asText()).hasSize(64);

        ApiResult current = getJson("/api/documents/" + docId + "/freeze");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("createdBy").asText()).isEqualTo("alice");
        assertThat(current.body().get("entries")).hasSize(1);
        var entry = current.body().get("entries").get(0);
        assertThat(entry.get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(entry.get("language").asText()).isEqualTo("en");
        assertThat(entry.get("allowedTranslations")).hasSize(2);
        assertThat(entry.get("allowedTranslations").get(0).asText()).isEqualTo("ML");
        assertThat(entry.get("allowedTranslations").get(1).asText()).isEqualTo("machine learning");

        ApiResult specified = getJson("/api/documents/" + docId + "/freeze/1");
        assertThat(specified.status()).isEqualTo(200);
        assertThat(specified.body().get("entries")).hasSize(1);

        // 同一术语版本只允许一份有效冻结
        ApiResult duplicate = createFreeze(docId, "fk-2", FREEZE_ENTRIES, "bob", newRequestId());
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().get("error").asText()).isEqualTo("FREEZE_EXISTS");

        // 不存在的资源
        assertThat(getJson("/api/documents/" + docId + "/freeze/99").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/freeze").status()).isEqualTo(404);
        assertThat(createFreeze(999999, "fk-3", FREEZE_ENTRIES, "alice", newRequestId()).status())
                .isEqualTo(404);

        // 未冻结文档查询当前冻结：404 NO_ACTIVE_FREEZE
        long docId2 = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult none = getJson("/api/documents/" + docId2 + "/freeze");
        assertThat(none.status()).isEqualTo(404);
        assertThat(none.body().get("error").asText()).isEqualTo("NO_ACTIVE_FREEZE");
    }

    @Test
    @DisplayName("创建冻结校验：语言越界/条目重复 422，空译法/空术语 400；失败不占 freezeKey")
    void createFreezeValidationFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        ApiResult wrongLang = createFreeze(docId, "fk-a",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"fr\",\"allowedTranslations\":[\"x\"]}]",
                "alice", newRequestId());
        assertThat(wrongLang.status()).isEqualTo(422);

        ApiResult duplicate = createFreeze(docId, "fk-b",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"ML\"]},"
                        + "{\"sourceTerm\":\" 机器学习 \",\"language\":\"EN\","
                        + "\"allowedTranslations\":[\"machine learning\"]}]",
                "alice", newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        assertThat(createFreeze(docId, "fk-c",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[]}]",
                "alice", newRequestId()).status()).isEqualTo(400);
        assertThat(createFreeze(docId, "fk-d",
                "[{\"sourceTerm\":\"\",\"language\":\"en\",\"allowedTranslations\":[\"ML\"]}]",
                "alice", newRequestId()).status()).isEqualTo(400);
        assertThat(createFreeze(docId, "fk-e", "[]", "alice", newRequestId()).status()).isEqualTo(400);

        // 失败不占键：上述 freezeKey 均可被修正后的请求复用
        ApiResult retried = createFreeze(docId, "fk-b",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"ML\"]}]",
                "alice", newRequestId());
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("freezeKey 幂等：同键同指纹重放原结果，同键异指纹（条目或操作者不同）409")
    void freezeKeyIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String entries = "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                + "\"allowedTranslations\":[\"machine learning\"]}]";

        ApiResult first = createFreeze(docId, "fk-replay", entries, "alice", newRequestId());
        assertThat(first.status()).isEqualTo(201);
        String fingerprint = first.body().get("fingerprint").asText();

        // 新 requestId + 同 freezeKey + 同内容：重放原结果，不产生新冻结
        ApiResult replay = createFreeze(docId, "fk-replay", entries, "alice", newRequestId());
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(replay.body().get("fingerprint").asText()).isEqualTo(fingerprint);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_freeze", Integer.class)).isEqualTo(1);

        // 同键异条目：409 FREEZE_KEY_CONFLICT
        ApiResult conflict = createFreeze(docId, "fk-replay",
                "[{\"sourceTerm\":\"深度学习\",\"language\":\"en\",\"allowedTranslations\":[\"DL\"]}]",
                "alice", newRequestId());
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("error").asText()).isEqualTo("FREEZE_KEY_CONFLICT");

        // 同键同条目但操作者不同：指纹含操作者，409
        ApiResult otherActor = createFreeze(docId, "fk-replay", entries, "bob", newRequestId());
        assertThat(otherActor.status()).isEqualTo(409);
        assertThat(otherActor.body().get("error").asText()).isEqualTo("FREEZE_KEY_CONFLICT");
    }

    @Test
    @DisplayName("批量修订冻结门禁：任一违反 422 并稳定列出段落与术语，整批回滚不写译文；合规批次原子成功")
    void batchRevisionFreezeGate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"深度学习\"}]");
        updateTerms(docId, 0, "[]", newRequestId());
        createFreeze(docId, "fk-1", FREEZE_ENTRIES, "alice", newRequestId());
        // 当前草稿版本 2

        // 两条修订均违反冻结：按段落稳定排序返回全部违规（请求中 s2 在前，响应仍 s1 在前）
        ApiResult violated = submitRevisionBatch(docId,
                "[{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"DL\",\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"bad\",\"sourceVersion\":1}]",
                "alice", newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        var violations = violated.body().get("freezeViolations");
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(violations.get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(violations.get(0).get("allowedTranslations").toString())
                .contains("ML", "machine learning");
        assertThat(violations.get(1).get("segmentId").asText()).isEqualTo("s2");
        assertThat(violations.get(1).get("sourceTerm").asText()).isEqualTo("深度学习");

        // 整批回滚：无译文写入，草稿版本不变
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);

        // 部分违反同样整批回滚
        ApiResult partial = submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"DL\","
                        + "\"sourceVersion\":1}]",
                "alice", newRequestId());
        assertThat(partial.status()).isEqualTo(422);
        assertThat(partial.body().get("freezeViolations")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 全部合规：整批原子成功，草稿版本只加一
        ApiResult ok = submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"deep learning\","
                        + "\"sourceVersion\":1}]",
                "alice", newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(3);
        assertThat(ok.body().get("results")).hasSize(2);
        assertThat(ok.body().get("results").get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("批量修订基础校验：语言越界/批内重复/源文版本不符 422，段落不存在 404，均不落数据")
    void batchRevisionValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");

        assertThat(submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"fr\",\"content\":\"x\",\"sourceVersion\":1}]",
                "alice", newRequestId()).status()).isEqualTo(422);
        assertThat(submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"a\",\"sourceVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"content\":\"b\","
                        + "\"sourceVersion\":1}]",
                "alice", newRequestId()).status()).isEqualTo(422);
        assertThat(submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"x\",\"sourceVersion\":9}]",
                "alice", newRequestId()).status()).isEqualTo(422);
        assertThat(submitRevisionBatch(docId,
                "[{\"segmentId\":\"sx\",\"language\":\"en\",\"content\":\"x\",\"sourceVersion\":1}]",
                "alice", newRequestId()).status()).isEqualTo(404);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("批量修订幂等：失败不占 requestId，同键重放不重复写入")
    void batchRevisionIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"machine learning\"]}]",
                "alice", newRequestId());

        // 失败不占键：先以 requestId 触发 422，再用同键修正内容后成功
        String requestId = newRequestId();
        ApiResult failed = submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"bad\",\"sourceVersion\":1}]",
                "alice", requestId);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1}]",
                "alice", requestId);
        assertThat(retried.status()).isEqualTo(200);

        // 同键同参重放：返回原结果，译文版本不重复递增
        ApiResult replay = submitRevisionBatch(docId,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1}]",
                "alice", requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("results").get(0).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("单条译文提交冻结门禁：违反 422 FREEZE_VIOLATION 且不写译文，合规提交正常")
    void singleSubmitFreezeGate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"machine learning\",\"ML\"]}]",
                "alice", newRequestId());

        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        assertThat(violated.body().get("freezeViolations")).hasSize(1);
        assertThat(violated.body().get("freezeViolations").get(0).get("segmentId").asText())
                .isEqualTo("s1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 允许译法之一即合规
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "I like ML", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("发布冻结门禁与快照固化：违规发布 422 无快照；合规发布快照固化冻结版本与条目")
    void publishFreezeGateAndSnapshot() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        // 冻结前已存在的译文不受冻结约束写入
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"machine learning\"]}]",
                "alice", newRequestId());
        // 当前草稿版本 2

        // 既有译文违反冻结：发布 422 且稳定列出段落与术语，不产生快照
        ApiResult blocked = publish(docId, 2, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
        assertThat(blocked.body().get("freezeViolations")).hasSize(1);
        assertThat(blocked.body().get("freezeViolations").get(0).get("sourceTerm").asText())
                .isEqualTo("机器学习");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 修正译文并重新批准后发布成功
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照固化所用冻结版本与冻结条目
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("freezeEntries")).hasSize(1);
        assertThat(release.body().get("freezeEntries").get(0).get("sourceTerm").asText())
                .isEqualTo("机器学习");
        assertThat(release.body().get("freezeEntries").get(0).get("allowedTranslations").toString())
                .contains("machine learning");
    }

    @Test
    @DisplayName("撤销冻结：后续修订与发布不再受门禁，既有快照不重写；重复撤销 409，历史冻结可查")
    void revokeFreezeSemantics() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"machine learning\"]}]",
                "alice", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        ApiResult revoked = revokeFreeze(docId, 1, "carol", newRequestId());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().get("status").asText()).isEqualTo("REVOKED");

        // 撤销后当前冻结查询 404，历史冻结仍可查且状态为 REVOKED
        assertThat(getJson("/api/documents/" + docId + "/freeze").status()).isEqualTo(404);
        ApiResult history = getJson("/api/documents/" + docId + "/freeze/1");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("status").asText()).isEqualTo("REVOKED");

        // 撤销只影响后续：此前违反冻结的译文现在可以提交并发布
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 3, 1, newRequestId()).status()).isEqualTo(201);

        // 新快照无冻结（freezeVersion 为 null），既有快照仍固化冻结版本 1
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("freezeVersion").isNull()).isTrue();
        assertThat(release2.body().get("freezeEntries")).isEmpty();
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("freezeVersion").asInt()).isEqualTo(1);

        // 重复撤销 409 FREEZE_ALREADY_REVOKED；撤销不存在的冻结 404
        ApiResult again = revokeFreeze(docId, 1, "carol", newRequestId());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("error").asText()).isEqualTo("FREEZE_ALREADY_REVOKED");
        assertThat(revokeFreeze(docId, 99, "carol", newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("术语版本边界：新增术语版本后旧冻结失效需重新冻结，旧冻结不重写、按版本可查")
    void termVersionBoundary() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"requiredTranslation\":\"machine learning\"}]", newRequestId());
        ApiResult frozen = createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"machine learning\"]}]",
                "alice", newRequestId());
        assertThat(frozen.body().get("termVersion").asInt()).isEqualTo(1);

        // 文档创建新术语版本：旧冻结不能复用
        updateTerms(docId, 1, "[]", newRequestId());
        ApiResult stale = getJson("/api/documents/" + docId + "/freeze");
        assertThat(stale.status()).isEqualTo(404);
        assertThat(stale.body().get("error").asText()).isEqualTo("NO_ACTIVE_FREEZE");

        // 旧冻结不再约束修订（新术语版本规则为空）
        ApiResult submit = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(submit.status()).isEqualTo(200);

        // 旧冻结按版本仍可查询，状态保持 ACTIVE 记录但不再生效
        ApiResult old = getJson("/api/documents/" + docId + "/freeze/1");
        assertThat(old.status()).isEqualTo(200);
        assertThat(old.body().get("termVersion").asInt()).isEqualTo(1);

        // 新术语版本可重新冻结，冻结版本递增
        ApiResult refrozen = createFreeze(docId, "fk-2",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"allowedTranslations\":[\"ML\"]}]",
                "alice", newRequestId());
        assertThat(refrozen.status()).isEqualTo(201);
        assertThat(refrozen.body().get("freezeVersion").asInt()).isEqualTo(2);
        assertThat(refrozen.body().get("termVersion").asInt()).isEqualTo(2);

        // 新冻结立即生效
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice", "hello again", 1,
                newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("FREEZE_VIOLATION");
    }

    @Test
    @DisplayName("段落诊断：命中术语的段落逐语言列出合规情况，缺译文为 null 版本，未命中段落不出现")
    void freezeDiagnostics() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"普通句子\"}]");
        createFreeze(docId, "fk-1",
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                        + "\"allowedTranslations\":[\"machine learning\"]},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"fr\","
                        + "\"allowedTranslations\":[\"apprentissage automatique\"]}]",
                "alice", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());

        ApiResult diagnostics = getJson("/api/documents/" + docId + "/freeze/diagnostics");
        assertThat(diagnostics.status()).isEqualTo(200);
        assertThat(diagnostics.body().get("freezeVersion").asInt()).isEqualTo(1);
        var entries = diagnostics.body().get("diagnostics");
        // s1 命中术语：en 合规、fr 缺译文不合规；s2 未命中不出现
        assertThat(entries).hasSize(2);
        var en = entries.get(0);
        assertThat(en.get("segmentId").asText()).isEqualTo("s1");
        assertThat(en.get("language").asText()).isEqualTo("en");
        assertThat(en.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(en.get("hits")).hasSize(1);
        assertThat(en.get("hits").get(0).get("satisfied").asBoolean()).isTrue();
        var fr = entries.get(1);
        assertThat(fr.get("language").asText()).isEqualTo("fr");
        assertThat(fr.get("translationVersion").isNull()).isTrue();
        assertThat(fr.get("hits").get(0).get("satisfied").asBoolean()).isFalse();

        // 无有效冻结时诊断 404
        long docId2 = createDocument(newRequestId(), "[\"en\"]", "[]");
        assertThat(getJson("/api/documents/" + docId2 + "/freeze/diagnostics").status())
                .isEqualTo(404);
    }
}
