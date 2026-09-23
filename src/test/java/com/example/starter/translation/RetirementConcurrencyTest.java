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
 * 术语退役并发边界测试：退役激活、草稿迁移与译文提交真实并发，按文档行锁串行提交，
 * 断言历史快照标记、当前草稿状态与冻结影响清单最终一致，无重复迁移或部分写入。
 */
class RetirementConcurrencyTest extends AbstractIntegrationTest {

    private static final String RULES_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]";
    private static final String RULES_V2 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]";
    private static final String FROM = "2026-02-01T00:00:00Z";
    private static final String TO = "2026-03-01T00:00:00Z";

    /** 三个段落译文均绑定术语版本 v1；建立替代版本 v2。草稿版本 6、发布版本 0。 */
    private long fixture() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"机器学习二\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"机器学习三\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "machine learning two", 1, newRequestId());
        submitTranslation(docId, "s3", "en", "alice", "machine learning three", 1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("并发激活同一退役单：仅一个成功，另一个 409，快照标记不重复")
    void concurrentActivation() throws Exception {
        long docId = fixture();
        createRetirement(docId, "ret-c1", 1, 2, FROM, TO, newRequestId());

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return activateRetirement(docId, "ret-c1", newRequestId());
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 200) {
                success++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        // 三个受影响草稿段落各仅一条快照/影响相关标记，无重复写入
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_retirement WHERE retirement_key = 'ret-c1' AND status = 'ACTIVATED'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_snapshot_impact", Integer.class))
                .isZero();
        // 激活使草稿版本加一一次：6 → 7
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(7);
    }

    @Test
    @DisplayName("并发草稿迁移：相同期望版本仅一个成功，另一个 409，译文增版与迁移记录不重复")
    void concurrentMigration() throws Exception {
        long docId = fixture();
        createRetirement(docId, "ret-c2", 1, 2, FROM, TO, newRequestId());
        activateRetirement(docId, "ret-c2", newRequestId());
        // 激活后草稿版本 7
        String drafts = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML one\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML two\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML three\"}]";

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return migrateDrafts(docId, "ret-c2", "carol", 7, drafts, newRequestId());
            }));
        }
        gate.countDown();
        int success = 0;
        int conflict = 0;
        for (Future<ApiResult> future : futures) {
            int status = future.get(30, TimeUnit.SECONDS).status();
            if (status == 200) {
                success++;
            } else if (status == 409) {
                conflict++;
            }
        }
        pool.shutdown();

        assertThat(success).isEqualTo(1);
        assertThat(conflict).isEqualTo(threads - 1);
        // 译文逐稿仅增版一次（v2）、迁移记录每稿仅一条、草稿版本仅加一
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ? "
                + "AND translation_version = 2 AND term_version = 2", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_migration", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(8);
    }

    @Test
    @DisplayName("退役激活与草稿提交并发：两者按提交顺序串行，冻结影响清单与当前草稿状态始终一致")
    void concurrentActivationAndDraftEdit() throws Exception {
        long docId = fixture();
        createRetirement(docId, "ret-c3", 1, 2, FROM, TO, newRequestId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        // 草稿提交绑定当前术语版本 2（不引用退役版本），任何顺序下都应成功
        Future<ApiResult> submitFuture = pool.submit(() -> {
            gate.await();
            return submitTranslation(docId, "s2", "en", "alice", "ML two revised", 1, newRequestId());
        });
        Future<ApiResult> activateFuture = pool.submit(() -> {
            gate.await();
            return activateRetirement(docId, "ret-c3", newRequestId());
        });
        gate.countDown();
        ApiResult submitResult = submitFuture.get(30, TimeUnit.SECONDS);
        ApiResult activateResult = activateFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(submitResult.status()).isEqualTo(200);
        assertThat(activateResult.status()).isEqualTo(200);

        // 冻结影响清单：仅统计绑定退役版本 v1 的译文，重新提交的 s2 绑定 v2，不属于影响清单
        ApiResult impact = getRetirementImpact(docId, "ret-c3");
        assertThat(impact.body().get("status").asText()).isEqualTo("ACTIVATED");
        var states = impact.body().get("impact");
        // 提交先于激活：s2 已切到 v2，冻结清单 drafts 仅 s2 之外的 s1、s3（无批准均为 DRAFT）
        // 激活先于提交：激活瞬间 s2 仍绑 v1，冻结清单含 s1、s2、s3；提交发生在其后不改冻结清单
        int frozenCount = states.get("drafts").size() + states.get("approved").size();
        assertThat(frozenCount).isBetween(2, 3);

        // 当前草稿状态与顺序一致：s2 终态必为 v2 译文、绑定替代版本 2；s1/s3 仍绑 v1
        assertThat(jdbc.queryForObject("SELECT term_version FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT translation_version FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", String.class, docId))
                .isEqualTo("ML two revised");
        assertThat(jdbc.queryForObject("SELECT term_version FROM translation WHERE document_id = ? "
                + "AND segment_id = 's1' AND language = 'en'", Integer.class, docId)).isEqualTo(1);
        // 激活始终成功一次：草稿版本 6 → 7（提交）→ 8（激活），与先后顺序无关
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(8);
        // 无发布快照，影响标记为空
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_snapshot_impact", Integer.class))
                .isZero();
    }
}
