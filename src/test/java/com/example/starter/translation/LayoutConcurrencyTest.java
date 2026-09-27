package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 版式锁定相关并发测试：真实并发 + 门闩协调 + 超时，按提交顺序裁决后核对最终业务数据，
 * 不出现半成品、丢失更新或脏批准。
 */
class LayoutConcurrencyTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("并发：源文修订与批准按提交顺序裁决，最终不存在对旧源版本的有效批准")
    void concurrentReviseAndApprove() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{a}\",\"placeholders\":[\"{a}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "x {a}", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> approveFuture = pool.submit(() -> {
            gate.await();
            return approve(docId, "s1", "en", "bob", 1, newRequestId());
        });
        Future<ApiResult> reviseFuture = pool.submit(() -> {
            gate.await();
            return reviseSource(docId, "s1", "{a}{b}", "[\"{a}\",\"{b}\"]", newRequestId());
        });
        gate.countDown();
        ApiResult approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        ApiResult reviseResult = reviseFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(reviseResult.status()).isEqualTo(200);
        assertThat(approveResult.status()).isIn(200, 422);

        Integer sourceVersion = jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);

        // 修订必然生效：源版本 2、草稿版本 3（建文档 1 + 提交 1 + 修订 1）；
        // 任何顺序下都不得残留针对源版本 1 的批准
        assertThat(sourceVersion).isEqualTo(2);
        assertThat(draftVersion).isEqualTo(3);
        assertThat(approvals).isZero();
        // 旧译文仍保留但待修订，禁止发布
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("并发：同一源段两次修订串行化，源版本与草稿版本无丢失更新")
    void concurrentRevisions() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s1\",\"sourceText\":\"v0\","
                        + "\"placeholders\":[]}");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> f1 = pool.submit(() -> {
            gate.await();
            return reviseSource(docId, "s1", "v1 {a}", "[\"{a}\"]", newRequestId());
        });
        Future<ApiResult> f2 = pool.submit(() -> {
            gate.await();
            return reviseSource(docId, "s1", "v2 {a}{b}", "[\"{a}\",\"{b}\"]", newRequestId());
        });
        gate.countDown();
        ApiResult r1 = f1.get(30, TimeUnit.SECONDS);
        ApiResult r2 = f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(r1.status()).isEqualTo(200);
        assertThat(r2.status()).isEqualTo(200);
        // 两次修订分别取得源版本 2、3（顺序不定），最终源版本 3 且只归属其一的内容
        assertThat(java.util.List.of(r1.body().get("sourceVersion").asInt(),
                r2.body().get("sourceVersion").asInt())).containsExactlyInAnyOrder(2, 3);
        assertThat(jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("并发：批准与撤回同一译文按提交顺序裁决，最终状态唯一且历史只追加两条证据")
    void concurrentApproveAndWithdraw() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{a}\",\"placeholders\":[\"{a}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "x {a}", 1, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 先制造一个既有批准，再让撤回与再批准并发
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        Future<ApiResult> withdrawFuture = pool.submit(() -> {
            gate.await();
            return withdraw(docId, "s1", "en", "bob", newRequestId());
        });
        Future<ApiResult> approveFuture = pool.submit(() -> {
            gate.await();
            return approve(docId, "s1", "en", "erin", 1, newRequestId());
        });
        gate.countDown();
        ApiResult withdrawResult = withdrawFuture.get(30, TimeUnit.SECONDS);
        ApiResult approveResult = approveFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两个操作都针对已存在译文且版本匹配，按锁顺序分别成功（撤回 404/批准 200 不会同时异常）
        assertThat(withdrawResult.status()).isEqualTo(200);
        assertThat(approveResult.status()).isEqualTo(200);

        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                Integer.class, docId);
        // 撤回先于再批准：最终 1 条且审核人 erin；再批准先于撤回：撤回删除该行，最终 0 条。
        // 两种顺序均为合法的提交顺序裁决结果，且不存在脏数据或重复行。
        assertThat(approvals).isIn(0, 1);
        if (approvals == 1) {
            String reviewer = jdbc.queryForObject(
                    "SELECT reviewer FROM approval WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                    String.class, docId);
            assertThat(reviewer).isEqualTo("erin");
        }
        Integer historyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_history WHERE document_id = ?", Integer.class, docId);
        // 建文档 + 提交 + 初次批准 + 撤回 + 再批准 = 5
        assertThat(historyCount).isEqualTo(5);
    }
}
