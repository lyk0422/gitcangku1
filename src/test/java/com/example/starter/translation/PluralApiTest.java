package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复数类别译文流程测试：类别提交/批准/发布、快照冻结与题干合成场景。
 * 复数段落仅允许 zh（类别 other）与 en（类别 one、other）。
 */
class PluralApiTest extends AbstractIntegrationTest {

    private ApiResult submitCategory(long docId, String segmentId, String language, String category,
                                     String actorId, String content, int sourceVersion, String requestId)
            throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"content\":\"" + content
                + "\",\"sourceVersion\":" + sourceVersion + "}";
        return putJson("/api/documents/" + docId + "/segments/" + segmentId + "/translations/" + language
                + "/categories/" + category, body, actorId);
    }

    private ApiResult submitCategories(long docId, String segmentId, String language, String actorId,
                                       String categoriesJson, int sourceVersion, String requestId)
            throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"sourceVersion\":" + sourceVersion
                + ",\"categories\":" + categoriesJson + "}";
        return putJson("/api/documents/" + docId + "/segments/" + segmentId + "/translations/" + language
                + "/categories", body, actorId);
    }

    private ApiResult approveCategory(long docId, String segmentId, String language, String category,
                                      String actorId, int translationVersion, String requestId)
            throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"translationVersion\":" + translationVersion + "}";
        return postJson("/api/documents/" + docId + "/segments/" + segmentId + "/translations/" + language
                + "/categories/" + category + "/approve", body, actorId);
    }

    @Test
    @DisplayName("合成场景：en 只有 one 批准时发布 422；补 other 并由不同人批准后成功；旧快照仍保存两类文本")
    void syntheticEnPluralFlow() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} files\",\"plural\":true}]");
        // 只提交并批准 one：发布因缺 other 类别失败
        assertThat(submitCategory(docId, "s1", "en", "one", "alice", "one file", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "en", "one", "bob", 1, newRequestId()).status()).isEqualTo(200);
        ApiResult blocked = publish(docId, 2, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // 补 other（作者 alice）并由不同人 carol 批准：发布成功
        assertThat(submitCategory(docId, "s1", "en", "other", "alice", "{count} files", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "en", "other", "carol", 1, newRequestId()).status())
                .isEqualTo(200);
        ApiResult published = publish(docId, 3, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);

        // 快照按类别名排序冻结两类文本、作者与批准人
        JsonNode categories = getJson("/api/documents/" + docId + "/releases/1").body()
                .get("segments").get(0).get("translations").get(0).get("categories");
        assertThat(categories).hasSize(2);
        assertThat(categories.get(0).get("category").asText()).isEqualTo("one");
        assertThat(categories.get(0).get("content").asText()).isEqualTo("one file");
        assertThat(categories.get(0).get("author").asText()).isEqualTo("alice");
        assertThat(categories.get(0).get("reviewer").asText()).isEqualTo("bob");
        assertThat(categories.get(1).get("category").asText()).isEqualTo("other");
        assertThat(categories.get(1).get("content").asText()).isEqualTo("{count} files");
        assertThat(categories.get(1).get("reviewer").asText()).isEqualTo("carol");

        // 修订类别 one 并重新批准、再次发布；旧快照仍同时保存两类原文本
        assertThat(submitCategory(docId, "s1", "en", "one", "alice", "exactly one file", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "en", "one", "bob", 2, newRequestId()).status()).isEqualTo(200);
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);

        JsonNode oldCategories = getJson("/api/documents/" + docId + "/releases/1").body()
                .get("segments").get(0).get("translations").get(0).get("categories");
        assertThat(oldCategories.get(0).get("content").asText()).isEqualTo("one file");
        assertThat(oldCategories.get(1).get("content").asText()).isEqualTo("{count} files");
        JsonNode newCategories = getJson("/api/documents/" + docId + "/releases/2").body()
                .get("segments").get(0).get("translations").get(0).get("categories");
        assertThat(newCategories.get(0).get("content").asText()).isEqualTo("exactly one file");
        assertThat(newCategories.get(0).get("translationVersion").asInt()).isEqualTo(2);
        assertThat(newCategories.get(1).get("content").asText()).isEqualTo("{count} files");
    }

    @Test
    @DisplayName("zh+en 完整流程：各类别独立作者，快照按类别名排序并冻结源文/术语版本")
    void zhAndEnFullFlow() throws Exception {
        long docId = createDocument(newRequestId(), "[\"zh\",\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} 个文件\",\"plural\":true}]");
        // zh 仅要求 other；en 要求 one 与 other，各类别作者可不同
        assertThat(submitCategory(docId, "s1", "zh", "other", "alice", "{count} 个文件", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(submitCategory(docId, "s1", "en", "one", "bob", "one file", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(submitCategory(docId, "s1", "en", "other", "carol", "{count} files", 1, newRequestId())
                .status()).isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "zh", "other", "dave", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "en", "one", "erin", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "en", "other", "frank", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        JsonNode segment = getJson("/api/documents/" + docId + "/releases/1").body().get("segments").get(0);
        assertThat(segment.get("plural").asBoolean()).isTrue();
        assertThat(segment.get("sourceVersion").asInt()).isEqualTo(1);
        JsonNode zh = segment.get("translations").get(0);
        assertThat(zh.get("language").asText()).isEqualTo("zh");
        assertThat(zh.get("categories")).hasSize(1);
        assertThat(zh.get("categories").get(0).get("category").asText()).isEqualTo("other");
        assertThat(zh.get("categories").get(0).get("author").asText()).isEqualTo("alice");
        assertThat(zh.get("categories").get(0).get("termVersion").asInt()).isZero();
        JsonNode en = segment.get("translations").get(1);
        assertThat(en.get("language").asText()).isEqualTo("en");
        assertThat(en.get("categories")).hasSize(2);
        assertThat(en.get("categories").get(0).get("category").asText()).isEqualTo("one");
        assertThat(en.get("categories").get(0).get("author").asText()).isEqualTo("bob");
        assertThat(en.get("categories").get(1).get("category").asText()).isEqualTo("other");
        assertThat(en.get("categories").get(1).get("author").asText()).isEqualTo("carol");
    }
    @Test
    @DisplayName("缺类别与额外类别：缺 other 发布 422；en 提交 few、zh 提交 one 均 422；集合内类别重复 422")
    void missingAndExtraCategories() throws Exception {
        long docId = createDocument(newRequestId(), "[\"zh\",\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} 个文件\",\"plural\":true}]");
        // 额外类别：en 不允许 few，zh 不允许 one
        assertThat(submitCategory(docId, "s1", "en", "few", "alice", "a few files", 1, newRequestId())
                .status()).isEqualTo(422);
        assertThat(submitCategory(docId, "s1", "zh", "one", "alice", "一个文件", 1, newRequestId()).status())
                .isEqualTo(422);
        // 集合内类别重复
        assertThat(submitCategories(docId, "s1", "en", "alice",
                "[{\"category\":\"one\",\"content\":\"one file\"},{\"category\":\"one\",\"content\":\"1 file\"}]",
                1, newRequestId()).status()).isEqualTo(422);

        // 只提交 zh/other 与 en/one 并批准：发布缺 en/other 类别，422 且无快照
        submitCategory(docId, "s1", "zh", "other", "alice", "{count} 个文件", 1, newRequestId());
        submitCategory(docId, "s1", "en", "one", "alice", "one file", 1, newRequestId());
        approveCategory(docId, "s1", "zh", "other", "bob", 1, newRequestId());
        approveCategory(docId, "s1", "en", "one", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("作者不能批准自己写的类别；类别译文版本不匹配 422")
    void authorCannotApproveOwnCategory() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} files\",\"plural\":true}]");
        submitCategory(docId, "s1", "en", "one", "alice", "one file", 1, newRequestId());
        assertThat(approveCategory(docId, "s1", "en", "one", "alice", 1, newRequestId()).status())
                .isEqualTo(422);
        assertThat(approveCategory(docId, "s1", "en", "one", "bob", 9, newRequestId()).status())
                .isEqualTo(422);
        assertThat(approveCategory(docId, "s1", "en", "one", "bob", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approveCategory(docId, "s1", "en", "other", "bob", 1, newRequestId()).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("修订源文使该段所有类别批准失效；修订一个类别只使该类别批准失效且不清空其他类别文本")
    void revisionInvalidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} files\",\"plural\":true}]");
        submitCategory(docId, "s1", "en", "one", "alice", "one file", 1, newRequestId());
        submitCategory(docId, "s1", "en", "other", "alice", "{count} files", 1, newRequestId());
        approveCategory(docId, "s1", "en", "one", "bob", 1, newRequestId());
        approveCategory(docId, "s1", "en", "other", "bob", 1, newRequestId());

        // 修订类别 one：仅 one 的批准失效，other 文本与批准保留
        ApiResult revised = submitCategory(docId, "s1", "en", "one", "alice", "exactly one file", 1,
                newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("translationVersion").asInt()).isEqualTo(2);
        String otherContent = jdbc.queryForObject(
                "SELECT content FROM plural_translation WHERE document_id = ? AND category = 'other'",
                String.class, docId);
        assertThat(otherContent).isEqualTo("{count} files");
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);
        // other 无需重新批准；one 重新批准后即可发布
        assertThat(approveCategory(docId, "s1", "en", "one", "bob", 2, newRequestId()).status())
                .isEqualTo(200);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // 修订源文：该段所有类别批准失效，发布 422
        assertThat(putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"{count} documents\"}").status())
                .isEqualTo(200);
        assertThat(publish(docId, 5, 1, newRequestId()).status()).isEqualTo(422);
        // 基于旧源文版本的类别提交也被拒绝
        assertThat(submitCategory(docId, "s1", "en", "one", "alice", "one document", 1, newRequestId())
                .status()).isEqualTo(422);
    }

    @Test
    @DisplayName("普通段落与复数段落契约隔离：普通段落拒类别提交，复数段落拒普通译文，非 zh/en 语言 422")
    void pluralContractIsolation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"hello\",\"plural\":false},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"{count} files\",\"plural\":true}]");
        // 普通段落不接受类别提交
        assertThat(submitCategory(docId, "s1", "en", "other", "alice", "你好", 1, newRequestId()).status())
                .isEqualTo(422);
        // 复数段落不接受普通译文提交
        assertThat(submitTranslation(docId, "s2", "en", "alice", "files", 1, newRequestId()).status())
                .isEqualTo(422);
        // 复数段落不支持 zh/en 以外的语言
        assertThat(submitCategory(docId, "s2", "ja", "other", "alice", "ファイル", 1, newRequestId())
                .status()).isEqualTo(422);
        // 普通段落原有契约不变：提交并批准普通译文成功
        assertThat(submitTranslation(docId, "s1", "en", "alice", "hello en", 1, newRequestId()).status())
                .isEqualTo(200);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);
    }
    @Test
    @DisplayName("幂等：同键同参重放首次响应，同键异参 409，失败不占键")
    void idempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} files\",\"plural\":true}]");
        // 同键同参：重放首次完整响应，业务只执行一次
        String requestId = newRequestId();
        ApiResult first = submitCategory(docId, "s1", "en", "one", "alice", "one file", 1, requestId);
        ApiResult replay = submitCategory(docId, "s1", "en", "one", "alice", "one file", 1, requestId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(replay.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(replay.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plural_translation WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);

        // 同键异参：409
        assertThat(submitCategory(docId, "s1", "en", "one", "alice", "different", 1, requestId).status())
                .isEqualTo(409);

        // 失败不占键：源文版本不匹配 422 后，同 requestId 用正确参数可成功
        String failedId = newRequestId();
        assertThat(submitCategory(docId, "s1", "en", "other", "alice", "{count} files", 99, failedId)
                .status()).isEqualTo(422);
        assertThat(submitCategory(docId, "s1", "en", "other", "alice", "{count} files", 1, failedId)
                .status()).isEqualTo(200);
    }

    @Test
    @DisplayName("集合请求换序视为同参：类别集合按名称排序规范化，重放首次响应")
    void setRequestOrderInsensitive() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{count} files\",\"plural\":true}]");
        String requestId = newRequestId();
        ApiResult first = submitCategories(docId, "s1", "en", "alice",
                "[{\"category\":\"one\",\"content\":\"one file\"},"
                        + "{\"category\":\"other\",\"content\":\"{count} files\"}]", 1, requestId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("categories").get(0).get("category").asText()).isEqualTo("one");
        assertThat(first.body().get("categories").get(1).get("category").asText()).isEqualTo("other");

        // 换序同参：重放首次响应，类别译文版本不递增、草稿版本不再加
        ApiResult replay = submitCategories(docId, "s1", "en", "alice",
                "[{\"category\":\"other\",\"content\":\"{count} files\"},"
                        + "{\"category\":\"one\",\"content\":\"one file\"}]", 1, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject("SELECT MAX(translation_version) FROM plural_translation "
                + "WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(2);

        // 同键异参（内容不同）：409
        assertThat(submitCategories(docId, "s1", "en", "alice",
                "[{\"category\":\"other\",\"content\":\"many files\"},"
                        + "{\"category\":\"one\",\"content\":\"one file\"}]", 1, requestId).status())
                .isEqualTo(409);
    }

    @Test
    @DisplayName("术语版本：类别提交绑定术语版本并校验必译；术语更新后旧类别发布 422")
    void termVersionBinding() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习 {count} 次\",\"plural\":true}]");
        assertThat(updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId()).status()).isEqualTo(201);
        // 违反术语规则的类别提交 422
        assertThat(submitCategory(docId, "s1", "en", "one", "alice", "one run", 1, newRequestId()).status())
                .isEqualTo(422);
        ApiResult one = submitCategory(docId, "s1", "en", "one", "alice", "machine learning once", 1,
                newRequestId());
        assertThat(one.status()).isEqualTo(200);
        assertThat(one.body().get("termVersion").asInt()).isEqualTo(1);
        submitCategory(docId, "s1", "en", "other", "alice", "machine learning {count} times", 1,
                newRequestId());
        approveCategory(docId, "s1", "en", "one", "bob", 1, newRequestId());
        approveCategory(docId, "s1", "en", "other", "bob", 1, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // 术语版本更新后，旧类别译文术语过期，发布 422
        assertThat(updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId()).status()).isEqualTo(201);
        assertThat(publish(docId, 5, 1, newRequestId()).status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
    }
}