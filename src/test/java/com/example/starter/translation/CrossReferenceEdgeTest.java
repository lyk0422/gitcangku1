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
 * 跨段引用的批准失效、快照冻结、相同正文版本变化、幂等与并发边界测试。
 */
class CrossReferenceEdgeTest extends AbstractIntegrationTest {

    private static String binding(String target, int sourceVersion, int translationVersion) {
        return "{\"targetSegmentId\":\"" + target + "\",\"targetSourceVersion\":" + sourceVersion
                + ",\"targetTranslationVersion\":" + translationVersion + "}";
    }

    @Test
    @DisplayName("引用编辑失效本段旧批准：先提交批准 A、B，再给 A 加引用后 A 批准消失，发布 422")
    void referenceEditInvalidatesExistingApproval() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"}]");
        assertThat(submitTranslation(docId, "A", "en", "bob", "A-1", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "B", "en", "alice", "B-1", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "B", "en", "dave", 1, newRequestId()).status()).isEqualTo(200);

        // A 声明引用 B：A 源版升 2、草稿升 3，A 旧批准被删除，B 批准保留
        assertThat(updateReferences(docId, "A", 3, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id = 'A'",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND segment_id = 'B'",
                Integer.class, docId)).isEqualTo(1);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("快照冻结引用边与各源译版本：发布后新增引用边不倒改旧快照；相同正文的译文新版本仍使引用方失效")
    void snapshotFreezesEdgesAndVersions() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"},"
                        + "{\"segmentId\":\"C\",\"sourceText\":\"源C\"}]");
        assertThat(updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslation(docId, "B", "en", "alice", "same", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslationWithRefs(docId, "A", "en", "bob", "A-1", 2,
                "[" + binding("B", 1, 1) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "C", "en", "frank", "C-1", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "B", "en", "dave", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "C", "en", "erin", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);

        // B 提交相同正文的译文新版本：版本按版本号而非文本比较，A 批准不可用于发布
        ApiResult sameTextV2 = submitTranslation(docId, "B", "en", "alice", "same", 1, newRequestId());
        assertThat(sameTextV2.status()).isEqualTo(200);
        assertThat(sameTextV2.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(publish(docId, 6, 1, newRequestId()).status()).isEqualTo(422);

        // 当前草稿 7：恢复可发布状态后给 C 增加 C→B 边（C 源版升 2，草稿 8），重钉重批后发布版本 2
        assertThat(submitTranslationWithRefs(docId, "A", "en", "bob", "A-2", 2,
                "[" + binding("B", 1, 2) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "B", "en", "dave", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(updateReferences(docId, "C", 7, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslationWithRefs(docId, "C", "en", "frank", "C-2", 2,
                "[" + binding("B", 1, 2) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "C", "en", "erin", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 9, 1, newRequestId()).status()).isEqualTo(201);

        // 版本 1 快照冻结：仅 A→B 一条边，A 钉 B 源 1/译 1
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.status()).isEqualTo(200);
        assertThat(release1.body().get("references")).hasSize(1);
        assertThat(release1.body().get("references").get(0).get("sourceSegmentId").asText()).isEqualTo("A");
        assertThat(release1.body().get("references").get(0).get("targetSegmentId").asText()).isEqualTo("B");
        var segments = release1.body().get("segments");
        var segA = segments.get(0);
        assertThat(segA.get("segmentId").asText()).isEqualTo("A");
        assertThat(segA.get("translations").get(0).get("references")).hasSize(1);
        assertThat(segA.get("translations").get(0).get("references").get(0)
                .get("targetTranslationVersion").asInt()).isEqualTo(1);
        assertThat(segments.get(2).get("translations").get(0).get("references")).isEmpty();

        // 版本 2 快照含 A→B 与 C→B 两条边
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("references")).hasSize(2);
    }

    @Test
    @DisplayName("引用写操作幂等：同 requestId 同参重放原结果且不重复变更；异参 409；失败不占键")
    void referenceIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"}]");
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedDraftVersion\":1,"
                + "\"references\":[{\"targetSegmentId\":\"B\"}]}";
        ApiResult first = putJson("/api/documents/" + docId + "/segments/A/references", body, "ref-editor");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("sourceVersion").asInt()).isEqualTo(2);

        // 同键同参：重放首次完整响应（仍返回源版 2），不重复推进
        ApiResult replay = putJson("/api/documents/" + docId + "/segments/A/references", body, "ref-editor");
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 'A'",
                Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_reference WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);

        // 同键异参（期望版本不同）：409
        String different = "{\"requestId\":\"" + requestId + "\",\"expectedDraftVersion\":2,\"references\":[]}";
        assertThat(putJson("/api/documents/" + docId + "/segments/A/references", different, "ref-editor").status())
                .isEqualTo(409);

        // 失败不占键：先用同键触发 422（引用不存在目标），再用同键修正成功
        String failKey = newRequestId();
        ApiResult failed = putJson("/api/documents/" + docId + "/segments/A/references",
                "{\"requestId\":\"" + failKey + "\",\"expectedDraftVersion\":2,"
                        + "\"references\":[{\"targetSegmentId\":\"X\"}]}", "ref-editor");
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = putJson("/api/documents/" + docId + "/segments/A/references",
                "{\"requestId\":\"" + failKey + "\",\"expectedDraftVersion\":2,"
                        + "\"references\":[{\"targetSegmentId\":\"B\"}]}", "ref-editor");
        assertThat(retried.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("并发：发布与引用编辑竞争同一文档时串行化，只出现一个一致结果，不产生部分边或混合快照")
    void concurrentPublishAndReferenceEdit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"},"
                        + "{\"segmentId\":\"C\",\"sourceText\":\"源C\"}]");
        assertThat(updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslation(docId, "B", "en", "alice", "B-1", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslation(docId, "C", "en", "frank", "C-1", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslationWithRefs(docId, "A", "en", "bob", "A-1", 2,
                "[" + binding("B", 1, 1) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "B", "en", "dave", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "C", "en", "erin", 1, newRequestId()).status()).isEqualTo(200);
        // 当前草稿版本 5、发布版本 0

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> {
            gate.await();
            return publish(docId, 5, 0, newRequestId());
        }));
        // B→C 为合法新边，推进 B 源版与草稿版本；与发布竞争，二者由 document 行锁串行
        futures.add(pool.submit(() -> {
            gate.await();
            return updateReferences(docId, "B", 5, "[{\"targetSegmentId\":\"C\"}]", newRequestId());
        }));
        gate.countDown();
        ApiResult publishResult = futures.get(0).get(30, TimeUnit.SECONDS);
        ApiResult editResult = futures.get(1).get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(editResult.status()).isEqualTo(200);
        assertThat(publishResult.status()).isIn(201, 409);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(6);
        assertThat(snapshots).isEqualTo(publishedVersion);
        if (publishResult.status() == 201) {
            // 发布先于编辑：版本 1 快照冻结编辑前状态，仅 A→B 边
            assertThat(publishedVersion).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("references")).hasSize(1);
        } else {
            // 编辑先于发布：期望草稿版本不符，发布 409 且无快照
            assertThat(publishedVersion).isZero();
        }
    }
}
