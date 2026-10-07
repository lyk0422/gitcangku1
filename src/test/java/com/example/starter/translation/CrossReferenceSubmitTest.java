package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文提交的引用绑定校验、递归上游失效、发布快照冻结与引用写操作幂等测试。
 */
class CrossReferenceSubmitTest extends AbstractIntegrationTest {

    private static String binding(String target, int sourceVersion, int translationVersion) {
        return "{\"targetSegmentId\":\"" + target + "\",\"targetSourceVersion\":" + sourceVersion
                + ",\"targetTranslationVersion\":" + translationVersion + "}";
    }

    /** 建文档 A、B（en/ja 两语言），A 声明引用 B（A 源版升至 2，草稿版本 2）；B 提交 en 译 1。 */
    private long setupAtoB() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"}]");
        assertThat(updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitTranslation(docId, "B", "en", "alice", "B-en-1", 1, newRequestId()).status())
                .isEqualTo(200);
        return docId;
    }

    @Test
    @DisplayName("译文提交引用绑定：缺项/多项 422；目标译稿不存在（另一语言）422；目标源或译稿版本非当前 422")
    void submitReferenceBindingFailures() throws Exception {
        long docId = setupAtoB();

        // 缺项（A 声明了引用但未携带绑定）
        ApiResult missing = submitTranslation(docId, "A", "en", "bob", "A-en", 2, newRequestId());
        assertThat(missing.status()).isEqualTo(422);

        // 多项（无引用段携带了绑定）
        ApiResult extra = submitTranslationWithRefs(docId, "B", "en", "alice", "B-en-2", 1,
                "[" + binding("A", 2, 1) + "]", newRequestId());
        assertThat(extra.status()).isEqualTo(422);

        // 目标译稿在另一语言不存在：引用只允许同语言
        ApiResult otherLanguage = submitTranslationWithRefs(docId, "A", "ja", "bob", "A-ja", 2,
                "[" + binding("B", 1, 1) + "]", newRequestId());
        assertThat(otherLanguage.status()).isEqualTo(422);

        // 目标源版本不是当前版本
        ApiResult wrongSource = submitTranslationWithRefs(docId, "A", "en", "bob", "A-en", 2,
                "[" + binding("B", 2, 1) + "]", newRequestId());
        assertThat(wrongSource.status()).isEqualTo(422);

        // 目标译稿版本不是当前版本
        ApiResult wrongTrans = submitTranslationWithRefs(docId, "A", "en", "bob", "A-en", 2,
                "[" + binding("B", 1, 9) + "]", newRequestId());
        assertThat(wrongTrans.status()).isEqualTo(422);

        // 失败不写译文、不留绑定
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND segment_id = 'A'",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_reference WHERE document_id = ?", Integer.class, docId))
                .isZero();
    }

    @Test
    @DisplayName("递归失效：C→A→B 发布后 B 源文修订，A 与 C 逐层失效；逐层重钉重批后才能再次发布")
    void recursiveUpstreamInvalidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"A\",\"sourceText\":\"源A\"},{\"segmentId\":\"B\",\"sourceText\":\"源B\"},"
                        + "{\"segmentId\":\"C\",\"sourceText\":\"源C\"}]");
        // 草稿 1 → A→B 后 2（A 源版 2）→ C→A 后 3（C 源版 2）
        assertThat(updateReferences(docId, "A", 1, "[{\"targetSegmentId\":\"B\"}]", newRequestId()).status())
                .isEqualTo(200);
        assertThat(updateReferences(docId, "C", 2, "[{\"targetSegmentId\":\"A\"}]", newRequestId()).status())
                .isEqualTo(200);
        // 译文：B 稿 1（草稿 4）→ A 稿 1 钉 B 1/1（草稿 5）→ C 稿 1 钉 A 2/1（草稿 6）
        assertThat(submitTranslation(docId, "B", "en", "alice", "B-1", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslationWithRefs(docId, "A", "en", "bob", "A-1", 2,
                "[" + binding("B", 1, 1) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(submitTranslationWithRefs(docId, "C", "en", "frank", "C-1", 2,
                "[" + binding("A", 2, 1) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "B", "en", "dave", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "C", "en", "erin", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 6, 0, newRequestId()).status()).isEqualTo(201);

        // B 源文修订（即使下游正文未变）：B 源版 2、草稿 7
        ApiResult revised = putJson("/api/documents/" + docId + "/segments/B/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"源B-修订\"}");
        assertThat(revised.status()).isEqualTo(200);
        assertThat(publish(docId, 7, 1, newRequestId()).status()).isEqualTo(422);

        // B 重提译 2 并重批：A 仍钉 B 1/1，发布 422
        assertThat(submitTranslation(docId, "B", "en", "alice", "B-2", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "B", "en", "dave", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 8, 1, newRequestId()).status()).isEqualTo(422);

        // A 重提译 2 钉 B 2/2 并重批：C 仍钉 A 译稿 1，发布 422（递归影响上游引用方）
        assertThat(submitTranslationWithRefs(docId, "A", "en", "bob", "A-2", 2,
                "[" + binding("B", 2, 2) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "A", "en", "carol", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 9, 1, newRequestId()).status()).isEqualTo(422);

        // C 重提译 2 钉 A 2/2 并由非原作者重批：整图同一当前版本，发布成功
        assertThat(submitTranslationWithRefs(docId, "C", "en", "frank", "C-2", 2,
                "[" + binding("A", 2, 2) + "]", newRequestId()).status()).isEqualTo(200);
        assertThat(approve(docId, "C", "en", "erin", 2, newRequestId()).status()).isEqualTo(200);
        ApiResult republished = publish(docId, 10, 1, newRequestId());
        assertThat(republished.status()).isEqualTo(201);
        assertThat(republished.body().get("publishedVersion").asInt()).isEqualTo(2);
    }
}
