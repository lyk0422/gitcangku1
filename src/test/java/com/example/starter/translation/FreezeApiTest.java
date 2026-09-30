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
 * 术语冻结窗口测试：可控 UTC 时钟驱动 10:00/10:30/11:00 场景，
 * 覆盖窗口内 409 且草稿不变、解冻重校验 422 整单回滚、幂等重放、查询展示与历史发布不回写。
 */
class FreezeApiTest extends AbstractIntegrationTest {

    private static final String START = "2026-10-01T10:00:00Z";
    private static final String END = "2026-10-01T11:00:00Z";

    private static final String RULES_V2 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"NN\"}]";

    /** 建两段文档并推进到术语 V2：s1/s2 译文均合规、已批准，当前草稿版本 7、发布版本 0。 */
    private long prepareV2Document() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"神经网络\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "carol", "neural network", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "dave", 1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "carol", "NN", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        approve(docId, "s2", "en", "dave", 2, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("题干主流程：10:00-11:00 冻结术语 V2；10:30 修改 409 且原草稿不变；11:00 重校验通过生成新草稿，旧发布仍为 V2")
    void freezeWindowMainScenario() throws Exception {
        setClock("2026-10-01T09:50:00Z");
        long docId = prepareV2Document();
        // 冻结前先发布一版：发布版本 1 固化术语 V2
        ApiResult firstRelease = publish(docId, 7, 0, newRequestId());
        assertThat(firstRelease.status()).isEqualTo(201);
        assertThat(firstRelease.body().get("publishedVersion").asInt()).isEqualTo(1);

        // 09:59 创建冻结窗口：绑定 V2、UTC 左闭右开 [10:00, 11:00)，返回受影响段落
        ApiResult freeze = createFreeze(docId, 2, START, END, newRequestId());
        assertThat(freeze.status()).isEqualTo(201);
        assertThat(freeze.body().get("window").get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(freeze.body().get("window").get("termVersion").asInt()).isEqualTo(2);
        assertThat(freeze.body().get("window").get("active").asBoolean()).isFalse();
        assertThat(freeze.body().get("draftVersion").asInt()).isEqualTo(7);
        assertThat(freeze.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(freeze.body().get("affectedSegments").toString()).isEqualTo("[\"s1\",\"s2\"]");

        // 10:00 窗口开始（左闭）：查询展示冻结起止、草稿术语版本与受影响段落
        setClock("2026-10-01T10:00:00Z");
        ApiResult status = getJson("/api/documents/" + docId + "/terms/freeze");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("activeWindow").get("termVersion").asInt()).isEqualTo(2);
        assertThat(status.body().get("activeWindow").get("freezeStart").asText()).isEqualTo(START);
        assertThat(status.body().get("activeWindow").get("freezeEnd").asText()).isEqualTo(END);
        assertThat(status.body().get("activeWindow").get("active").asBoolean()).isTrue();
        assertThat(status.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(status.body().get("affectedSegments").toString()).isEqualTo("[\"s1\",\"s2\"]");
        assertThat(status.body().get("windows")).hasSize(1);
        ApiResult window1 = getJson("/api/documents/" + docId + "/terms/freeze/1");
        assertThat(window1.status()).isEqualTo(200);
        assertThat(window1.body().get("active").asBoolean()).isTrue();

        // 冻结期间发布与各类草稿修改一律 409 TERM_FROZEN
        assertThat(publish(docId, 7, 1, newRequestId()).status()).isEqualTo(409);
        assertThat(putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"冻结期改源文\"}").status())
                .isEqualTo(409);
        assertThat(submitTranslation(docId, "s1", "en", "alice", "frozen edit", 1,
                newRequestId()).status()).isEqualTo(409);
        assertThat(approve(docId, "s1", "en", "bob", 2, newRequestId()).status()).isEqualTo(409);
        assertThat(postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s3\",\"sourceText\":\"x\"}")
                .status()).isEqualTo(409);
        assertThat(createFreeze(docId, 2, "2026-10-01T10:30:00Z", "2026-10-01T12:00:00Z",
                newRequestId()).status()).isEqualTo(409);

        // 10:30 换术语返回 409，原草稿不变：术语版本、草稿版本、译文内容均未改动
        String unfreezeRequestId = newRequestId();
        ApiResult frozenUpdate = updateTerms(docId, 2,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML Platform\"}]",
                unfreezeRequestId);
        assertThat(frozenUpdate.status()).isEqualTo(409);
        assertThat(frozenUpdate.body().get("error").asText()).isEqualTo("TERM_FROZEN");
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId)).isEqualTo("ML");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 窗口内同键重放同样 409（守卫先于幂等重放）
        assertThat(updateTerms(docId, 2,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML Platform\"}]",
                unfreezeRequestId).status()).isEqualTo(409);

        // 11:00 窗口结束（右开）：全部受影响段落按新草稿完整校验。新规则集与已发布译文兼容，
        // 一次提交新草稿版本 V3，受影响段落随响应返回，全部译文原子重绑 V3
        setClock(END);
        ApiResult unfrozen = updateTerms(docId, 2, RULES_V2, unfreezeRequestId);
        assertThat(unfrozen.status()).isEqualTo(201);
        assertThat(unfrozen.body().get("termVersion").asInt()).isEqualTo(3);
        assertThat(unfrozen.body().get("draftVersion").asInt()).isEqualTo(8);
        assertThat(unfrozen.body().get("affectedSegments").toString()).isEqualTo("[\"s1\",\"s2\"]");
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT term_version FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(3);

        // 旧发布仍为 V2：历史快照不回写
        ApiResult oldRelease = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(oldRelease.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(oldRelease.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("ML");

        // 解冻后发布只能引用完整校验后的草稿：成功生成发布版本 2（固化 V3）
        ApiResult secondRelease = publish(docId, 8, 1, newRequestId());
        assertThat(secondRelease.status()).isEqualTo(201);
        assertThat(getJson("/api/documents/" + docId + "/releases/2").body().get("termVersion").asInt())
                .isEqualTo(3);

        // 同更新 requestId 重放首次版本与受影响段落；换参数 409
        ApiResult replay = updateTerms(docId, 2, RULES_V2, unfreezeRequestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("termVersion").asInt()).isEqualTo(3);
        assertThat(replay.body().get("affectedSegments").toString()).isEqualTo("[\"s1\",\"s2\"]");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId)).isEqualTo(3);
        assertThat(updateTerms(docId, 2, "[]", unfreezeRequestId).status()).isEqualTo(409);

        // 再次冻结绑定 V3 后，同一 requestId 在窗口内重放仍 409
        createFreeze(docId, 3, "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", newRequestId());
        setClock("2026-10-01T12:30:00Z");
        assertThat(updateTerms(docId, 2, RULES_V2, unfreezeRequestId).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("窗口结束但段落校验失败：整单 422 回滚，不产生术语 V3、不改动译文与草稿，无部分术语替换")
    void unfreezeValidationFailureRollsBack() throws Exception {
        setClock("2026-10-01T09:50:00Z");
        long docId = prepareV2Document();
        createFreeze(docId, 2, START, END, newRequestId());
        setClock(END);

        // s1 译文 "ML" 不满足新必译 "ML Platform"：422 返回全部违规术语
        ApiResult failed = updateTerms(docId, 2,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML Platform\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"NN\"}]",
                newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(failed.body().get("violations")).hasSize(1);
        assertThat(failed.body().get("violations").get(0).get("requiredTranslation").asText())
                .isEqualTo("ML Platform");

        // 整单回滚：无 V3 版本与规则、草稿术语/草稿版本不变、译文未被部分替换
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(7);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_rule WHERE document_id = ? AND term_version = 3",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId)).isEqualTo("ML");
        assertThat(jdbc.queryForObject(
                "SELECT DISTINCT term_version FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);

        // 失败不占键：同 requestId 修正规则（受影响段落均合规）后成功
        ApiResult retried = updateTerms(docId, 2, RULES_V2, newRequestId());
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("termVersion").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("解冻重校验：受影响段落某目标语言缺译时整单 422，不产生新版本")
    void unfreezeValidationMissingTranslation() throws Exception {
        setClock("2026-10-01T09:00:00Z");
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createFreeze(docId, 1, START, END, newRequestId());

        setClock(END);
        ApiResult failed = updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"ja\",\"requiredTranslation\":\"機械学習\"}]",
                newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("message").asText()).contains("s1/ja");
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("窗口边界：10:00:00 冻结（左闭），11:00:00 已解冻（右开）")
    void freezeBoundaryAtEndpoints() throws Exception {
        setClock("2026-10-01T09:00:00Z");
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        updateTerms(docId, 0, "[]", newRequestId());
        createFreeze(docId, 1, START, END, newRequestId());

        setClock("2026-10-01T10:00:00Z");
        assertThat(updateTerms(docId, 1, "[]", newRequestId()).status()).isEqualTo(409);
        setClock("2026-10-01T10:59:59.999Z");
        assertThat(updateTerms(docId, 1, "[]", newRequestId()).status()).isEqualTo(409);
        setClock("2026-10-01T11:00:00Z");
        ApiResult opened = updateTerms(docId, 1, "[]", newRequestId());
        assertThat(opened.status()).isEqualTo(201);
        assertThat(opened.body().get("affectedSegments")).isEmpty();
    }

    @Test
    @DisplayName("创建冻结窗口失败分支：区间非法 422；绑定版本不符 409；窗口重叠进行中 409；文档不存在 404")
    void createFreezeFailures() throws Exception {
        setClock("2026-10-01T09:00:00Z");
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        updateTerms(docId, 0, "[]", newRequestId());

        assertThat(createFreeze(docId, 1, END, START, newRequestId()).status()).isEqualTo(422);
        assertThat(createFreeze(docId, 1, START, START, newRequestId()).status()).isEqualTo(422);
        assertThat(createFreeze(docId, 2, START, END, newRequestId()).status()).isEqualTo(409);
        assertThat(getJson("/api/documents/999999/terms/freeze").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/" + docId + "/terms/freeze/9").status()).isEqualTo(404);

        // 从未冻结时查询 activeWindow 为 null、windows 为空
        ApiResult status = getJson("/api/documents/" + docId + "/terms/freeze");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("activeWindow").isNull()).isTrue();
        assertThat(status.body().get("windows")).isEmpty();
    }

    @Test
    @DisplayName("创建冻结窗口幂等：同键同参重放同一窗口，同键异参 409，失败不占键")
    void createFreezeIdempotency() throws Exception {
        setClock("2026-10-01T09:00:00Z");
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        updateTerms(docId, 0, "[]", newRequestId());
        String requestId = newRequestId();

        ApiResult first = createFreeze(docId, 1, START, END, requestId);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = createFreeze(docId, 1, START, END, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("window").get("freezeVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_freeze WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);

        assertThat(createFreeze(docId, 1, START, "2026-10-01T12:00:00Z", requestId).status())
                .isEqualTo(409);

        String failKey = newRequestId();
        assertThat(createFreeze(docId, 1, END, START, failKey).status()).isEqualTo(422);
        assertThat(createFreeze(docId, 1, START, END, failKey).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT freeze_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
    }

    @Test
    @DisplayName("无冻结历史的普通术语更新：不受解冻流程影响，affectedSegments 为空，不批量重绑旧译文")
    void ordinaryTermUpdateUnaffected() throws Exception {
        setClock("2026-10-01T09:00:00Z");
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());

        ApiResult updated = updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML Platform\"}]",
                newRequestId());
        assertThat(updated.status()).isEqualTo(201);
        assertThat(updated.body().get("affectedSegments")).isEmpty();
        // 普通更新不重绑译文：旧译文仍绑定 V1（解冻语义只在冻结窗口结束后触发）
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("解冻与发布竞争：同一时刻真实并发，发布只能引用已完整校验的草稿版本")
    void concurrentUnfreezeAndPublish() throws Exception {
        setClock("2026-10-01T09:50:00Z");
        long docId = prepareV2Document();
        createFreeze(docId, 2, START, END, newRequestId());
        // 时钟恰好为窗口右开端点：解冻更新判定窗口已结束，发布守卫也据此放行或拦截
        setClock(END);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 7, 0, newRequestId());
        });
        Future<ApiResult> unfreezeFuture = pool.submit(() -> {
            gate.await();
            return updateTerms(docId, 2, RULES_V2, newRequestId());
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, TimeUnit.SECONDS);
        ApiResult unfreezeResult = unfreezeFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        // 两操作经文档行锁串行：解冻更新成功；发布要么引用冻结前已完整校验的草稿 7（201），
        // 要么因草稿版本推进为 8 而 409，绝不可能引用半成品草稿
        assertThat(unfreezeResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 409);

        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer termVersion = jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(8);
        assertThat(termVersion).isEqualTo(3);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于解冻：快照固化冻结前完整草稿（术语 V2、译文 ML/NN）
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult snapshot = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(snapshot.body().get("termVersion").asInt()).isEqualTo(2);
            assertThat(snapshot.body().get("segments").get(0).get("translations").get(0)
                    .get("content").asText()).isEqualTo("ML");
        } else {
            // 解冻先于发布：期望草稿版本 7 已失效（当前为 8），409 且无快照
            assertThat(publishedVersion).isZero();
        }
    }
}
