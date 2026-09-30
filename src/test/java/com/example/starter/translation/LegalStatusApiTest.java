package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 译文法律效力状态集成测试：真实 H2（MODE=MySQL）验证 BINDING 双人签署、
 * 缺签/同人重复签/版本修订拒绝、幂等重放、快照固化与法律效力查询分区。
 */
class LegalStatusApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("BINDING 主流程：两名不同签署人确认当前译文版本后发布 201，快照仅覆盖签署语种并固化签署人")
    void bindingPublishWithTwoDistinctSigners() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        // 三次提交使译文成为 V3
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "v2", 1, newRequestId());
        ApiResult v3 = submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        assertThat(v3.body().get("translationVersion").asInt()).isEqualTo(3);
        // 当前草稿版本 4；签署不改变草稿版本

        ApiResult signedByJia = sign(docId, "s1", "en", "jia", 3, newRequestId());
        assertThat(signedByJia.status()).isEqualTo(201);
        assertThat(signedByJia.body().get("language").asText()).isEqualTo("en");
        assertThat(signedByJia.body().get("translationVersion").asInt()).isEqualTo(3);
        assertThat(signedByJia.body().get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(signedByJia.body().get("signedAt").asText()).isNotBlank();
        ApiResult signedByYi = sign(docId, "s1", "en", "yi", 3, newRequestId());
        assertThat(signedByYi.status()).isEqualTo(201);

        ApiResult published = publishLegal(docId, 4, 0, "BINDING", "en", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(published.body().get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(published.body().get("language").asText()).isEqualTo("en");

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(release.body().get("language").asText()).isEqualTo("en");
        var translation = release.body().get("segments").get(0).get("translation");
        assertThat(translation.get("content").asText()).isEqualTo("v3");
        assertThat(translation.get("translationVersion").asInt()).isEqualTo(3);
        assertThat(translation.get("signers")).hasSize(2);
        assertThat(translation.get("signers").toString()).contains("jia", "yi");
        assertThat(translation.has("reviewer")).isFalse();
    }

    @Test
    @DisplayName("BINDING 无需审核批准：仅双人签署即可发布；INFORMATIONAL 缺批准仍 422")
    void bindingDoesNotRequireApproval() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        // 无批准：INFORMATIONAL 发布沿用原条件 422
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(422);

        sign(docId, "s1", "en", "bob", 1, newRequestId());
        sign(docId, "s1", "en", "carol", 1, newRequestId());
        ApiResult published = publishLegal(docId, 2, 0, "BINDING", "en", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("缺签 422 且不改变当前已发布版本；多段落须逐段满足两名签署人")
    void missingSignaturesRejectedWithoutChangingPublishedVersion() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "t1", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "t2", 1, newRequestId());
        // 草稿版本 3；s1 双人签署、s2 仅一人签署
        sign(docId, "s1", "en", "bob", 1, newRequestId());
        sign(docId, "s1", "en", "carol", 1, newRequestId());
        sign(docId, "s2", "en", "bob", 1, newRequestId());

        ApiResult rejected = publishLegal(docId, 3, 0, "BINDING", "en", newRequestId());
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().get("message").asText()).contains("s2");
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 先以 INFORMATIONAL 发布到版本 1，随后 BINDING 失败：当前已发布版本保持 1
        approve(docId, "s1", "en", "dave", 1, newRequestId());
        approve(docId, "s2", "en", "dave", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult bindingRejected = publishLegal(docId, 3, 1, "BINDING", "en", newRequestId());
        assertThat(bindingRejected.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        ApiResult effect = legalEffect(docId);
        assertThat(effect.body().get("currentPublishedVersion").asInt()).isEqualTo(1);
        assertThat(effect.body().get("currentLegalStatus").asText()).isEqualTo("INFORMATIONAL");

        // 补齐 s2 第二名签署人后 BINDING 发布为版本 2
        sign(docId, "s2", "en", "erin", 1, newRequestId());
        ApiResult ok = publishLegal(docId, 3, 1, "BINDING", "en", newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("publishedVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("同人重复签 422，且重复签不能充当第二名签署人；不同人并发签同一版本可共存")
    void duplicateSignerRejected() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());

        ApiResult first = sign(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(first.status()).isEqualTo(201);
        ApiResult duplicate = sign(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);
        // 唯一约束实际拦截：库里只有一条 bob 的签署
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM binding_signature WHERE document_id = ? AND signer = 'bob'",
                Integer.class, docId)).isEqualTo(1);

        // 仅 bob 一人签署不能 BINDING 发布
        ApiResult rejected = publishLegal(docId, 2, 0, "BINDING", "en", newRequestId());
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().get("message").asText()).contains("两名不同签署人");

        sign(docId, "s1", "en", "carol", 1, newRequestId());
        assertThat(publishLegal(docId, 2, 0, "BINDING", "en", newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("V3 经甲乙签署可 BINDING 发布；先修订为 V4 后，原两份 V3 签署不能用于 V4 发布")
    void revisionV4InvalidatesV3Signatures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "v2", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        sign(docId, "s1", "en", "jia", 3, newRequestId());
        sign(docId, "s1", "en", "yi", 3, newRequestId());
        // 草稿版本 4

        // 修订源文到版本 2（草稿 5），再据新源文提交译文 V4（草稿 6）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文V4\"}");
        ApiResult v4 = submitTranslation(docId, "s1", "en", "alice", "v4", 2, newRequestId());
        assertThat(v4.body().get("translationVersion").asInt()).isEqualTo(4);

        // 原两份 V3 签署不能用于 V4 发布：422 且未发布
        ApiResult rejected = publishLegal(docId, 6, 0, "BINDING", "en", newRequestId());
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 查询中草稿签署仍可追溯但标记无效
        ApiResult effect = legalEffect(docId);
        var signatures = effect.body().get("segments").get(0).get("languages").get(0).get("draftSignatures");
        assertThat(signatures).hasSize(2);
        for (var signature : signatures) {
            assertThat(signature.get("translationVersion").asInt()).isEqualTo(3);
            assertThat(signature.get("valid").asBoolean()).isFalse();
        }

        // 对 V4 重新双人签署后可发布
        sign(docId, "s1", "en", "jia", 4, newRequestId());
        sign(docId, "s1", "en", "yi", 4, newRequestId());
        ApiResult published = publishLegal(docId, 6, 0, "BINDING", "en", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("签署校验：译文版本已修订 422；译文基于旧源文 422；译文不存在 404；非法 legalStatus 400；BINDING 缺 language 422")
    void signValidationBranches() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "v2", 1, newRequestId());
        // 当前译文版本 2：签 V1 已被修订
        ApiResult oldVersion = sign(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(oldVersion.status()).isEqualTo(422);
        assertThat(oldVersion.body().get("message").asText()).contains("已被修订");
        // 不存在的译文版本号同样 422
        assertThat(sign(docId, "s1", "en", "bob", 99, newRequestId()).status()).isEqualTo(422);
        // 译文不存在 404
        assertThat(sign(docId, "s1", "ja", "bob", 1, newRequestId()).status()).isEqualTo(404);

        // 源文修订后旧译文待更新：签署 422
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后\"}");
        ApiResult stale = sign(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(stale.status()).isEqualTo(422);
        assertThat(stale.body().get("message").asText()).contains("译文待更新");

        // 非法 legalStatus 为参数校验 400（当前草稿版本 4：建文档 1 + 两次提交 2 + 源文修订 1）
        ApiResult badStatus = postJson("/api/documents/" + docId + "/publish",
                "{\"requestId\":\"" + newRequestId() + "\",\"expectedDraftVersion\":4,"
                        + "\"expectedPublishedVersion\":0,\"legalStatus\":\"SEALED\"}");
        assertThat(badStatus.status()).isEqualTo(400);

        // BINDING 缺 language：业务校验 422
        ApiResult noLanguage = postJson("/api/documents/" + docId + "/publish",
                "{\"requestId\":\"" + newRequestId() + "\",\"expectedDraftVersion\":4,"
                        + "\"expectedPublishedVersion\":0,\"legalStatus\":\"BINDING\"}");
        assertThat(noLanguage.status()).isEqualTo(422);
        // 语言不在目标语种：重新提交 V3（基于源文 V2）后草稿版本 5
        submitTranslation(docId, "s1", "en", "alice", "v3", 2, newRequestId());
        sign(docId, "s1", "en", "bob", 3, newRequestId());
        sign(docId, "s1", "en", "carol", 3, newRequestId());
        assertThat(publishLegal(docId, 5, 0, "BINDING", "fr", newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("幂等：同 requestId 重放首次 BINDING 发布快照；改语种或期望版本 409")
    void bindingPublishIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        sign(docId, "s1", "en", "bob", 1, newRequestId());
        sign(docId, "s1", "en", "carol", 1, newRequestId());
        // 草稿版本 2

        String publishRequestId = newRequestId();
        ApiResult first = publishLegal(docId, 2, 0, "BINDING", "en", publishRequestId);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("publishedVersion").asInt()).isEqualTo(1);

        // 同键同参：重放首次结果，不再产生新版本
        ApiResult replay = publishLegal(docId, 2, 0, "BINDING", "en", publishRequestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(replay.body().get("language").asText()).isEqualTo("en");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 同键改语种：409
        ApiResult changedLanguage = publishLegal(docId, 2, 0, "BINDING", "ja", publishRequestId);
        assertThat(changedLanguage.status()).isEqualTo(409);
        // 同键改期望版本：409
        ApiResult changedVersion = publishLegal(docId, 2, 1, "BINDING", "en", publishRequestId);
        assertThat(changedVersion.status()).isEqualTo(409);
        // 同键改法律效力：409
        ApiResult changedStatus = publish(docId, 2, 0, publishRequestId);
        assertThat(changedStatus.status()).isEqualTo(409);
        // 冲突请求不改变已发布版本
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 签署幂等：同键同参重放成功签署；同键改译文版本 409
        String signRequestId = newRequestId();
        ApiResult signFirst = sign(docId, "s1", "en", "dave", 1, signRequestId);
        assertThat(signFirst.status()).isEqualTo(201);
        ApiResult signReplay = sign(docId, "s1", "en", "dave", 1, signRequestId);
        assertThat(signReplay.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM binding_signature WHERE document_id = ? AND signer = 'dave'",
                Integer.class, docId)).isEqualTo(1);
        ApiResult signChanged = sign(docId, "s1", "en", "dave", 2, signRequestId);
        assertThat(signChanged.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("后续修订不回写已发布签署人与正文快照；法律效力查询区分草稿、当前效力与历史发布")
    void publishedSnapshotImmutableAndQuerySections() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        sign(docId, "s1", "en", "jia", 1, newRequestId());
        sign(docId, "s1", "en", "yi", 1, newRequestId());
        assertThat(publishLegal(docId, 2, 0, "BINDING", "en", newRequestId()).status()).isEqualTo(201);

        // 修订源文并提交 V4，旧签署失效但历史发布不变
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后原文\"}");
        submitTranslation(docId, "s1", "en", "alice", "v4", 2, newRequestId());

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("原文");
        var publishedTranslation = release.body().get("segments").get(0).get("translation");
        assertThat(publishedTranslation.get("content").asText()).isEqualTo("v3");
        assertThat(publishedTranslation.get("translationVersion").asInt()).isEqualTo(1);
        assertThat(publishedTranslation.get("signers").toString()).contains("jia", "yi");

        // 法律效力查询：当前效力为版本 1 的 BINDING/en；草稿签署标记无效；历史发布列出 1 条
        ApiResult effect = legalEffect(docId);
        assertThat(effect.status()).isEqualTo(200);
        assertThat(effect.body().get("currentPublishedVersion").asInt()).isEqualTo(1);
        assertThat(effect.body().get("currentLegalStatus").asText()).isEqualTo("BINDING");
        assertThat(effect.body().get("currentLanguage").asText()).isEqualTo("en");
        var languageEffect = effect.body().get("segments").get(0).get("languages").get(0);
        assertThat(languageEffect.get("language").asText()).isEqualTo("en");
        assertThat(languageEffect.get("bindingPublishedVersion").asInt()).isEqualTo(1);
        assertThat(languageEffect.get("draftSignatures")).hasSize(2);
        for (var signature : languageEffect.get("draftSignatures")) {
            assertThat(signature.get("valid").asBoolean()).isFalse();
        }
        assertThat(effect.body().get("releases")).hasSize(1);
        assertThat(effect.body().get("releases").get(0).get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(effect.body().get("releases").get(0).get("language").asText()).isEqualTo("en");
    }

    @Test
    @DisplayName("INFORMATIONAL 与 BINDING 可交替发布；历史查询保留各自状态，INFORMATIONAL 快照含全部语种")
    void mixedReleasesKeptSeparately() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "en-v1", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "ja-v1", 1, newRequestId());
        sign(docId, "s1", "en", "bob", 1, newRequestId());
        sign(docId, "s1", "en", "carol", 1, newRequestId());
        // 草稿版本 3

        // 先发布 en 的 BINDING 版本 1（仅覆盖 en）
        assertThat(publishLegal(docId, 3, 0, "BINDING", "en", newRequestId()).status()).isEqualTo(201);
        // 再补齐批准发布 INFORMATIONAL 版本 2（覆盖 en、ja）
        approve(docId, "s1", "en", "dave", 1, newRequestId());
        approve(docId, "s1", "ja", "dave", 1, newRequestId());
        assertThat(publish(docId, 3, 1, newRequestId()).status()).isEqualTo(201);

        ApiResult bindingRelease = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(bindingRelease.body().get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(bindingRelease.body().get("language").asText()).isEqualTo("en");
        ApiResult infoRelease = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(infoRelease.body().get("legalStatus").asText()).isEqualTo("INFORMATIONAL");
        assertThat(infoRelease.body().get("segments").get(0).get("translations")).hasSize(2);

        ApiResult effect = legalEffect(docId);
        assertThat(effect.body().get("currentPublishedVersion").asInt()).isEqualTo(2);
        assertThat(effect.body().get("currentLegalStatus").asText()).isEqualTo("INFORMATIONAL");
        assertThat(effect.body().get("releases")).hasSize(2);
        // en 历史上已有 BINDING 版本 1；ja 从未有过 BINDING
        var languages = effect.body().get("segments").get(0).get("languages");
        assertThat(languages.get(0).get("language").asText()).isEqualTo("en");
        assertThat(languages.get(0).get("bindingPublishedVersion").asInt()).isEqualTo(1);
        assertThat(languages.get(1).get("bindingPublishedVersion").asInt()).isZero();
    }
}
