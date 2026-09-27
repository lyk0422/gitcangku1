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
 * 译文版式占位锁定：声明、批准校验、占位符修订、批量批准、撤回与诊断的主流程与失败分支测试。
 */
class LayoutPlaceholderTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("声明占位符：创建时声明，明细查询返回规范排序（保留重复计数）")
    void declarePlaceholdersCanonicallySorted() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"点击 {2} 或 {1} 再按 {1}\","
                        + "\"placeholders\":[\"{2}\",\"{1}\",\"{1}\"]}]");
        ApiResult detail = getJson("/api/documents/" + docId + "/segments/s1");
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("sourceVersion").asInt()).isEqualTo(1);
        List<String> placeholders = new ArrayList<>();
        detail.body().get("placeholders").forEach(n -> placeholders.add(n.asText()));
        assertThat(placeholders).containsExactly("{1}", "{1}", "{2}");

        ApiResult missing = getJson("/api/documents/" + docId + "/segments/nope");
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("声明占位符：格式非法返回 422；未声明的段落明细 placeholders 为 null")
    void declarePlaceholdersInvalidFormat() throws Exception {
        ApiResult plain = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"a\","
                        + "\"placeholders\":[\"plain\"]}]}");
        assertThat(plain.status()).isEqualTo(422);
        assertThat(plain.body().get("error").asText()).isEqualTo("UNPROCESSABLE");

        ApiResult empty = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"a\","
                        + "\"placeholders\":[\"{}\"]}]}");
        assertThat(empty.status()).isEqualTo(422);

        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\"}]");
        ApiResult unclosed = revisePlaceholders(docId, "s1", "[\"{unclosed\"]", newRequestId());
        assertThat(unclosed.status()).isEqualTo(422);

        ApiResult detail = getJson("/api/documents/" + docId + "/segments/s1");
        assertThat(detail.body().get("placeholders").isNull()).isTrue();
    }

    @Test
    @DisplayName("批准校验：译文保留相同占位符集合及次数可批准并记录历史")
    void approveWithMatchingPlaceholders() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"点击 {1} 继续 {2}\","
                        + "\"placeholders\":[\"{1}\",\"{2}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "click {1} to continue {2}", 1, newRequestId());
        ApiResult approved = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(approved.status()).isEqualTo(200);

        ApiResult history = getJson("/api/documents/" + docId + "/history?operation=TRANSLATION_APPROVED");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("records")).hasSize(1);
        var record = history.body().get("records").get(0);
        assertThat(record.get("segmentId").asText()).isEqualTo("s1");
        assertThat(record.get("language").asText()).isEqualTo("en");
        assertThat(record.get("actor").asText()).isEqualTo("bob");
        assertThat(record.get("detail").get("translationVersion").asInt()).isEqualTo(1);
        assertThat(record.get("occurredAt").asText()).endsWith("Z");
    }

    @Test
    @DisplayName("批准校验：译文缺失占位符 422，返回要求值与实际值，且不产生批准记录")
    void approveWithMissingPlaceholder() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"点击 {1} 继续\","
                        + "\"placeholders\":[\"{1}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "click to continue", 1, newRequestId());
        ApiResult result = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("error").asText()).isEqualTo("PLACEHOLDER_MISMATCH");
        var mismatch = result.body().get("placeholderMismatches").get(0);
        assertThat(mismatch.get("placeholder").asText()).isEqualTo("{1}");
        assertThat(mismatch.get("expectedCount").asInt()).isEqualTo(1);
        assertThat(mismatch.get("actualCount").asInt()).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("批准校验：译文含额外占位符 422；重复计数不一致 422")
    void approveWithExtraOrCountMismatch() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\",\"placeholders\":[\"{1}\",\"{1}\"]},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"b\",\"placeholders\":[\"{1}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "only {1} once", 1, newRequestId());
        ApiResult countMismatch = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(countMismatch.status()).isEqualTo(422);
        var mismatch = countMismatch.body().get("placeholderMismatches").get(0);
        assertThat(mismatch.get("placeholder").asText()).isEqualTo("{1}");
        assertThat(mismatch.get("expectedCount").asInt()).isEqualTo(2);
        assertThat(mismatch.get("actualCount").asInt()).isEqualTo(1);

        submitTranslation(docId, "s2", "en", "alice", "has {1} and extra {9}", 1, newRequestId());
        ApiResult extra = approve(docId, "s2", "en", "bob", 1, newRequestId());
        assertThat(extra.status()).isEqualTo(422);
        var extraMismatch = extra.body().get("placeholderMismatches").get(0);
        assertThat(extraMismatch.get("placeholder").asText()).isEqualTo("{9}");
        assertThat(extraMismatch.get("expectedCount").asInt()).isEqualTo(0);
        assertThat(extraMismatch.get("actualCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("批准校验：未声明占位符的段落不校验；声明空集合时译文含占位符 422")
    void approveUndeclaredAndEmptyDeclaration() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"b\",\"placeholders\":[]}]");
        submitTranslation(docId, "s1", "en", "alice", "free {1} text", 1, newRequestId());
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        submitTranslation(docId, "s2", "en", "alice", "no {1} allowed", 1, newRequestId());
        ApiResult result = approve(docId, "s2", "en", "bob", 1, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("placeholderMismatches").get(0).get("expectedCount").asInt())
                .isEqualTo(0);
    }

    @Test
    @DisplayName("占位符修订：创建新源版本，译文待修订禁止发布；重新翻译批准后发布，快照固化占位符")
    void revisePlaceholdersCreatesNewSourceVersion() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"点击 {1} 继续\",\"placeholders\":[\"{1}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "click {1}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult revised = revisePlaceholders(docId, "s1", "[\"{1}\",\"{2}\"]", newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(revised.body().get("draftVersion").asInt()).isEqualTo(3);

        // 旧译文基于源文版本 1，禁止发布
        ApiResult stalePublish = publish(docId, 3, 0, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);

        // 基于新源版本重新翻译并批准后发布成功
        submitTranslation(docId, "s1", "en", "alice", "click {1} or {2}", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("sourceVersion").asInt()).isEqualTo(2);
        List<String> snapshotPlaceholders = new ArrayList<>();
        segment.get("placeholders").forEach(n -> snapshotPlaceholders.add(n.asText()));
        assertThat(snapshotPlaceholders).containsExactly("{1}", "{2}");

        // 后续占位符修订不改写已发布快照
        revisePlaceholders(docId, "s1", "[\"{3}\"]", newRequestId());
        ApiResult releaseAgain = getJson("/api/documents/" + docId + "/releases/1");
        List<String> frozen = new ArrayList<>();
        releaseAgain.body().get("segments").get(0).get("placeholders")
                .forEach(n -> frozen.add(n.asText()));
        assertThat(frozen).containsExactly("{1}", "{2}");
    }

    @Test
    @DisplayName("批量批准：全部完整时一次批准，响应按规范排序并返回实际批准数量")
    void batchApproveSuccess() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\",\"placeholders\":[\"{1}\"]},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"b\"}]");
        submitTranslation(docId, "s1", "en", "alice", "a {1}", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "あ {1}", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "b en", 1, newRequestId());

        // 故意乱序传入，响应应按 (segmentId, language) 规范排序
        ApiResult result = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().get("approvedCount").asInt()).isEqualTo(3);
        var approvals = result.body().get("approvals");
        assertThat(approvals.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(approvals.get(0).get("language").asText()).isEqualTo("en");
        assertThat(approvals.get(1).get("language").asText()).isEqualTo("ja");
        assertThat(approvals.get(2).get("segmentId").asText()).isEqualTo("s2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_history WHERE document_id = ? AND operation = 'TRANSLATION_APPROVED'",
                Integer.class, docId)).isEqualTo(3);
    }

    @Test
    @DisplayName("批量批准：任一段不完整整次 422 且不改变批准状态，响应区分各项原因")
    void batchApproveAtomicFailure() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\",\"placeholders\":[\"{1}\"]},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"b\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"c\"}]");
        submitTranslation(docId, "s1", "en", "alice", "missing placeholder", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "b en", 1, newRequestId());
        // s3 无译文

        ApiResult result = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s3\",\"language\":\"en\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(result.body().get("error").asText()).isEqualTo("BATCH_INCOMPLETE");
        var items = result.body().get("items");
        assertThat(items).hasSize(2);
        List<String> reasons = new ArrayList<>();
        items.forEach(n -> reasons.add(n.get("segmentId").asText() + ":" + n.get("reason").asText()));
        assertThat(reasons).containsExactlyInAnyOrder(
                "s1:PLACEHOLDER_MISMATCH", "s3:TRANSLATION_MISSING");

        // 整次不改变批准状态：无任何批准记录，包括本来可批准的 s2
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_history WHERE document_id = ?", Integer.class, docId))
                .isZero();
    }

    @Test
    @DisplayName("批量批准幂等：同键同参（乱序）重放首次完整响应；同键异参 409；失败不占键")
    void batchApproveIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\"},{\"segmentId\":\"s2\",\"sourceText\":\"b\"}]");
        submitTranslation(docId, "s1", "en", "alice", "a en", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "b en", 1, newRequestId());

        String requestId = newRequestId();
        String itemsReversed = "[{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1}]";
        ApiResult first = batchApprove(docId, "bob", itemsReversed, requestId);
        assertThat(first.status()).isEqualTo(200);

        // 同键同参（items 顺序不同，规范化后等价）：重放首次完整响应
        String itemsForward = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1}]";
        ApiResult replay = batchApprove(docId, "bob", itemsForward, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 同键异参：409
        String different = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1}]";
        ApiResult conflict = batchApprove(docId, "bob", different, requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用某 requestId 触发 422，再同键修正参数后成功
        String failKey = newRequestId();
        ApiResult failed = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":9}]", failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1}]", failKey);
        assertThat(retried.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("撤回批准：撤回后发布 422；重复撤回 404；历史保留撤回证据")
    void withdrawApproval() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\"}]");
        submitTranslation(docId, "s1", "en", "alice", "a en", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult withdrawn = withdraw(docId, "s1", "en", "carol", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("withdrawnBy").asText()).isEqualTo("carol");
        assertThat(withdrawn.body().get("previousReviewer").asText()).isEqualTo("bob");
        assertThat(withdrawn.body().get("translationVersion").asInt()).isEqualTo(1);

        ApiResult publishResult = publish(docId, 2, 0, newRequestId());
        assertThat(publishResult.status()).isEqualTo(422);

        ApiResult again = withdraw(docId, "s1", "en", "carol", newRequestId());
        assertThat(again.status()).isEqualTo(404);

        ApiResult history = getJson("/api/documents/" + docId + "/history?operation=APPROVAL_WITHDRAWN");
        assertThat(history.body().get("records")).hasSize(1);
        var record = history.body().get("records").get(0);
        assertThat(record.get("actor").asText()).isEqualTo("carol");
        assertThat(record.get("detail").get("previousReviewer").asText()).isEqualTo("bob");
    }

    @Test
    @DisplayName("诊断查询：返回实际/要求数量与逐语言占位符比对结果，排序稳定")
    void diagnostics() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a\",\"placeholders\":[\"{1}\"]},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"b\"}]");
        submitTranslation(docId, "s1", "en", "alice", "a {1}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "あ", 1, newRequestId());

        ApiResult result = getJson("/api/documents/" + docId + "/diagnostics");
        assertThat(result.status()).isEqualTo(200);
        var body = result.body();
        assertThat(body.get("segmentCount").asInt()).isEqualTo(2);
        assertThat(body.get("languageCount").asInt()).isEqualTo(2);
        assertThat(body.get("requiredApprovalCount").asInt()).isEqualTo(4);
        assertThat(body.get("approvedCount").asInt()).isEqualTo(1);
        assertThat(body.get("readyCount").asInt()).isEqualTo(1);

        var s1 = body.get("segments").get(0);
        assertThat(s1.get("segmentId").asText()).isEqualTo("s1");
        var s1En = s1.get("translations").get(0);
        assertThat(s1En.get("language").asText()).isEqualTo("en");
        assertThat(s1En.get("approved").asBoolean()).isTrue();
        assertThat(s1En.get("placeholdersMatch").asBoolean()).isTrue();
        assertThat(s1En.get("expectedPlaceholderCount").asInt()).isEqualTo(1);
        assertThat(s1En.get("actualPlaceholderCount").asInt()).isEqualTo(1);
        assertThat(s1En.get("publishReady").asBoolean()).isTrue();

        var s1Ja = s1.get("translations").get(1);
        assertThat(s1Ja.get("placeholdersMatch").asBoolean()).isFalse();
        assertThat(s1Ja.get("actualPlaceholderCount").asInt()).isEqualTo(0);
        assertThat(s1Ja.get("publishReady").asBoolean()).isFalse();

        var s2En = body.get("segments").get(1).get("translations").get(0);
        assertThat(s2En.get("translationExists").asBoolean()).isFalse();
        assertThat(s2En.get("placeholdersDeclared").asBoolean()).isFalse();
        assertThat(s2En.get("placeholdersMatch").isNull()).isTrue();
    }

    @Test
    @DisplayName("占位符修订与发布并发：对应一个一致状态，失败不产生部分快照")
    void concurrentRevisePlaceholdersAndPublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"a {1}\",\"placeholders\":[\"{1}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "a {1} en", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 2、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 2, 0, newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return revisePlaceholders(docId, "s1", "[\"{2}\"]", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(reviseResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);

        assertThat(draftVersion).isEqualTo(3);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于修订：快照固化修订前占位符
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("segments").get(0).get("placeholders").get(0).asText())
                    .isEqualTo("{1}");
        } else {
            assertThat(publishedVersion).isZero();
        }
    }
}
