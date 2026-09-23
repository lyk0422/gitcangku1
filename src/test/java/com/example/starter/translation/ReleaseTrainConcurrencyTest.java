package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布列车并发边界测试：真实并发激活与并发编辑，通过门闩协调同时发起，
 * 设置超时并断言最终数据状态一致（同一语言只由一个成功列车推进，无部分切换）。
 */
class ReleaseTrainConcurrencyTest extends AbstractIntegrationTest {

    private static final String PAST = "2020-01-01T00:00:00+08:00";
    private static final String LOCALES_V1 =
            "[{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0},"
                    + "{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0}]";

    private long prepareApprovedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        for (String segment : new String[]{"s1", "s2"}) {
            for (String language : new String[]{"en", "fr"}) {
                assertThat(submitTranslation(docId, segment, language, "alice",
                        language + "译文-" + segment, 1, newRequestId()).status()).isEqualTo(200);
                assertThat(approve(docId, segment, language, "bob", 1, newRequestId()).status())
                        .isEqualTo(200);
            }
        }
        return docId;
    }

    private ApiResult createTrain(long docId, String trainKey, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"trainKey\":\"" + trainKey
                + "\",\"sourceDocumentVersion\":5,\"plannedAt\":\"" + PAST
                + "\",\"locales\":" + LOCALES_V1 + "}";
        return postJson("/api/documents/" + docId + "/release-trains", body);
    }

    private ApiResult transition(long docId, String trainKey, String action, String requestId)
            throws Exception {
        return postJson("/api/documents/" + docId + "/release-trains/" + trainKey + "/" + action,
                "{\"requestId\":\"" + requestId + "\"}");
    }

    @Test
    @DisplayName("两列车并发激活：仅一个成功，全部语言指针一致推进，无部分切换")
    void concurrentTrainActivation() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-x", newRequestId()).status()).isEqualTo(201);
        assertThat(createTrain(docId, "train-y", newRequestId()).status()).isEqualTo(201);
        assertThat(transition(docId, "train-x", "ready", newRequestId()).status()).isEqualTo(200);
        assertThat(transition(docId, "train-y", "ready", newRequestId()).status()).isEqualTo(200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> first = pool.submit(() -> {
            gate.await();
            return transition(docId, "train-x", "activate", newRequestId());
        });
        Future<ApiResult> second = pool.submit(() -> {
            gate.await();
            return transition(docId, "train-y", "activate", newRequestId());
        });
        gate.countDown();
        ApiResult firstResult = first.get(30, TimeUnit.SECONDS);
        ApiResult secondResult = second.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        List<Integer> statuses = new ArrayList<>(
                List.of(firstResult.status(), secondResult.status()));
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);

        // 全部语言指针一致：同为 1，且快照数量与指针状态一致
        ApiResult pointers = getJson("/api/documents/" + docId + "/release-pointers");
        assertThat(pointers.body().get("trainReleaseVersion").asInt()).isEqualTo(1);
        for (var pointer : pointers.body().get("pointers")) {
            assertThat(pointer.get("releasedVersion").asInt()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM train_snapshot WHERE document_id = ? AND release_train_version = 1",
                Integer.class, docId)).isEqualTo(2);
        Integer activated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_train WHERE document_id = ? AND status = 'ACTIVATED'",
                Integer.class, docId);
        assertThat(activated).isEqualTo(1);
    }

    @Test
    @DisplayName("激活与源文修订并发：对应一个一致状态，失败时所有语言保持旧版本")
    void concurrentActivateAndRevise() throws Exception {
        long docId = prepareApprovedDoc();
        assertThat(createTrain(docId, "train-race", newRequestId()).status()).isEqualTo(201);
        assertThat(transition(docId, "train-race", "ready", newRequestId()).status()).isEqualTo(200);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> activateFuture = pool.submit(() -> {
            gate.await();
            return transition(docId, "train-race", "activate", newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return putJson("/api/documents/" + docId + "/segments/s1/source",
                    "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后原文一\"}");
        });
        gate.countDown();
        ApiResult activateResult = activateFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(reviseResult.status()).isEqualTo(200);
        assertThat(activateResult.status()).isIn(201, 422);

        ApiResult pointers = getJson("/api/documents/" + docId + "/release-pointers");
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM train_snapshot WHERE document_id = ?", Integer.class, docId);
        if (activateResult.status() == 201) {
            // 激活先于修订：全部语言切到版本 1，快照为修订前源文
            for (var pointer : pointers.body().get("pointers")) {
                assertThat(pointer.get("releasedVersion").asInt()).isEqualTo(1);
            }
            assertThat(snapshots).isEqualTo(2);
            ApiResult release = getJson("/api/documents/" + docId + "/train-releases/1");
            assertThat(release.body().get("locales").get(0).get("segments").get(0)
                    .get("sourceText").asText()).isEqualTo("原文一");
        } else {
            // 修订先于激活：源文摘要不一致，整列 422，所有语言保持旧版本
            for (var pointer : pointers.body().get("pointers")) {
                assertThat(pointer.get("releasedVersion").asInt()).isZero();
            }
            assertThat(snapshots).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT train_release_version FROM document WHERE document_id = ?",
                    Integer.class, docId)).isZero();
        }
    }

    @Test
    @DisplayName("并发同 requestId 创建列车（含 locale 换序）：仅执行一次，全部重放同一结果")
    void concurrentSameRequestIdCreateTrain() throws Exception {
        long docId = prepareApprovedDoc();
        String requestId = newRequestId();
        String reversedLocales =
                "[{\"locale\":\"fr\",\"translationVersion\":1,\"expectedVersion\":0},"
                        + "{\"locale\":\"en\",\"translationVersion\":1,\"expectedVersion\":0}]";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final String locales = i % 2 == 0 ? LOCALES_V1 : reversedLocales;
            futures.add(pool.submit(() -> {
                gate.await();
                String body = "{\"requestId\":\"" + requestId + "\",\"trainKey\":\"train-idem\","
                        + "\"sourceDocumentVersion\":5,\"plannedAt\":\"" + PAST
                        + "\",\"locales\":" + locales + "}";
                return postJson("/api/documents/" + docId + "/release-trains", body);
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long trainId = results.get(0).body().get("trainId").asLong();
        for (ApiResult result : results) {
            assertThat(result.status()).isEqualTo(201);
            assertThat(result.body().get("trainId").asLong()).isEqualTo(trainId);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_train", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM request_log WHERE request_id = ?", Integer.class, requestId))
                .isEqualTo(1);
    }
}
