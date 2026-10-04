package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复数类别译文流程测试：类别提交/批准/发布、失效边界、快照冻结与幂等。
 * 复数流程语言限 zh（要求 other）与 en（要求 one、other）。
 */
class PluralApiTest extends AbstractIntegrationTest {

    private static final String PLURAL_SEGMENT =
            "[{\"segmentId\":\"p1\",\"sourceText\":\"{count} files\",\"plural\":true}]";

    @Test
    @DisplayName("复数主流程：zh 只需 other、en 需 one 与 other，批准后发布，快照按类别名排序并冻结作者与审核人")
    void pluralHappyPath() throws Exception {
        long docId = createDocument(newRequestId(), "[\"zh\",\"en\"]", PLURAL_SEGMENT);
        assertThat(submitPlural(docId, "p1", "zh", "alice", 1,
                "[{\"category\":\"other\",\"content\":\"{count} 个文件\"}]", newRequestId()).status())
                .isEqualTo(200);
        ApiResult enSubmit = submitPlural(docId, "p1", "en", "bob", 1,
                "[{\"category\":\"one\",\"content\":\"{count} file\"},"
                        + "{\"category\":\"other\",\"content\":\"{count} files\"}]", newRequestId());
        assertThat(enSubmit.status()).isEqualTo(200);
        assertThat(enSubmit.body().get("categories")).hasSize(2);
        assertThat(enSubmit.body().get("draftVersion").asInt()).isEqualTo(3);

        assertThat(approvePlural(docId, "p1", "zh", "carol", "other", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approvePlural(docId, "p1", "en", "dave", "one", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approvePlural(docId, "p1", "en", "erin", "other", 1, newRequestId()).status())
                .isEqualTo(200);

        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("segmentType").asText()).isEqualTo("plural");
        var zh = segment.get("translations").get(0);
        assertThat(zh.get("language").asText()).isEqualTo("zh");
        assertThat(zh.get("categories")).hasSize(1);
        assertThat(zh.get("categories").get(0).get("category").asText()).isEqualTo("other");
        var en = segment.get("translations").get(1);
        assertThat(en.get("categories")).hasSize(2);
        // 类别按名称排序：one 在 other 前
        assertThat(en.get("categories").get(0).get("category").asText()).isEqualTo("one");
        assertThat(en.get("categories").get(1).get("category").asText()).isEqualTo("other");
        assertThat(en.get("categories").get(0).get("author").asText()).isEqualTo("bob");
        assertThat(en.get("categories").get(0).get("reviewer").asText()).isEqualTo("dave");
        assertThat(en.get("categories").get(1).get("reviewer").asText()).isEqualTo("erin");
        assertThat(en.get("categories").get(0).get("sourceVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("合成场景：en 仅 one 批准时发布 422；补 other 由不同人批准后成功；旧快照仍保存两类原文")
    void enOneApprovedOnlyThenComplete() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());

        // 仅 one 批准：发布 422，不产生快照
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // other 由与作者不同的人批准后发布成功
        assertThat(approvePlural(docId, "p1", "en", "carol", "other", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 修订 one 类别并再次发布
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"a single file\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 2, newRequestId());
        assertThat(publish(docId, 3, 1, newRequestId()).status()).isEqualTo(201);

        // 旧快照仍同时保存两类别的原文本
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        var cats1 = release1.body().get("segments").get(0).get("translations").get(0).get("categories");
        assertThat(cats1).hasSize(2);
        assertThat(cats1.get(0).get("content").asText()).isEqualTo("one file");
        assertThat(cats1.get(1).get("content").asText()).isEqualTo("many files");
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var cats2 = release2.body().get("segments").get(0).get("translations").get(0).get("categories");
        assertThat(cats2.get(0).get("content").asText()).isEqualTo("a single file");
        assertThat(cats2.get(1).get("content").asText()).isEqualTo("many files");
    }

    @Test
    @DisplayName("缺类别与额外类别：en 缺 other 或 zh 多出 one 时发布 422")
    void missingAndExtraCategories() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"zh\"]", PLURAL_SEGMENT);
        // en 只提交 one（缺 other）；zh 提交 other 与 one（one 为额外类别）
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"}]", newRequestId());
        submitPlural(docId, "p1", "zh", "alice", 1,
                "[{\"category\":\"other\",\"content\":\"文件\"},{\"category\":\"one\",\"content\":\"一个文件\"}]",
                newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());
        approvePlural(docId, "p1", "zh", "bob", "other", 1, newRequestId());
        approvePlural(docId, "p1", "zh", "bob", "one", 1, newRequestId());

        ApiResult result = publish(docId, 3, 0, newRequestId());
        assertThat(result.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isZero();
    }
    @Test
    @DisplayName("作者不能批准自己写的类别；译文版本不匹配 422；类别不存在 404")
    void approvePluralFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"}]", newRequestId());

        ApiResult byAuthor = approvePlural(docId, "p1", "en", "alice", "one", 1, newRequestId());
        assertThat(byAuthor.status()).isEqualTo(422);

        ApiResult wrongVersion = approvePlural(docId, "p1", "en", "bob", "one", 9, newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(422);

        ApiResult missing = approvePlural(docId, "p1", "en", "bob", "other", 1, newRequestId());
        assertThat(missing.status()).isEqualTo(404);

        assertThat(approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId()).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("源文修订使该段全部类别批准失效：发布 422，重提交并重新批准后成功")
    void sourceRevisionInvalidatesAllCategoryApprovals() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());
        approvePlural(docId, "p1", "en", "bob", "other", 1, newRequestId());

        putJson("/api/documents/" + docId + "/segments/p1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"{count} items\"}");
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 基于旧源文版本提交 422；基于当前版本重提交后旧批准仍失效
        assertThat(submitStalePlural(docId)).isEqualTo(422);
        submitPlural(docId, "p1", "en", "alice", 2,
                "[{\"category\":\"one\",\"content\":\"one item\"},"
                        + "{\"category\":\"other\",\"content\":\"many items\"}]", newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);

        approvePlural(docId, "p1", "en", "bob", "one", 2, newRequestId());
        approvePlural(docId, "p1", "en", "bob", "other", 2, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
    }

    private int submitStalePlural(long docId) throws Exception {
        return submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"x\"}]", newRequestId()).status();
    }

