package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文版式锁定主流程与失败分支：占位符声明、规范化排序、重复计数、提交/批准/发布各环节
 * 422 拦截、源文修订失效旧译文与旧批准、发布快照固化占位符版本。
 */
class LayoutLockApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("占位符声明：响应返回规范排序集合（含重复），非法格式 422 且不落段")
    void declarePlaceholdersCanonicalAndIllegal() throws Exception {
        ApiResult ok = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"{b} 对 {a} 说 {a}\","
                        + "\"placeholders\":[\"{b}\",\"{a}\",\"{a}\"]}]}");
        assertThat(ok.status()).isEqualTo(201);

        long docId = ok.body().get("documentId").asLong();
        ApiResult detail = getJson("/api/documents/" + docId);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.body().get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{a}\",\"{a}\",\"{b}\"]");

        ApiResult illegal = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"bad\",\"sourceText\":\"x\","
                        + "\"placeholders\":[\"{a}\",\"not-a-placeholder\"]}");
        assertThat(illegal.status()).isEqualTo(422);
        assertThat(illegal.body().get("message").asText()).contains("not-a-placeholder");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM segment WHERE document_id = ? AND segment_id = 'bad'",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("译文提交：占位符完全一致（顺序无关）成功并回显要求/实际集合；缺失或额外占位符 422 且不写译文")
    void submitTranslationPlaceholderLock() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{b} 对 {a} 说 {a}\","
                        + "\"placeholders\":[\"{b}\",\"{a}\",\"{a}\"]}]");

        // 顺序不同但多重集合一致：成功
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "{a} tells {a} to {b}", 1,
                newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("requiredPlaceholders").toString())
                .isEqualTo("[\"{a}\",\"{a}\",\"{b}\"]");
        assertThat(ok.body().get("actualPlaceholders").toString())
                .isEqualTo("[\"{a}\",\"{a}\",\"{b}\"]");

        // 缺失一个 {a}：422，消息含要求/实际/差额
        ApiResult missing = submitTranslation(docId, "s1", "en", "alice", "{a} to {b}", 1,
                newRequestId());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("message").asText())
                .contains("{a}").contains("要求 2").contains("实际 1").contains("差额 1");

        // 额外占位符 {c}：422
        ApiResult extra = submitTranslation(docId, "s1", "en", "alice", "{a} {a} {b} {c}", 1,
                newRequestId());
        assertThat(extra.status()).isEqualTo(422);
        assertThat(extra.body().get("message").asText())
                .contains("{c}").contains("要求 0").contains("实际 1").contains("差额 1");

        // 计数不一致（{a} 三次）：422
        ApiResult count = submitTranslation(docId, "s1", "en", "alice", "{a} {a} {a} {b}", 1,
                newRequestId());
        assertThat(count.status()).isEqualTo(422);

        // 失败均未覆盖首条成功译文
        assertThat(jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                String.class, docId)).isEqualTo("{a} tells {a} to {b}");
    }

    @Test
    @DisplayName("批准与发布：占位符当前不一致的译文不能批准；发布侧再次复核占位符集合")
    void approveAndPublishRecheckPlaceholders() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{name} 你好\",\"placeholders\":[\"{name}\"]}]");
        // 直接构造占位符不一致的译文无法通过提交接口，故先提交一致译文，再用修订改变占位符集合制造不一致
        submitTranslation(docId, "s1", "en", "alice", "hello {name}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 修订源段：新源版本声明 {name}{count}，旧译文缺 {count} 且旧批准被清除
        ApiResult revised = reviseSource(docId, "s1", "{name} 有 {count} 项", "[\"{count}\",\"{name}\"]",
                newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(revised.body().get("placeholders").toString()).isEqualTo("[\"{count}\",\"{name}\"]");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();

        // 旧译文（仍是 v1 内容、基于源版本 1）不能批准：源版本不匹配优先 422
        ApiResult staleApprove = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(staleApprove.status()).isEqualTo(422);

        // 重新提交缺占位符的译文：提交侧 422
        ApiResult badResubmit = submitTranslation(docId, "s1", "en", "alice", "hello {name}", 2,
                newRequestId());
        assertThat(badResubmit.status()).isEqualTo(422);
        assertThat(badResubmit.body().get("message").asText()).contains("{count}");

        // 提交占位符齐全的译文后批准并发布
        submitTranslation(docId, "s1", "en", "alice", "{name} has {count} items", 2, newRequestId());
        ApiResult approved = approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.body().get("placeholders").toString()).isEqualTo("[\"{count}\",\"{name}\"]");
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("源文修订禁止旧译文发布：旧译文保留但待修订，发布 422；明细与诊断可定位原因")
    void revisionBlocksStaleTranslationFromPublish() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文 {x}\",\"placeholders\":[\"{x}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "text {x}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 草稿版本 2

        // 修订源文但暂不重新提交：旧译文仍在，状态待修订
        reviseSource(docId, "s1", "修订 {x} {y}", "[\"{x}\",\"{y}\"]", newRequestId());
        ApiResult blocked = publish(docId, 3, 0, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.body().get("message").asText()).contains("待更新");

        // 诊断：要求 1 条、就绪 0 条，原因含待更新与缺批准
        ApiResult diag = getJson("/api/documents/" + docId + "/diagnostics");
        assertThat(diag.status()).isEqualTo(200);
        assertThat(diag.body().get("requiredCount").asInt()).isEqualTo(1);
        assertThat(diag.body().get("readyCount").asInt()).isZero();
        assertThat(diag.body().get("ready").asBoolean()).isFalse();
        assertThat(diag.body().get("items").get(0).get("reasons").toString())
                .contains("待更新").contains("缺少批准");
        assertThat(diag.body().get("items").get(0).get("placeholders").get("matched").asBoolean()).isFalse();
        assertThat(diag.body().get("items").get(0).get("placeholders").get("missing").toString())
                .contains("{y}");

        // 明细：批准视图 invalid=false（无批准），译文 sourceVersion 落后
        ApiResult detail = getJson("/api/documents/" + docId);
        var translation = detail.body().get("segments").get(0).get("translations").get(0);
        assertThat(translation.get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(translation.get("approval").get("valid").asBoolean()).isFalse();
        assertThat(translation.get("approval").get("reviewer").isNull()).isTrue();
    }

    @Test
    @DisplayName("发布快照固化占位符版本：发布后修订源段占位符不改写旧快照")
    void snapshotFreezesPlaceholders() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{a}\",\"placeholders\":[\"{a}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "x {a}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        reviseSource(docId, "s1", "{a}{b}", "[\"{a}\",\"{b}\"]", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "{a}{b}", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 4, 1, newRequestId()).status()).isEqualTo(201);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{a}\"]");
        var t1 = release1.body().get("segments").get(0).get("translations").get(0);
        assertThat(t1.get("actualPlaceholders").toString()).isEqualTo("[\"{a}\"]");
        assertThat(t1.get("placeholderMatched").asBoolean()).isTrue();

        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("segments").get(0).get("placeholders").toString())
                .isEqualTo("[\"{a}\",\"{b}\"]");
    }
}
