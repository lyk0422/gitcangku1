package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨段引用主流程与失败分支测试：真实 H2 数据库验证引用图、版本钉绑、递归失效与快照冻结。
 */
class CrossReferenceTest extends AbstractIntegrationTest {

    private static String refBinding(String target, int sourceVersion, int translationVersion) {
        return "{\"targetSegmentId\":\"" + target + "\",\"targetSourceVersion\":" + sourceVersion
                + ",\"targetTranslationVersion\":" + translationVersion + "}";
    }

    @Test
    @DisplayName("合成主流程：A引用B，两段批准后B提交译2，A正文未变也不可发布；A重钉并重新批准后可发布；B反向引用A 409")
    void crossReferenceSyntheticFlow() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"}]");

        // A 声明引用 B：源版推进为 2、草稿版本推进为 2
        ApiResult edited = updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId());
        assertThat(edited.status()).isEqualTo(200);
        assertThat(edited.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(edited.body().get("draftVersion").asInt()).isEqualTo(2);

        // B 先提交译 1（无引用），A 再提交译 1 并钉住 B 源版 1/译稿 1
        assertThat(submitTranslation(docId, "B", "en", "alice", "B-en-1", 1, newRequestId()).status())
                .isEqualTo(200);
        ApiResult submitA = submitTranslationWithRefs(docId, "A", "en", "bob", "A-en-1", 2,
                "[" + refBinding("B", 1, 1) + "]", newRequestId());
        assertThat(submitA.status()).isEqualTo(200);
        assertThat(submitA.body().get("references")).hasSize(1);
        assertThat(submitA.body().get("draftVersion").asInt()).isEqualTo(4);

        // 目标与引用方均由非原作者批准
        assertThat(approve(docId, "B", "en", "dave", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // B 提交译 2（源版未变，但译文版本变化）
        ApiResult b2 = submitTranslation(docId, "B", "en", "alice", "B-en-2", 1, newRequestId());
        assertThat(b2.status()).isEqualTo(200);
        assertThat(b2.body().get("translationVersion").asInt()).isEqualTo(2);

        // A 正文未变，但其批准钉住 B 译稿 1：发布必须 422，且不产生第二个快照
        ApiResult stalePublish = publish(docId, 5, 1, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // A 重新提交钉住 B 译稿 2；B 译 2 与 A 译 2 的旧批准均已失效，未重新批准前发布 422
        ApiResult a2 = submitTranslationWithRefs(docId, "A", "en", "bob", "A-en-1", 2,
                "[" + refBinding("B", 1, 2) + "]", newRequestId());
        assertThat(a2.status()).isEqualTo(200);
        assertThat(a2.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(publish(docId, 6, 1, newRequestId()).status()).isEqualTo(422);
        // 目标与引用方均重新由非原作者批准后发布成功
        assertThat(approve(docId, "B", "en", "dave", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 2, newRequestId()).status()).isEqualTo(200);
        ApiResult republished = publish(docId, 6, 1, newRequestId());
        assertThat(republished.status()).isEqualTo(201);
        assertThat(republished.body().get("publishedVersion").asInt()).isEqualTo(2);

        // B 反向引用 A 成环：409，且不留 B→A 边
        ApiResult reverse = updateReferences(docId, "B", 6, "[{\"targetSegmentId\":\"A\"}]", newRequestId());
        assertThat(reverse.status()).isEqualTo(409);
        Integer reverseEdges = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_reference WHERE document_id = ? AND source_segment_id = 'B'",
                Integer.class, docId);
        assertThat(reverseEdges).isZero();
        Integer aToB = jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_reference WHERE document_id = ? "
                        + "AND source_segment_id = 'A' AND target_segment_id = 'B'",
                Integer.class, docId);
        assertThat(aToB).isEqualTo(1);
    }

    @Test
    @DisplayName("引用编辑拒绝分支：自身/不存在目标/重复 422；超过 5 个 400；期望草稿版本不符 409；源版未重复推进")
    void updateReferencesValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"}]");

        ApiResult self = updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"A\"}]", newRequestId());
        assertThat(self.status()).isEqualTo(422);

        ApiResult missing = updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"X\"}]", newRequestId());
        assertThat(missing.status()).isEqualTo(422);

        ApiResult duplicate = updateReferences(docId, "A", 1,
                "[{\"targetSegmentId\":\"B\"},{\"targetSegmentId\":\"B\"}]", newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        String six = "[{\"targetSegmentId\":\"s1\"},{\"targetSegmentId\":\"s2\"},{\"targetSegmentId\":\"s3\"},"
                + "{\"targetSegmentId\":\"s4\"},{\"targetSegmentId\":\"s5\"},{\"targetSegmentId\":\"s6\"}]";
        ApiResult tooMany = updateReferences(docId, "A", 1, six, newRequestId());
        assertThat(tooMany.status()).isEqualTo(400);

        ApiResult versionConflict = updateReferences(docId, "A", 99,
                "[{\"targetSegmentId\":\"B\"}]", newRequestId());
        assertThat(versionConflict.status()).isEqualTo(409);

        // 全部失败不留边，源版与草稿版不变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM segment_reference WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 'A'",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("引用编辑缺少 X-Actor-Id：400")
    void updateReferencesRequiresActor() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"expectedDraftVersion\":1,\"references\":[]}";
        ApiResult result = putJson("/api/documents/" + docId + "/segments/A/references", body);
        assertThat(result.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("成环检测：A→B→C 后 C→A 成环 409，回滚不留部分边")
    void cycleThroughChainRejected() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"a\"},{\"segmentId\":\"B\",\"sourceText\":\"b\"},"
                        + "{\"segmentId\":\"C\",\"sourceText\":\"c\"}]");
        assertThat(updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(updateReferences(docId, "B", 2, "[{\"targetSegmentId\":\"C\"}]", newRequestId()).status())
                .isEqualTo(200);
        // C→A 形成 A→B→C→A：409
        ApiResult cycle = updateReferences(docId, "C", 3, "[{\"targetSegmentId\":\"A\"}]", newRequestId());
        assertThat(cycle.status()).isEqualTo(409);
        // 回滚不留 C→A 边，C 源版不推进
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment_reference WHERE document_id = ? AND source_segment_id = 'C'",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 'C'",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("引用集合未变化的重复编辑：200 但不推进源版与草稿版（版本只随实际变化推进）")
    void unchangedReferencesDoNotBumpVersions() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"}]");
        ApiResult first = updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId());
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("sourceVersion").asInt()).isEqualTo(2);
        ApiResult again = updateReferences(docId, "A", 2, "[{\"targetSegmentId\":\"B\"}]", newRequestId());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(again.body().get("draftVersion").asInt()).isEqualTo(2);
    }
}