    @Test
    @DisplayName("修订一个类别只使该类别批准失效，不清空其他类别文本")
    void categoryRevisionInvalidatesOnlyThatCategory() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());
        approvePlural(docId, "p1", "en", "carol", "other", 1, newRequestId());

        // 仅修订 one：one 的批准失效，发布 422；other 文本与批准不受影响
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"exactly one file\"}]", newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // other 未被清空：文本与译文版本保持原值
        var otherRow = jdbc.queryForMap("SELECT content, translation_version, author FROM plural_translation "
                + "WHERE document_id = ? AND segment_id = 'p1' AND language = 'en' AND category = 'other'", docId);
        assertThat(otherRow.get("content")).isEqualTo("many files");
        assertThat(((Number) otherRow.get("translation_version")).intValue()).isEqualTo(1);

        // 只需重新批准 one；other 用旧版本号再批准会 422（其批准仍有效，无需重批）
        approvePlural(docId, "p1", "en", "bob", "one", 2, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var cats = release.body().get("segments").get(0).get("translations").get(0).get("categories");
        assertThat(cats.get(0).get("content").asText()).isEqualTo("exactly one file");
        assertThat(cats.get(0).get("translationVersion").asInt()).isEqualTo(2);
        assertThat(cats.get(1).get("content").asText()).isEqualTo("many files");
        assertThat(cats.get(1).get("translationVersion").asInt()).isEqualTo(1);
        assertThat(cats.get(1).get("reviewer").asText()).isEqualTo("carol");
    }
    @Test
    @DisplayName("类型与语言边界：普通段落走复数端点 422；复数段落走普通端点 422；非 zh/en 语言 422")
    void typeAndLanguageBoundaries() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"plain text\"},"
                        + "{\"segmentId\":\"p1\",\"sourceText\":\"{count} files\",\"plural\":true}]");

        // 普通段落不能按类别提交/批准
        assertThat(submitPlural(docId, "s1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"x\"}]", newRequestId()).status()).isEqualTo(422);
        assertThat(approvePlural(docId, "s1", "en", "bob", "one", 1, newRequestId()).status())
                .isEqualTo(422);

        // 复数段落不能走普通译文端点
        assertThat(submitTranslation(docId, "p1", "en", "alice", "files", 1, newRequestId()).status())
                .isEqualTo(422);
        assertThat(approve(docId, "p1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);

        // 复数流程仅允许 zh/en：ja 提交 422
        assertThat(submitPlural(docId, "p1", "ja", "alice", 1,
                "[{\"category\":\"other\",\"content\":\"ファイル\"}]", newRequestId()).status())
                .isEqualTo(422);

        // 含复数段落且目标语言含不支持语言时，发布 422（普通段落已齐备）
        submitTranslation(docId, "s1", "en", "alice", "plain", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "プレーン", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "ja", "bob", 1, newRequestId());
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());
        approvePlural(docId, "p1", "en", "bob", "other", 1, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("幂等：类别集合换序视为同参重放；同键异参 409；失败不占键")
    void pluralIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        String requestId = newRequestId();
        ApiResult first = submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", requestId);
        assertThat(first.status()).isEqualTo(200);

        // 同键同参（类别换序）：重放首次完整响应，不重复递增版本
        ApiResult replay = submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"other\",\"content\":\"many files\"},"
                        + "{\"category\":\"one\",\"content\":\"one file\"}]", requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        Integer oneVersion = jdbc.queryForObject("SELECT translation_version FROM plural_translation "
                + "WHERE document_id = ? AND segment_id = 'p1' AND language = 'en' AND category = 'one'",
                Integer.class, docId);
        assertThat(oneVersion).isEqualTo(1);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(2);

        // 同键异参：409
        ApiResult conflict = submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"changed\"}]", requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用某键触发 422，再用同键正确参数成功
        String failKey = newRequestId();
        assertThat(submitPlural(docId, "p1", "en", "alice", 99,
                "[{\"category\":\"one\",\"content\":\"x\"}]", failKey).status()).isEqualTo(422);
        assertThat(submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file v2\"}]", failKey).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("术语约束：类别译文违反术语规则 422 返回全部违规；术语版本更新后旧类别译文发布 422")
    void pluralTermConstraints() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", PLURAL_SEGMENT);
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"files\",\"language\":\"en\",\"requiredTranslation\":\"documents\"}]",
                newRequestId());

        // 类别正文缺少必译文本：422 且返回违规术语
        ApiResult violation = submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"many files\"}]", newRequestId());
        assertThat(violation.status()).isEqualTo(422);
        assertThat(violation.body().get("violations")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plural_translation WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // 合规提交并批准后，术语版本更新使类别译文过期，发布 422
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one documents\"},"
                        + "{\"category\":\"other\",\"content\":\"many documents\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 1, newRequestId());
        approvePlural(docId, "p1", "en", "bob", "other", 1, newRequestId());
        updateTerms(docId, 1, "[]", newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);

        // 以当前术语版本重新提交并批准后发布成功
        submitPlural(docId, "p1", "en", "alice", 1,
                "[{\"category\":\"one\",\"content\":\"one documents\"},"
                        + "{\"category\":\"other\",\"content\":\"many documents\"}]", newRequestId());
        approvePlural(docId, "p1", "en", "bob", "one", 2, newRequestId());
        approvePlural(docId, "p1", "en", "bob", "other", 2, newRequestId());
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(2);
    }
}