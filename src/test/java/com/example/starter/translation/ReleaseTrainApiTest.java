package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布列车 API 测试：创建校验、预检、READY 冻结、整列取消、原子激活、
 * 失败回滚（缺段/撤批/源文变更/术语失效）、幂等与查询。
 * 计划时刻通过固定的过去/未来时间戳控制，无需外部时钟。
 */
class ReleaseTrainApiTest extends AbstractIntegrationTest {

    private static final String PAST = "2020-01-01T00:00:00+08:00";
    private static final String FUTURE = "2099-01-01T00:00:00+08:00";
    private static final String LOCALES_V1 =
            "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                    + "{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0}]";

    /** 建 2 语言 2 段落文档，全部译文提交（版本 1）并批准；返回文档 ID，此时草稿版本为 5。 */
    private long prepareApprovedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        for (String segment : new String[]{"s1", "s2"}) {
            for (String language : new String[]{"en", "fr"}) {
                ApiResult submit = submitTranslation(docId, segment, language, "alice",
                        language + "译文-" + segment, 1, newRequestId());
                assertThat(submit.status()).isEqualTo(200);
                ApiResult approve = approve(docId, segment, language, "bob", 1, newRequestId());
                assertThat(approve.status()).isEqualTo(200);
            }
        }
        return docId;
    }

    private ApiResult createTrain(long docId, String trainKey, int sourceDocumentVersion,
                                  String plannedAt, String localesJson, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"trainKey\":\"" + trainKey
                + "\",\"sourceDocumentVersion\":" + sourceDocumentVersion
                + ",\"plannedAt\":\"" + plannedAt + "\",\"locales\":" + localesJson + "}";
        return postJson("/api/documents/" + docId + "/release-trains", body);
    }

    private ApiResult transition(long docId, String trainKey, String action, String requestId) throws Exception {
        return postJson("/api/documents/" + docId + "/release-trains/" + trainKey + "/" + action,
                "{\"requestId\":\"" + requestId + "\"}");
    }

    private void readyTrain(long docId, String trainKey) throws Exception {
        ApiResult ready = transition(docId, trainKey, "ready", newRequestId());
        assertThat(ready.status()).as("READY 应成功: %s", ready.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("创建列车成功：状态 DRAFT、语言稳定排序；trainKey 重复 409")
    void createTrainSuccess() throws Exception {
        long docId = prepareApprovedDoc();
        ApiResult created = createTrain(docId, "train-1", 5, PAST, LOCALES_V1, newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.body().get("trainKey").asText()).isEqualTo("train-1");
        assertThat(created.body().get("sourceDocumentVersion").asInt()).isEqualTo(5);
        assertThat(created.body().get("locales").get(0).get("locale").asText()).isEqualTo("en");
        assertThat(created.body().get("locales").get(1).get("locale").asText()).isEqualTo("fr");

        ApiResult duplicate = createTrain(docId, "train-1", 5, PAST, LOCALES_V1, newRequestId());
        assertThat(duplicate.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("创建列车校验：语言数不足 400、语言重复 422、集合不一致 422、源文档版本不符 409")
    void createTrainValidation() throws Exception {
        long docId = prepareApprovedDoc();
        ApiResult tooFew = createTrain(docId, "t-few", 5, PAST,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0}]", newRequestId());
        assertThat(tooFew.status()).isEqualTo(400);

        ApiResult duplicated = createTrain(docId, "t-dup", 5, PAST,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"EN\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0}]",
                newRequestId());
        assertThat(duplicated.status()).isEqualTo(422);

        ApiResult mismatched = createTrain(docId, "t-mis", 5, PAST,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"de\",\"translationVersion\":1,\"expectedVersion\":0}]",
                newRequestId());
        assertThat(mismatched.status()).isEqualTo(422);

        ApiResult wrongVersion = createTrain(docId, "t-ver", 4, PAST, LOCALES_V1, newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("预检：逐语言报告缺段与未批准，不写数据；预检未通过 READY 返回 422")
    void precheckReportsAndReadyFails() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        ApiResult created = createTrain(docId, "train-pre", 1, PAST, LOCALES_V1, newRequestId());
        assertThat(created.status()).isEqualTo(201);

        ApiResult precheck = getJson("/api/documents/" + docId + "/release-trains/train-pre/precheck");
        assertThat(precheck.status()).isEqualTo(200);
        assertThat(precheck.body().get("ready").asBoolean()).isFalse();
        for (int i = 0; i < 2; i++) {
            var locale = precheck.body().get("locales").get(i);
            assertThat(locale.get("missingSegments").toString()).contains("s1", "s2");
            assertThat(locale.get("currentPointer").asInt()).isZero();
        }
        // 预检不写数据：列车仍为 DRAFT
        ApiResult train = getJson("/api/documents/" + docId + "/release-trains/train-pre");
        assertThat(train.body().get("status").asText()).isEqualTo("DRAFT");

        ApiResult ready = transition(docId, "train-pre", "ready", newRequestId());
        assertThat(ready.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("READY 冻结后激活：全部语言原子切到同一 releaseTrainVersion，快照记录切换前后指针")
    void readyAndActivateSuccess() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-a", 5, PAST, LOCALES_V1, newRequestId()).status()).isEqualTo(201);
        readyTrain(docId, "train-a");

        ApiResult train = getJson("/api/documents/" + docId + "/release-trains/train-a");
        assertThat(train.body().get("status").asText()).isEqualTo("READY");
        assertThat(train.body().get("termVersion").asInt()).isZero();
        assertThat(train.body().get("sourceDigest").asText()).isNotBlank();

        ApiResult activated = transition(docId, "train-a", "activate", newRequestId());
        assertThat(activated.status()).as("激活应成功: %s", activated.body()).isEqualTo(201);
        assertThat(activated.body().get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(activated.body().get("releaseTrainVersion").asInt()).isEqualTo(1);

        ApiResult pointers = getJson("/api/documents/" + docId + "/release-pointers");
        assertThat(pointers.body().get("trainReleaseVersion").asInt()).isEqualTo(1);
        for (var pointer : pointers.body().get("pointers")) {
            assertThat(pointer.get("releasedVersion").asInt()).isEqualTo(1);
        }

        ApiResult release = getJson("/api/documents/" + docId + "/train-releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("locales").size()).isEqualTo(2);
        var enSnapshot = release.body().get("locales").get(0);
        assertThat(enSnapshot.get("locale").asText()).isEqualTo("en");
        assertThat(enSnapshot.get("trainKey").asText()).isEqualTo("train-a");
        assertThat(enSnapshot.get("pointerBefore").asInt()).isZero();
        assertThat(enSnapshot.get("pointerAfter").asInt()).isEqualTo(1);
        assertThat(enSnapshot.get("candidateTranslationVersion").asInt()).isEqualTo(1);
        assertThat(enSnapshot.get("segments").size()).isEqualTo(2);
        assertThat(enSnapshot.get("segments").get(0).get("translation").get("reviewer").asText())
                .isEqualTo("bob");
        // 快照不可变且再次读取一致
        ApiResult reread = getJson("/api/documents/" + docId + "/train-releases/1");
        assertThat(reread.body()).isEqualTo(release.body());
    }

    @Test
    @DisplayName("激活前置条件：未到计划时刻 409、未 READY 409、已取消 409")
    void activatePreconditions() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "t-future", 5, FUTURE, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "t-future");
        assertThat(transition(docId, "t-future", "activate", newRequestId()).status()).isEqualTo(409);

        assertThat(createTrain(docId, "t-draft", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        assertThat(transition(docId, "t-draft", "activate", newRequestId()).status()).isEqualTo(409);

        assertThat(transition(docId, "t-draft", "cancel", newRequestId()).status()).isEqualTo(200);
        assertThat(transition(docId, "t-draft", "activate", newRequestId()).status()).isEqualTo(409);
        assertThat(transition(docId, "t-draft", "ready", newRequestId()).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("READY 后译文被修改：激活 422 整列回滚，所有语言保持旧指针且无快照")
    void translationEditedAfterReadyRollsBack() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-edit", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-edit");

        ApiResult edit = submitTranslation(docId, "s1", "en", "alice", "修改后译文", 1, newRequestId());
        assertThat(edit.status()).isEqualTo(200);

        ApiResult activated = transition(docId, "train-edit", "activate", newRequestId());
        assertThat(activated.status()).isEqualTo(422);
        assertRollbackState(docId, "train-edit");
    }

    @Test
    @DisplayName("READY 后批准被撤回：激活 422 整列回滚，所有语言保持旧指针且无快照")
    void approvalWithdrawnAfterReadyRollsBack() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-approve", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-approve");

        // 模拟审核人撤回批准（删除批准记录）
        jdbc.update("DELETE FROM approval WHERE document_id = ? AND segment_id = 's1' AND language = 'fr'",
                docId);

        ApiResult activated = transition(docId, "train-approve", "activate", newRequestId());
        assertThat(activated.status()).isEqualTo(422);
        assertRollbackState(docId, "train-approve");
    }

    @Test
    @DisplayName("READY 后源文修订：激活 422（源文摘要不一致）整列回滚")
    void sourceRevisedAfterReadyRollsBack() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-src", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-src");

        ApiResult revise = putJson("/api/documents/" + docId + "/segments/s2/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后原文二\"}");
        assertThat(revise.status()).isEqualTo(200);

        ApiResult activated = transition(docId, "train-src", "activate", newRequestId());
        assertThat(activated.status()).isEqualTo(422);
        assertRollbackState(docId, "train-src");
    }

    @Test
    @DisplayName("READY 后术语版本被推进：激活 422（术语失效）整列回滚")
    void termVersionAdvancedAfterReadyRollsBack() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"原文\",\"language\":\"en\",\"requiredTranslation\":\"text\"}]",
                newRequestId()).status()).isEqualTo(201);
        for (String segment : new String[]{"s1", "s2"}) {
            for (String language : new String[]{"en", "fr"}) {
                assertThat(submitTranslation(docId, segment, language, "alice",
                        "text " + language + segment, 1, newRequestId()).status()).isEqualTo(200);
                assertThat(approve(docId, segment, language, "bob", 1, newRequestId()).status())
                        .isEqualTo(200);
            }
        }
        // 草稿版本：1(建文档) + 1(术语) + 4(译文) = 6
        assertThat(createTrain(docId, "train-term", 6, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-term");

        assertThat(updateTerms(docId, 1,
                "[{\"sourceTerm\":\"原文\",\"language\":\"en\",\"requiredTranslation\":\"copy\"}]",
                newRequestId()).status()).isEqualTo(201);

        ApiResult activated = transition(docId, "train-term", "activate", newRequestId());
        assertThat(activated.status()).isEqualTo(422);
        assertRollbackState(docId, "train-term");
    }

    @Test
    @DisplayName("预检报告术语过期：译文绑定旧术语版本时 READY 422")
    void termStaleBlocksReady() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"原文\",\"language\":\"en\",\"requiredTranslation\":\"text\"}]",
                newRequestId()).status()).isEqualTo(201);
        // 草稿版本 6；译文仍绑定术语版本 0，相对当前版本 1 过期
        assertThat(createTrain(docId, "train-stale", 6, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);

        ApiResult precheck = getJson("/api/documents/" + docId + "/release-trains/train-stale/precheck");
        assertThat(precheck.body().get("ready").asBoolean()).isFalse();
        assertThat(precheck.body().get("locales").get(0).get("termStale").asBoolean()).isTrue();

        assertThat(transition(docId, "train-stale", "ready", newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("另一列车已推进发布指针：激活 409，本列车保持 READY 且不产生快照")
    void pointerAdvancedByAnotherTrain() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-first", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        assertThat(createTrain(docId, "train-second", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-first");
        readyTrain(docId, "train-second");

        assertThat(transition(docId, "train-first", "activate", newRequestId()).status()).isEqualTo(201);
        ApiResult second = transition(docId, "train-second", "activate", newRequestId());
        assertThat(second.status()).isEqualTo(409);

        ApiResult train = getJson("/api/documents/" + docId + "/release-trains/train-second");
        assertThat(train.body().get("status").asText()).isEqualTo("READY");
        ApiResult pointers = getJson("/api/documents/" + docId + "/release-pointers");
        for (var pointer : pointers.body().get("pointers")) {
            assertThat(pointer.get("releasedVersion").asInt()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM train_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("第二列车按新指针推进：releaseTrainVersion 递增，历史列车快照保留")
    void secondTrainAdvancesVersion() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-v1", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-v1");
        assertThat(transition(docId, "train-v1", "activate", newRequestId()).status()).isEqualTo(201);

        String localesV2 =
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":1},"
                        + "{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":1}]";
        assertThat(createTrain(docId, "train-v2", 5, PAST, localesV2, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-v2");
        ApiResult activated = transition(docId, "train-v2", "activate", newRequestId());
        assertThat(activated.status()).isEqualTo(201);
        assertThat(activated.body().get("releaseTrainVersion").asInt()).isEqualTo(2);

        ApiResult release2 = getJson("/api/documents/" + docId + "/train-releases/2");
        assertThat(release2.status()).isEqualTo(200);
        assertThat(release2.body().get("locales").get(0).get("pointerBefore").asInt()).isEqualTo(1);
        assertThat(release2.body().get("locales").get(0).get("pointerAfter").asInt()).isEqualTo(2);
        // 历史列车快照仍保留
        assertThat(getJson("/api/documents/" + docId + "/train-releases/1").status()).isEqualTo(200);
        ApiResult pointers = getJson("/api/documents/" + docId + "/release-pointers");
        for (var pointer : pointers.body().get("pointers")) {
            assertThat(pointer.get("releasedVersion").asInt()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("幂等：同 requestId 同参（含 locale 换序）重放首次结果，异参 409，失败不占键")
    void idempotency() throws Exception {
        long docId = prepareApprovedDoc();
        String requestId = newRequestId();
        String reversedLocales =
                "[{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0}]";
        ApiResult first = createTrain(docId, "train-idem", 5, PAST, LOCALES_V1, requestId);
        assertThat(first.status()).isEqualTo(201);
        // locale 换序视为同参：重放首次快照
        ApiResult replay = createTrain(docId, "train-idem", 5, PAST, reversedLocales, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("trainId").asLong())
                .isEqualTo(first.body().get("trainId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_train", Integer.class)).isEqualTo(1);
        // 同键异参 409
        ApiResult different = createTrain(docId, "train-other", 5, PAST, LOCALES_V1, requestId);
        assertThat(different.status()).isEqualTo(409);
        // 失败不占键：先 409（源文档版本不符），同 requestId 修正参数后成功
        String failedRequestId = newRequestId();
        assertThat(createTrain(docId, "train-retry", 4, PAST, LOCALES_V1, failedRequestId).status())
                .isEqualTo(409);
        ApiResult retried = createTrain(docId, "train-retry", 5, PAST, LOCALES_V1, failedRequestId);
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("READY 后候选不可替换只能整列取消；取消已激活列车 409")
    void cancelSemantics() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-cancel", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-cancel");
        ApiResult cancelled = transition(docId, "train-cancel", "cancel", newRequestId());
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(cancelled.body().get("status").asText()).isEqualTo("CANCELLED");

        assertThat(createTrain(docId, "train-act", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        readyTrain(docId, "train-act");
        assertThat(transition(docId, "train-act", "activate", newRequestId()).status()).isEqualTo(201);
        assertThat(transition(docId, "train-act", "cancel", newRequestId()).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("查询：列车列表按 trainKey 稳定排序；不存在的列车发布版本 404；历史单语言发布快照不受影响")
    void queriesAndLegacyPublish() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "b-train", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);
        assertThat(createTrain(docId, "a-train", 5, PAST, LOCALES_V1, newRequestId()).status())
                .isEqualTo(201);

        ApiResult list = getJson("/api/documents/" + docId + "/release-trains");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("trains").get(0).get("trainKey").asText()).isEqualTo("a-train");
        assertThat(list.body().get("trains").get(1).get("trainKey").asText()).isEqualTo("b-train");

        assertThat(getJson("/api/documents/" + docId + "/train-releases/9").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/" + docId + "/release-trains/no-such").status())
                .isEqualTo(404);

        // 历史单语言发布链路仍可用：草稿版本 5、发布版本 0
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult legacyRelease = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(legacyRelease.status()).isEqualTo(200);
        assertThat(legacyRelease.body().get("publishedVersion").asInt()).isEqualTo(1);
    }

    /** 断言激活失败后的回滚状态：指针保持 0、无列车快照、列车仍为 READY。 */
    private void assertRollbackState(long docId, String trainKey) throws Exception {
        ApiResult pointers = getJson("/api/documents/" + docId + "/release-pointers");
        for (var pointer : pointers.body().get("pointers")) {
            assertThat(pointer.get("releasedVersion").asInt()).isZero();
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM train_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT train_release_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();
        ApiResult train = getJson("/api/documents/" + docId + "/release-trains/" + trainKey);
        assertThat(train.body().get("status").asText()).isEqualTo("READY");
    }
}
