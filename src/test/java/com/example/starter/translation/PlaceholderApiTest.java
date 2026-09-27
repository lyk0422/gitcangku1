package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文版式占位锁定：声明、译文完整性校验、批量批准与快照固化测试。
 */
class PlaceholderApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("占位符声明：创建新源版本与占位符版本，集合规范排序，草稿版本加一")
    void declarePlaceholdersSuccess() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"请点击 {button} 查看 {image}\"}]");
        ApiResult declared = declarePlaceholders(docId, "s1", "请点击 {button} 查看 {image}",
                "[\"{image}\",\"{button}\"]", newRequestId());
        assertThat(declared.status()).isEqualTo(200);
        assertThat(declared.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(declared.body().get("placeholderVersion").asInt()).isEqualTo(1);
        assertThat(declared.body().get("draftVersion").asInt()).isEqualTo(2);
        // 响应集合按占位符原文规范排序
        assertThat(declared.body().get("placeholders").get(0).asText()).isEqualTo("{button}");
        assertThat(declared.body().get("placeholders").get(1).asText()).isEqualTo("{image}");

        ApiResult status = getJson("/api/documents/" + docId + "/segments/s1/placeholders");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("placeholderVersion").asInt()).isEqualTo(1);
        assertThat(status.body().get("placeholders")).hasSize(2);
        assertThat(status.body().get("placeholders").get(0).get("placeholder").asText())
                .isEqualTo("{button}");
        assertThat(status.body().get("placeholders").get(0).get("requiredCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("占位符声明：格式非法、重复、与源文集合不一致均返回 422 且不改变状态")
    void declarePlaceholdersValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"请点击 {button}\"}]");

        ApiResult badFormat = declarePlaceholders(docId, "s1", "请点击 {button}",
                "[\"button\"]", newRequestId());
        assertThat(badFormat.status()).isEqualTo(422);

        ApiResult duplicate = declarePlaceholders(docId, "s1", "请点击 {button}",
                "[\"{button}\",\"{button}\"]", newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        // 声明缺失源文中的占位符
        ApiResult missing = declarePlaceholders(docId, "s1", "请点击 {button}", "[]", newRequestId());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("error").asText()).isEqualTo("PLACEHOLDER_VIOLATION");

        // 声明包含源文中不存在的占位符
        ApiResult extra = declarePlaceholders(docId, "s1", "请点击 {button}",
                "[\"{button}\",\"{link}\"]", newRequestId());
        assertThat(extra.status()).isEqualTo(422);

        // 全部失败不改变段落状态
        ApiResult status = getJson("/api/documents/" + docId + "/segments/s1/placeholders");
        assertThat(status.body().get("placeholderVersion").asInt()).isZero();
        assertThat(status.body().get("placeholders")).isEmpty();
        Integer sourceVersion = jdbc.queryForObject(
                "SELECT source_version FROM segment WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId);
        assertThat(sourceVersion).isEqualTo(1);
    }

    @Test
    @DisplayName("译文占位符校验：缺失、额外、计数不一致均 422 并返回要求/实际次数明细")
    void translationPlaceholderValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"请点击 {button} 查看 {image}\"}]");
        declarePlaceholders(docId, "s1", "请点击 {button} 查看 {image}",
                "[\"{button}\",\"{image}\"]", newRequestId());

        // 缺失 {image}
        ApiResult missing = submitTranslation(docId, "s1", "en", "alice", "click {button}", 2,
                newRequestId());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("error").asText()).isEqualTo("PLACEHOLDER_VIOLATION");
        var violation = missing.body().get("placeholderViolations").get(0);
        assertThat(violation.get("placeholder").asText()).isEqualTo("{image}");
        assertThat(violation.get("type").asText()).isEqualTo("MISSING");
        assertThat(violation.get("requiredCount").asInt()).isEqualTo(1);
        assertThat(violation.get("actualCount").asInt()).isZero();

        // 额外 {link}
        ApiResult extra = submitTranslation(docId, "s1", "en", "alice",
                "click {button} see {image} {link}", 2, newRequestId());
        assertThat(extra.status()).isEqualTo(422);
        var extraViolation = extra.body().get("placeholderViolations").get(0);
        assertThat(extraViolation.get("type").asText()).isEqualTo("EXTRA");
        assertThat(extraViolation.get("placeholder").asText()).isEqualTo("{link}");
        assertThat(extraViolation.get("requiredCount").asInt()).isZero();
        assertThat(extraViolation.get("actualCount").asInt()).isEqualTo(1);

        // 计数不一致：源文 {icon} 出现两次，译文只出现一次
        declarePlaceholders(docId, "s1", "请 {icon} 再 {icon} 一次", "[\"{icon}\"]", newRequestId());
        ApiResult mismatch = submitTranslation(docId, "s1", "en", "alice", "click {icon}", 3,
                newRequestId());
        assertThat(mismatch.status()).isEqualTo(422);
        var mismatchViolation = mismatch.body().get("placeholderViolations").get(0);
        assertThat(mismatchViolation.get("type").asText()).isEqualTo("COUNT_MISMATCH");
        assertThat(mismatchViolation.get("requiredCount").asInt()).isEqualTo(2);
        assertThat(mismatchViolation.get("actualCount").asInt()).isEqualTo(1);

        // 全部失败不落译文
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 完整保留占位符集合与次数：成功
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "click {icon} then {icon}", 3,
                newRequestId());
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("占位符修订：所有目标译文转为待修订，禁止用旧译文发布；快照固化占位符版本")
    void placeholderRevisionStalesTranslations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"打开 {file}\"}]");
        declarePlaceholders(docId, "s1", "打开 {file}", "[\"{file}\"]", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "open {file}", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 修订占位符：新源版本 3、占位符版本 2，旧译文转为待修订
        ApiResult revised = declarePlaceholders(docId, "s1", "打开 {file} 并 {save}",
                "[\"{file}\",\"{save}\"]", newRequestId());
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(3);
        assertThat(revised.body().get("placeholderVersion").asInt()).isEqualTo(2);

        // 禁止用旧译文发布
        ApiResult stalePublish = publish(docId, 4, 0, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);

        // 基于新源版本重新提交、批准后发布成功
        submitTranslation(docId, "s1", "en", "alice", "open {file} and {save}", 3, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照固化占位符版本与集合
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("placeholderVersion").asInt()).isEqualTo(2);
        assertThat(segment.get("placeholders")).hasSize(2);
        assertThat(segment.get("placeholders").get(0).get("placeholder").asText()).isEqualTo("{file}");
        assertThat(segment.get("placeholders").get(1).get("placeholder").asText()).isEqualTo("{save}");

        // 后续占位符修订不改写旧快照
        declarePlaceholders(docId, "s1", "打开 {file}", "[\"{file}\"]", newRequestId());
        ApiResult releaseAgain = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseAgain.body().get("segments").get(0).get("placeholderVersion").asInt())
                .isEqualTo(2);
        assertThat(releaseAgain.body().get("segments").get(0).get("placeholders")).hasSize(2);
    }

    @Test
    @DisplayName("源文修订守卫：已声明占位符的段落，普通源文修订不得改变占位符集合")
    void reviseSourceKeepsDeclaredPlaceholders() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好 {name}\"}]");
        declarePlaceholders(docId, "s1", "你好 {name}", "[\"{name}\"]", newRequestId());

        // 改变占位符：422，且源文版本不变
        ApiResult changed = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"你好 {user}\"}");
        assertThat(changed.status()).isEqualTo(422);
        assertThat(changed.body().get("error").asText()).isEqualTo("PLACEHOLDER_VIOLATION");

        // 保持占位符集合与次数：允许修订
        ApiResult kept = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"亲爱的 {name} 你好\"}");
        assertThat(kept.status()).isEqualTo(200);
        assertThat(kept.body().get("sourceVersion").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("批量批准：全部完整时一次批准；任一段不完整整次 422 且不改变批准状态")
    void batchApproveAtomicity() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好 {name}\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"再见\"}]");
        declarePlaceholders(docId, "s1", "你好 {name}", "[\"{name}\"]", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "hello {name}", 2, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "こんにちは {name}", 2, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "bye", 1, newRequestId());

        // s2/ja 缺译文：整次 422，不产生任何批准
        ApiResult incomplete = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"ja\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(incomplete.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 译文版本不匹配：整次 422，仍无批准
        ApiResult wrongVersion = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":9},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 审核人是译文作者：整次 422
        ApiResult byAuthor = batchApprove(docId, "alice",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(byAuthor.status()).isEqualTo(422);

        // 全部完整：一次批准三条
        ApiResult ok = batchApprove(docId, "bob",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1}]",
                newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(ok.body().get("approvedCount").asInt()).isEqualTo(3);
        assertThat(ok.body().get("approvals")).hasSize(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("占位符声明幂等：同键同参重放首次响应，同键异参 409，失败不占键")
    void declarePlaceholdersIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好 {name}\"}]");
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"sourceText\":\"你好 {name}\","
                + "\"placeholders\":[\"{name}\"]}";
        String url = "/api/documents/" + docId + "/segments/s1/placeholders";

        ApiResult first = putJson(url, body);
        assertThat(first.status()).isEqualTo(200);
        ApiResult replay = putJson(url, body);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        // 重放不重复推进版本
        assertThat(replay.body().get("placeholderVersion").asInt()).isEqualTo(1);

        ApiResult conflict = putJson(url, "{\"requestId\":\"" + requestId
                + "\",\"sourceText\":\"你好 {name} 再见\",\"placeholders\":[\"{name}\"]}");
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先 422，再同键修正参数成功
        String failKey = newRequestId();
        ApiResult failed = putJson(url, "{\"requestId\":\"" + failKey
                + "\",\"sourceText\":\"你好 {name}\",\"placeholders\":[\"{bad\"]}");
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = putJson(url, "{\"requestId\":\"" + failKey
                + "\",\"sourceText\":\"你好 {name} 午安\",\"placeholders\":[\"{name}\"]}");
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("placeholderVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("占位符诊断查询：返回各语言译文完整性明细；文档或段落不存在 404")
    void placeholderStatusDiagnostics() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"你好 {name}\"}]");
        declarePlaceholders(docId, "s1", "你好 {name}", "[\"{name}\"]", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "hello {name}", 2, newRequestId());

        ApiResult status = getJson("/api/documents/" + docId + "/segments/s1/placeholders");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("placeholderVersion").asInt()).isEqualTo(1);
        assertThat(status.body().get("translations")).hasSize(1);
        var enStatus = status.body().get("translations").get(0);
        assertThat(enStatus.get("language").asText()).isEqualTo("en");
        assertThat(enStatus.get("placeholderStale").asBoolean()).isFalse();
        assertThat(enStatus.get("violations")).isEmpty();

        // 占位符修订后译文转为待修订
        declarePlaceholders(docId, "s1", "你好 {name} 见 {title}", "[\"{name}\",\"{title}\"]",
                newRequestId());
        ApiResult stale = getJson("/api/documents/" + docId + "/segments/s1/placeholders");
        var staleEn = stale.body().get("translations").get(0);
        assertThat(staleEn.get("placeholderStale").asBoolean()).isTrue();
        assertThat(staleEn.get("violations").get(0).get("type").asText()).isEqualTo("MISSING");

        assertThat(getJson("/api/documents/999999/segments/s1/placeholders").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/" + docId + "/segments/nope/placeholders").status())
                .isEqualTo(404);
    }
}
