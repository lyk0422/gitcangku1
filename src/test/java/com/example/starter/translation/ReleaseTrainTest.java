package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布列车集成测试：真实 H2（MODE=MySQL）数据库，覆盖创建校验、只读预检、READY 冻结、
 * 缺段/撤批/改稿回滚、多语言原子切换、requestId 幂等与同 locale 并发只推进一次等边界。
 */
class ReleaseTrainTest extends AbstractIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-09-24T00:00:00Z");
    private static final String FUTURE = "2026-09-24T01:00:00Z";
    private static final String RULES = "["
            + "{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
            + "{\"sourceTerm\":\"机器学习\",\"language\":\"fr\",\"requiredTranslation\":\"apprentissage automatique\"}"
            + "]";

    @Autowired
    private com.example.starter.translation.service.TrainClock trainClock;

    @BeforeEach
    void fixClock() {
        trainClock.setClock(Clock.fixed(BASE, ZoneOffset.UTC));
    }

    @AfterEach
    void resetClock() {
        trainClock.reset();
    }

    /** 建双语言文档（s1 命中术语、s2 不命中），激活术语版本 1，提交并批准全部 4 份候选译文；完成时草稿版本 6。 */
    private long preparedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"数据工程\"}]");
        assertThat(updateTerms(docId, 0, RULES, newRequestId()).status()).isEqualTo(201);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "machine learning", 1,
                newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "s1", "fr", "alice", "apprentissage automatique", 1,
                newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "s2", "en", "alice", "data engineering", 1,
                newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "s2", "fr", "alice", "ingenierie des donnees", 1,
                newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s1", "fr", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s2", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "s2", "fr", "bob", 1, newRequestId()).status()).isEqualTo(200);
        return docId;
    }

    private String candidates(int enVersion, int frVersion, int expected) {
        return "[{\"locale\":\"en\",\"translationVersion\":" + enVersion + ",\"expectedVersion\":" + expected
                + "},{\"locale\":\"fr\",\"translationVersion\":" + frVersion + ",\"expectedVersion\":"
                + expected + "}]";
    }

    private String createReadyTrain(long docId, String trainKey, String scheduledAt) throws Exception {
        ApiResult created = createTrain(docId, trainKey, 6, scheduledAt, candidates(1, 1, 0),
                newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(trainReady(trainKey, newRequestId()).status()).isEqualTo(200);
        return trainKey;
    }

    @Test
    @DisplayName("创建列车：locale 集合与文档不一致、重复、数量越界返回 422；源版本不符或 trainKey 重复返回 409")
    void createTrainValidation() throws Exception {
        long docId = preparedDoc();

        // 只声明 1 个 locale 不满足 2~20 的请求约束，参数校验直接 400
        ApiResult missingLocale = createTrain(docId, "t-bad-locale", 6, FUTURE,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0}]", newRequestId());
        assertThat(missingLocale.status()).isEqualTo(400);

        ApiResult wrongLocale = createTrain(docId, "t-wrong-locale", 6, FUTURE,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"de\",\"translationVersion\":1,\"expectedVersion\":0}]",
                newRequestId());
        assertThat(wrongLocale.status()).isEqualTo(422);

        ApiResult duplicateLocale = createTrain(docId, "t-dup-locale", 6, FUTURE,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"EN\",\"translationVersion\":1,\"expectedVersion\":0}]",
                newRequestId());
        assertThat(duplicateLocale.status()).isEqualTo(422);

        ApiResult wrongVersion = createTrain(docId, "t-version", 5, FUTURE, candidates(1, 1, 0),
                newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(409);

        assertThat(createTrain(docId, "t-dup-key", 6, FUTURE, candidates(1, 1, 0),
                newRequestId()).status()).isEqualTo(201);
        ApiResult duplicateKey = createTrain(docId, "t-dup-key", 6, FUTURE, candidates(1, 1, 0),
                newRequestId());
        assertThat(duplicateKey.status()).isEqualTo(409);
        // 全部校验失败（422/409）均回滚不写列车，仅 t-dup-key 一行
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_train", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("只读预检：不写数据；干净候选 clean=true，缺段/指针不符逐项返回")
    void precheckReadOnly() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // fr 既无译文也无批准，草稿版本：建 1 + 术语 1 + 提交 1 = 3

        ApiResult created = createTrain(docId, "t-precheck", 3, FUTURE,
                "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0}]",
                newRequestId());
        assertThat(created.status()).isEqualTo(201);

        ApiResult precheck = trainPrecheck("t-precheck");
        assertThat(precheck.status()).isEqualTo(200);
        assertThat(precheck.body().get("clean").asBoolean()).isFalse();
        JsonNode en = precheck.body().get("locales").get(0);
        JsonNode fr = precheck.body().get("locales").get(1);
        assertThat(en.get("locale").asText()).isEqualTo("en");
        assertThat(en.get("missingSegments").size()).isZero();
        assertThat(fr.get("locale").asText()).isEqualTo("fr");
        assertThat(fr.get("missingSegments").get(0).asText()).isEqualTo("s1");
        assertThat(fr.get("approvalIssues").get(0).get("reason").asText()).isEqualTo("MISSING_APPROVAL");

        // 预检不写任何业务数据：仍为 DRAFT，无指针、无快照
        assertThat(getTrain("t-precheck").body().get("status").asText()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_pointer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isZero();
    }

    @Test
    @DisplayName("READY 冻结：预检不通过拒绝冻结且失败不占 requestId；冻结后返回固化预检结果")
    void readyFreezeAndFailedRequestIdNotOccupied() throws Exception {
        long docId = preparedDoc();
        // 故意声明不存在的候选译文版本 2，预检缺段
        ApiResult created = createTrain(docId, "t-freeze", 6, FUTURE, candidates(2, 1, 0),
                newRequestId());
        assertThat(created.status()).isEqualTo(201);

        String requestId = newRequestId();
        ApiResult failedReady = trainReady("t-freeze", requestId);
        assertThat(failedReady.status()).isEqualTo(422);
        assertThat(getTrain("t-freeze").body().get("status").asText()).isEqualTo("DRAFT");

        // 失败不占键：同一 requestId 用于另一次操作仍可成功
        ApiResult cancelled = trainCancel("t-freeze", requestId);
        assertThat(cancelled.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);

        // 全新干净列车冻结成功，冻结预检结果随查询返回
        String readyKey = createReadyTrain(docId, "t-ready", FUTURE);
        JsonNode train = getTrain(readyKey).body();
        assertThat(train.get("status").asText()).isEqualTo("READY");
        assertThat(train.get("frozenPrecheck").get("clean").asBoolean()).isTrue();
        assertThat(train.get("frozenPrecheck").get("status").asText()).isEqualTo("READY");
        ApiResult precheck = trainPrecheck(readyKey);
        assertThat(precheck.body().get("status").asText()).isEqualTo("READY");
        assertThat(precheck.body().get("clean").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("未到计划时刻激活返回 422；READY 后只能整列取消，取消后不能再激活")
    void scheduledTimeGateAndCancel() throws Exception {
        long docId = preparedDoc();
        String trainKey = createReadyTrain(docId, "t-schedule", FUTURE);

        ApiResult early = trainActivate(trainKey, newRequestId());
        assertThat(early.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_pointer", Integer.class)).isZero();

        assertThat(trainCancel(trainKey, newRequestId()).status()).isEqualTo(200);
        assertThat(trainActivate(trainKey, newRequestId()).status()).isEqualTo(409);
        assertThat(getTrain(trainKey).body().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("多语言原子切换：到达计划时刻激活后全部 locale 指针切到同一版本并生成不可变快照")
    void atomicMultiLocaleActivation() throws Exception {
        long docId = preparedDoc();
        String trainKey = createReadyTrain(docId, "t-publish", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));

        ApiResult activated = trainActivate(trainKey, newRequestId());
        assertThat(activated.status()).isEqualTo(201);
        assertThat(activated.body().get("releaseTrainVersion").asInt()).isEqualTo(1);
        assertThat(activated.body().get("status").asText()).isEqualTo("PUBLISHED");

        ApiResult pointers = listReleasePointers(docId);
        assertThat(pointers.status()).isEqualTo(200);
        assertThat(pointers.body()).hasSize(2);
        assertThat(pointers.body().get(0).get("locale").asText()).isEqualTo("en");
        assertThat(pointers.body().get(0).get("releaseTrainVersion").asInt()).isEqualTo(1);
        assertThat(pointers.body().get(1).get("locale").asText()).isEqualTo("fr");
        assertThat(pointers.body().get(1).get("releaseTrainVersion").asInt()).isEqualTo(1);

        ApiResult snapshots = listTrainSnapshots(trainKey);
        assertThat(snapshots.status()).isEqualTo(200);
        assertThat(snapshots.body()).hasSize(2);
        JsonNode enSnapshot = snapshots.body().get(0);
        assertThat(enSnapshot.get("locale").asText()).isEqualTo("en");
        assertThat(enSnapshot.get("pointerBefore").asInt()).isZero();
        assertThat(enSnapshot.get("pointerAfter").asInt()).isEqualTo(1);
        JsonNode snapshotPayload = objectMapper.readTree(enSnapshot.get("snapshotJson").asText());
        assertThat(snapshotPayload.get("releaseTrainVersion").asInt()).isEqualTo(1);
        assertThat(snapshotPayload.get("termVersion").asInt()).isEqualTo(1);
        assertThat(snapshotPayload.get("segments")).hasSize(2);
        assertThat(snapshotPayload.get("segments").get(0).get("content").asText())
                .isEqualTo("machine learning");

        // PUBLISHED 不可再取消或再激活
        assertThat(trainCancel(trainKey, newRequestId()).status()).isEqualTo(409);
        assertThat(trainActivate(trainKey, newRequestId()).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("冻结后撤批：激活 422 整列回滚，所有语言保持旧版本，列车仍为 READY 可修复后重发")
    void rollbackOnWithdrawnApproval() throws Exception {
        long docId = preparedDoc();
        String trainKey = createReadyTrain(docId, "t-unapprove", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));

        assertThat(unapprove(docId, "s1", "fr", newRequestId()).status()).isEqualTo(200);

        ApiResult activated = trainActivate(trainKey, newRequestId());
        assertThat(activated.status()).isEqualTo(422);
        assertThat(getTrain(trainKey).body().get("status").asText()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_pointer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT release_train_version FROM release_train WHERE train_key = ?",
                Integer.class, trainKey)).isNull();

        // 补回批准后同列车可重新激活成功
        assertThat(approve(docId, "s1", "fr", "carol", 1, newRequestId()).status()).isEqualTo(200);
        ApiResult retry = trainActivate(trainKey, newRequestId());
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.body().get("releaseTrainVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("冻结后候选译文被修改：激活 409 整列回滚，所有语言保持旧版本")
    void rollbackOnCandidateModified() throws Exception {
        long docId = preparedDoc();
        String trainKey = createReadyTrain(docId, "t-modified", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));

        // 重新提交 en/s1 产生译文版本 2，候选版本 1 已被替换
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "machine learning v2",
                1, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);

        ApiResult activated = trainActivate(trainKey, newRequestId());
        assertThat(activated.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_pointer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isZero();
        assertThat(getTrain(trainKey).body().get("status").asText()).isEqualTo("READY");
    }

    @Test
    @DisplayName("冻结后源文修订：激活 409 整列回滚")
    void rollbackOnSourceRevised() throws Exception {
        long docId = preparedDoc();
        String trainKey = createReadyTrain(docId, "t-source", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));

        ApiResult revise = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"机器学习新版\"}");
        assertThat(revise.status()).isEqualTo(200);

        ApiResult activated = trainActivate(trainKey, newRequestId());
        assertThat(activated.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_pointer", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isZero();
    }

    @Test
    @DisplayName("冻结后激活新术语版本：激活 409 整列回滚（绑定术语版本在计划时刻已失效）")
    void rollbackOnTermActivated() throws Exception {
        long docId = preparedDoc();
        String trainKey = createReadyTrain(docId, "t-term", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));

        ApiResult newTerms = updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"fr\",\"requiredTranslation\":\"AA\"}]",
                newRequestId());
        assertThat(newTerms.status()).isEqualTo(201);

        ApiResult activated = trainActivate(trainKey, newRequestId());
        assertThat(activated.status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isZero();
    }

    @Test
    @DisplayName("requestId 幂等：同参重放首次快照，locale 换序视为同参，异参 409；激活重放不产生第二版本")
    void idempotency() throws Exception {
        long docId = preparedDoc();
        String createRequestId = newRequestId();
        String bodyOrdered = "{\"requestId\":\"" + createRequestId + "\",\"trainKey\":\"t-idem\","
                + "\"sourceDocumentVersion\":6,\"scheduledAt\":\"" + FUTURE + "\",\"candidates\":"
                + candidates(1, 1, 0) + "}";
        ApiResult first = postJson("/api/documents/" + docId + "/release-trains", bodyOrdered);
        assertThat(first.status()).isEqualTo(201);
        // 完全同参重放
        ApiResult replay = postJson("/api/documents/" + docId + "/release-trains", bodyOrdered);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("trainKey").asText()).isEqualTo("t-idem");

        // locale 换序：规范化后同参，重放首次结果，不产生第二列
        String bodyReordered = "{\"requestId\":\"" + createRequestId + "\",\"trainKey\":\"t-idem\","
                + "\"sourceDocumentVersion\":6,\"scheduledAt\":\"" + FUTURE + "\",\"candidates\":["
                + "{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0},"
                + "{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0}]}";
        ApiResult reordered = postJson("/api/documents/" + docId + "/release-trains", bodyReordered);
        assertThat(reordered.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_train", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, createRequestId))
                .isEqualTo(1);

        // 同键异参：409
        String bodyDifferent = bodyOrdered.replace("\"sourceDocumentVersion\":6",
                "\"sourceDocumentVersion\":5");
        ApiResult conflict = postJson("/api/documents/" + docId + "/release-trains", bodyDifferent);
        assertThat(conflict.status()).isEqualTo(409);

        // 激活幂等：同 requestId 两次，只有一个发布版本，指针与快照不重复
        assertThat(trainReady("t-idem", newRequestId()).status()).isEqualTo(200);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));
        String activateRequestId = newRequestId();
        ApiResult activated1 = trainActivate("t-idem", activateRequestId);
        ApiResult activated2 = trainActivate("t-idem", activateRequestId);
        assertThat(activated1.status()).isEqualTo(201);
        assertThat(activated2.status()).isEqualTo(201);
        assertThat(activated1.body().get("releaseTrainVersion").asInt())
                .isEqualTo(activated2.body().get("releaseTrainVersion").asInt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COALESCE(MAX(release_train_version),0) FROM release_train", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("顺序两列车：第二列车 expectedVersion=1 成功把全部 locale 推进到版本 2")
    void sequentialTrainsAdvancePointers() throws Exception {
        long docId = preparedDoc();
        String first = createReadyTrain(docId, "t-seq-1", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));
        assertThat(trainActivate(first, newRequestId()).status()).isEqualTo(201);

        // 译文全部重提为版本 2 并重新批准（基底不变，无需改术语）
        for (String locale : new String[]{"en", "fr"}) {
            for (String segment : new String[]{"s1", "s2"}) {
                String content = "en".equals(locale)
                        ? segment.equals("s1") ? "machine learning 2" : "data engineering 2"
                        : segment.equals("s1") ? "apprentissage automatique 2" : "ingenierie des donnees 2";
                assertThat(submitTranslation(docId, segment, locale, "alice", content, 1,
                        newRequestId()).status()).isEqualTo(200);
                assertThat(approve(docId, segment, locale, "bob", 2, newRequestId()).status()).isEqualTo(200);
            }
        }
        int draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        ApiResult second = createTrain(docId, "t-seq-2", draftVersion, FUTURE, candidates(2, 2, 1),
                newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(trainReady("t-seq-2", newRequestId()).status()).isEqualTo(200);
        ApiResult activated = trainActivate("t-seq-2", newRequestId());
        assertThat(activated.status()).isEqualTo(201);
        assertThat(activated.body().get("releaseTrainVersion").asInt()).isEqualTo(2);

        JsonNode pointers = listReleasePointers(docId).body();
        assertThat(pointers.get(0).get("releaseTrainVersion").asInt()).isEqualTo(2);
        assertThat(pointers.get(1).get("releaseTrainVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("并发两列车激活同 locale：恰好一个成功，另一个 409，全部指针只指向胜出版本")
    void concurrentTrainsOnlyOneAdvancesEachLocale() throws Exception {
        long docId = preparedDoc();
        String trainA = createReadyTrain(docId, "t-concurrent-a", FUTURE);
        String trainB = createReadyTrain(docId, "t-concurrent-b", FUTURE);
        trainClock.setClock(Clock.fixed(Instant.parse("2026-09-24T02:00:00Z"), ZoneOffset.UTC));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (String key : new String[]{trainA, trainB}) {
            futures.add(pool.submit(() -> {
                gate.await();
                return trainActivate(key, newRequestId());
            }));
        }
        gate.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS).status());
        }
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        JsonNode pointers = listReleasePointers(docId).body();
        assertThat(pointers).hasSize(2);
        assertThat(pointers.get(0).get("releaseTrainVersion").asInt()).isEqualTo(1);
        assertThat(pointers.get(1).get("releaseTrainVersion").asInt()).isEqualTo(1);
        // 只可能有胜出列车的 2 条快照，失败列车零快照
        Integer snapshotCount = jdbc.queryForObject("SELECT COUNT(*) FROM train_snapshot", Integer.class);
        assertThat(snapshotCount).isEqualTo(2);
        Integer publishedTrains = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_train WHERE status = 'PUBLISHED'", Integer.class);
        assertThat(publishedTrains).isEqualTo(1);
    }
}
