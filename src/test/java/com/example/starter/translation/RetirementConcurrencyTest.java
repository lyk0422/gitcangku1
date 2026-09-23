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
 * 退役并发边界测试：退役激活与发布并发按文档行锁串行提交，
 * 历史发布快照、当前草稿状态与影响清单必须一致；同 requestId 并发仅生效一次。
 */
class RetirementConcurrencyTest extends AbstractIntegrationTest {

    private static final String RULES_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]";
    private static final String PAST_FROM = "2020-01-01T00:00:00Z";
    private static final String PAST_TO = "2021-01-01T00:00:00Z";

    private long setupSingleSegmentDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("退役激活与发布并发：按文档行锁串行提交；译文术语过期使发布 422，激活无部分写入且影响清单一致")
    void concurrentActivationAndPublish() throws Exception {
        long docId = setupSingleSegmentDoc();
        // 建立 v2（替代版本）：草稿版本 4，s1 译文仍绑定 v1
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 4, 0, newRequestId());
        });
        Future<ApiResult> activateFuture = pool.submit(() -> {
            gate.await();
            return activateRetirement(docId, "ret-1", newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult activateResult = activateFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 激活必然成功；发布因译文绑定 v1 而当前术语版本 v2（过期/退役）必然 422
        assertThat(activateResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isEqualTo(422);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();
        assertThat(snapshots).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_retirement WHERE document_id = ? "
                + "AND status = 'ACTIVE'", Integer.class, docId)).isEqualTo(1);
        // 冻结影响清单恰好一条（APPROVED s1，撤批）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_impact WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("退役激活与译文重提交并发：按提交顺序串行，冻结影响清单与当前译文绑定状态一致")
    void concurrentActivationAndResubmit() throws Exception {
        long docId = setupSingleSegmentDoc();
        // 建立 v2（替代版本）：草稿版本 4，s1 译文仍绑定 v1
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 译文重提交仍按 v1 规则校验（当前术语版本已是 v2，提交绑定 v2）：内容须满足 v2 规则
        Future<ApiResult> submitFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        });
        Future<ApiResult> activateFuture = pool.submit(() -> {
            gate.await();
            return activateRetirement(docId, "ret-1", newRequestId());
        });
        gate.countDown();
        ApiResult submitResult = submitFuture.get(30, TimeUnit.SECONDS);
        ApiResult activateResult = activateFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(submitResult.status()).isEqualTo(200);
        assertThat(activateResult.status()).isEqualTo(200);

        Integer boundVersion = jdbc.queryForObject(
                "SELECT term_version FROM translation WHERE document_id = ? AND segment_id = 's1' "
                        + "AND language = 'en'", Integer.class, docId);
        // 有效批准（版本同时匹配）必须为零：提交先到则旧批准失效，激活先到则批准被撤回
        Integer validApprovals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval a JOIN translation t "
                        + "ON a.document_id = t.document_id AND a.segment_id = t.segment_id "
                        + "AND a.language = t.language JOIN segment s "
                        + "ON a.document_id = s.document_id AND a.segment_id = s.segment_id "
                        + "WHERE a.document_id = ? AND a.translation_version = t.translation_version "
                        + "AND a.source_version = s.source_version", Integer.class, docId);
        Integer impactRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM retirement_impact WHERE document_id = ? AND kind IN ('DRAFT','APPROVED')",
                Integer.class, docId);
        // 重提交必使旧批准失效（译文版本 2），最终译文绑定 v2，影响清单与状态一致：
        // 若提交先到：译文已绑 v2，冻结清单不含 s1（0 条草稿类影响，仅可能残留失效批准行）；
        // 若激活先到：冻结时译文仍为 v1 且批准有效（APPROVED 1 条，批准被撤回），随后提交改绑 v2。
        assertThat(boundVersion).isEqualTo(2);
        assertThat(validApprovals).isZero();
        assertThat(impactRows).isIn(0, 1);
        if (impactRows == 1) {
            String kind = jdbc.queryForObject("SELECT kind FROM retirement_impact WHERE document_id = ?",
                    String.class, docId);
            assertThat(kind).isEqualTo("APPROVED");
        }
        // 术语版本状态与退役单状态一致
        assertThat(jdbc.queryForObject("SELECT status FROM term_version WHERE document_id = ? AND term_version = 1",
                String.class, docId)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM term_version WHERE document_id = ? AND term_version = 2",
                String.class, docId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("并发同 requestId 创建退役单：仅生效一次，全部重放同一成功结果")
    void concurrentSameRequestIdCreateRetirement() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        String requestId = newRequestId();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", requestId);
            }));
        }
        gate.countDown();
        List<ApiResult> results = new ArrayList<>();
        for (Future<ApiResult> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        for (ApiResult result : results) {
            assertThat(result.status())
                    .as("并发响应: %s", result.body())
                    .isEqualTo(201);
            assertThat(result.body().get("retirementKey").asText()).isEqualTo("ret-1");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_retirement WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        // 前置建文档与两次术语更新各占一条 request_log；并发退役单同 requestId 仅落一条
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        // 文档无译文且无发布快照，影响集为空
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_impact WHERE document_id = ?",
                Integer.class, docId)).isZero();
    }
}
