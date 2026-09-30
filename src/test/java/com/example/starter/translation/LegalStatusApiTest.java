package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 法律效力状态（INFORMATIONAL/BINDING）主流程、拒绝分支与状态边界的真实 H2 数据库测试。
 */
class LegalStatusApiTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("默认全部语种 INFORMATIONAL；切换 BINDING 草稿版本加一，重复设同值不加一；语种越界 422；非法状态 400")
    void legalStatusDefaultsAndSwitch() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]", "[]");

        ApiResult initial = getJson("/api/documents/" + docId + "/legal-status");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.body().get("draftVersion").asInt()).isEqualTo(1);
        assertThat(initial.body().get("publishedVersion").asInt()).isZero();
        assertThat(initial.body().get("languages")).hasSize(2);
        assertThat(initial.body().get("languages").get(0).get("language").asText()).isEqualTo("en");
        assertThat(initial.body().get("languages").get(0).get("draftLegalStatus").asText())
                .isEqualTo("INFORMATIONAL");
        assertThat(initial.body().get("languages").get(0).get("publishedLegalStatus").isNull()).isTrue();

        ApiResult binding = updateLegalStatus(docId, "EN", "BINDING", newRequestId());
        assertThat(binding.status()).isEqualTo(200);
        assertThat(binding.body().get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(binding.body().get("draftVersion").asInt()).isEqualTo(2);

        // 重复设为相同值：成功但草稿版本不变
        ApiResult again = updateLegalStatus(docId, "en", "BINDING", newRequestId());
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("draftVersion").asInt()).isEqualTo(2);

        ApiResult wrongLanguage = updateLegalStatus(docId, "fr", "BINDING", newRequestId());
        assertThat(wrongLanguage.status()).isEqualTo(422);
        ApiResult badValue = putJson("/api/documents/" + docId + "/languages/en/legal-status",
                "{\"requestId\":\"" + newRequestId() + "\",\"legalStatus\":\"MAGIC\"}");
        assertThat(badValue.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("BINDING 主流程：V3 经两名不同签署人签署后发布成功，快照固化语种状态、签署人与签署时间")
    void bindingPublishWithTwoSigners() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        updateLegalStatus(docId, "en", "BINDING", newRequestId());
        // 译文经两次重新提交到达 V3（每次草稿版本加一）
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "v2", 1, newRequestId());
        ApiResult v3 = submitTranslation(docId, "s1", "en", "alice", "v3", 1, newRequestId());
        assertThat(v3.body().get("translationVersion").asInt()).isEqualTo(3);
        // 当前草稿版本：1 建文档 + 1 切状态 + 3 次提交 = 5；签署不递增草稿版本
        ApiResult jia = sign(docId, "s1", "en", "jia", 3, newRequestId());
        ApiResult yi = sign(docId, "s1", "en", "yi", 3, newRequestId());
        assertThat(jia.status()).isEqualTo(201);
        assertThat(yi.status()).isEqualTo(201);
        assertThat(jia.body().get("signedAt").asText()).contains("+08:00");
        assertThat(yi.body().get("language").asText()).isEqualTo("en");

        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var translation = release.body().get("segments").get(0).get("translations").get(0);
        assertThat(translation.get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(translation.get("content").asText()).isEqualTo("v3");
        assertThat(translation.get("translationVersion").asInt()).isEqualTo(3);
        assertThat(translation.get("signers")).hasSize(2);
        assertThat(translation.get("signers").toString()).contains("jia", "yi");
        assertThat(translation.get("signers").get(0).get("signedAt").asText()).contains("+08:00");
        assertThat(translation.has("reviewer")).isFalse();
        assertThat(release.body().get("languageLegalStatuses").get(0).get("legalStatus").asText())
                .isEqualTo("BINDING");
    }

    @Test
    @DisplayName("BINDING 拒绝分支：缺签、仅一名签署人均 422；同人重复签 422；不改变已发布版本、不产生快照")
    void bindingPublishRejections() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 先以 INFORMATIONAL 发布一次：发布版本 1、草稿版本 2
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 切换 BINDING：草稿版本 3
        updateLegalStatus(docId, "en", "BINDING", newRequestId());

        // 无签署：422，当前已发布版本保持 1
        ApiResult noSignature = publish(docId, 3, 1, newRequestId());
        assertThat(noSignature.status()).isEqualTo(422);
        assertThat(noSignature.body().get("message").asText()).contains("缺少签署");

        // 仅一名签署人：422（作者也可以签署，但两名必须不同）
        assertThat(sign(docId, "s1", "en", "jia", 1, newRequestId()).status()).isEqualTo(201);
        ApiResult oneSigner = publish(docId, 3, 1, newRequestId());
        assertThat(oneSigner.status()).isEqualTo(422);
        assertThat(oneSigner.body().get("message").asText()).contains("仅有 1 名不同签署人");

        // 同一签署人对同一译文版本重复签：422（唯一约束兜底）
        ApiResult duplicate = sign(docId, "s1", "en", "jia", 1, newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        // 已发布版本与历史快照均未改变
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        ApiResult releaseStillV1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseStillV1.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("v1");
    }

    @Test
    @DisplayName("修订为 V4 后：签署旧版 V3 被拒；两份 V3 签署不能用于 V4 发布；补齐 V4 签署后发布且历史快照不回写")
    void revisionInvalidatesSignatures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文V1\"}]");
        updateLegalStatus(docId, "en", "BINDING", newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "译文V3", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "译文V3-2", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "译文V3", 1, newRequestId());
        sign(docId, "s1", "en", "jia", 3, newRequestId());
        sign(docId, "s1", "en", "yi", 3, newRequestId());
        // 草稿版本 5，首次发布成功
        assertThat(publish(docId, 5, 0, newRequestId()).status()).isEqualTo(201);

        // 源文修订后重新提交译文 V4：草稿版本 7（修订 6、提交 7）
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文V2\"}");
        ApiResult v4 = submitTranslation(docId, "s1", "en", "alice", "译文V4", 2, newRequestId());
        assertThat(v4.body().get("translationVersion").asInt()).isEqualTo(4);

        // 对已被修订的 V3 签署：422
        ApiResult signOld = sign(docId, "s1", "en", "bing", 3, newRequestId());
        assertThat(signOld.status()).isEqualTo(422);

        // 原两份 V3 签署不能用于 V4 发布：422，当前已发布版本仍为 1
        ApiResult publishV4WithoutSign = publish(docId, 7, 1, newRequestId());
        assertThat(publishV4WithoutSign.status()).isEqualTo(422);
        assertThat(publishV4WithoutSign.body().get("message").asText()).contains("缺少签署");
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 查询清楚区分：V3 签署 current=false；尚无 V4 签署
        ApiResult status = getJson("/api/documents/" + docId + "/legal-status");
        var draft = status.body().get("draftSignatures").get(0);
        assertThat(draft.get("currentTranslationVersion").asInt()).isEqualTo(4);
        assertThat(draft.get("signatures")).hasSize(2);
        assertThat(draft.get("signatures").get(0).get("translationVersion").asInt()).isEqualTo(3);
        assertThat(draft.get("signatures").get(0).get("current").asBoolean()).isFalse();

        // 两名签署人补签 V4 后发布成功
        sign(docId, "s1", "en", "jia", 4, newRequestId());
        sign(docId, "s1", "en", "yi", 4, newRequestId());
        ApiResult second = publish(docId, 7, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);

        // 新快照为 V4 与新签署；历史快照 1 保持 V3 与甲、乙，不被回写
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var t2 = release2.body().get("segments").get(0).get("translations").get(0);
        assertThat(t2.get("content").asText()).isEqualTo("译文V4");
        assertThat(t2.get("translationVersion").asInt()).isEqualTo(4);
        assertThat(t2.get("signers")).hasSize(2);
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        var t1 = release1.body().get("segments").get(0).get("translations").get(0);
        assertThat(t1.get("content").asText()).isEqualTo("译文V3");
        assertThat(t1.get("translationVersion").asInt()).isEqualTo(3);
        assertThat(t1.get("signers").toString()).contains("jia", "yi");
    }

    @Test
    @DisplayName("混合效力：INFORMATIONAL 语种沿用批准、BINDING 语种要两签署，缺任一则 422；发布后查询区分草稿与当前效力")
    void mixedLegalStatuses() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "こんにちは", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        updateLegalStatus(docId, "ja", "BINDING", newRequestId());
        // 建文档 1 + 两次提交 2 + 切状态 1 = 4
        ApiResult missingJaSign = publish(docId, 4, 0, newRequestId());
        assertThat(missingJaSign.status()).isEqualTo(422);
        assertThat(missingJaSign.body().get("message").asText()).contains("s1/ja");

        sign(docId, "s1", "ja", "jia", 1, newRequestId());
        ApiResult oneJaSign = publish(docId, 4, 0, newRequestId());
        assertThat(oneJaSign.status()).isEqualTo(422);
        sign(docId, "s1", "ja", "yi", 1, newRequestId());

        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 切回 INFORMATIONAL：草稿状态变化（草稿版本 5），当前已发布版本仍固化 BINDING
        updateLegalStatus(docId, "ja", "INFORMATIONAL", newRequestId());
        ApiResult status = getJson("/api/documents/" + docId + "/legal-status");
        assertThat(status.body().get("draftVersion").asInt()).isEqualTo(5);
        var ja = status.body().get("languages").get(1);
        assertThat(ja.get("language").asText()).isEqualTo("ja");
        assertThat(ja.get("draftLegalStatus").asText()).isEqualTo("INFORMATIONAL");
        assertThat(ja.get("publishedLegalStatus").asText()).isEqualTo("BINDING");
        var en = status.body().get("languages").get(0);
        assertThat(en.get("draftLegalStatus").asText()).isEqualTo("INFORMATIONAL");
        assertThat(en.get("publishedLegalStatus").asText()).isEqualTo("INFORMATIONAL");

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var translations = release.body().get("segments").get(0).get("translations");
        assertThat(translations.get(0).get("legalStatus").asText()).isEqualTo("INFORMATIONAL");
        assertThat(translations.get(0).get("reviewer").asText()).isEqualTo("bob");
        assertThat(translations.get(1).get("legalStatus").asText()).isEqualTo("BINDING");
        assertThat(translations.get(1).get("signers").toString()).contains("jia", "yi");
    }

    @Test
    @DisplayName("签署与状态切换幂等：同键同参重放且不重复落库；同键异参 409；签署失败不占键")
    void signIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());

        String signKey = newRequestId();
        ApiResult first = sign(docId, "s1", "en", "jia", 1, signKey);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = sign(docId, "s1", "en", "jia", 1, signKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("signedAt").asText()).isEqualTo(first.body().get("signedAt").asText());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_signature WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 同键异参（换译文版本）：409
        ApiResult conflict = sign(docId, "s1", "en", "jia", 99, signKey);
        assertThat(conflict.status()).isEqualTo(409);

        // 同键异参（换语种路径）：409，且在业务校验之前返回，不会产生 ja 译文不存在的 404
        ApiResult conflictLanguage = sign(docId, "s1", "ja", "jia", 1, signKey);
        assertThat(conflictLanguage.status()).isEqualTo(409);

        // 失败不占键：先签不存在的译文版本 422，再用同键签当前版本成功
        String failKey = newRequestId();
        ApiResult failed = sign(docId, "s1", "en", "yi", 99, failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = sign(docId, "s1", "en", "yi", 1, failKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_signature WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);

        // 状态切换同键异参：409
        String statusKey = newRequestId();
        assertThat(updateLegalStatus(docId, "en", "BINDING", statusKey).status()).isEqualTo(200);
        assertThat(updateLegalStatus(docId, "en", "INFORMATIONAL", statusKey).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("发布 requestId 重放首次发布快照；改期望版本 409；重放不改变当前已发布版本")
    void publishRequestReplayFreezesFirstSnapshot() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());
        sign(docId, "s1", "en", "jia", 1, newRequestId());
        sign(docId, "s1", "en", "yi", 1, newRequestId());
        updateLegalStatus(docId, "en", "BINDING", newRequestId());
        // 建文档 1 + 提交 1 + 切状态 1 = 3（签署不递增草稿版本）
        String publishKey = newRequestId();
        ApiResult first = publish(docId, 3, 0, publishKey);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("publishedVersion").asInt()).isEqualTo(1);

        // 推进草稿：源文修订（草稿 4）+ 重新提交 V2（草稿 5）并补齐两签署，已发布版本仍为 1
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文2\"}");
        submitTranslation(docId, "s1", "en", "alice", "v2", 2, newRequestId());
        sign(docId, "s1", "en", "jia", 2, newRequestId());
        sign(docId, "s1", "en", "yi", 2, newRequestId());

        // 同键同参重放：返回首次发布快照，不产生第 2 个发布版本
        ApiResult replay = publish(docId, 3, 0, publishKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);

        // 同键异参（改期望版本）：409
        ApiResult changed = publish(docId, 5, 1, publishKey);
        assertThat(changed.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("签署边界：译文不存在 404；缺 X-Actor-Id 400；文档不存在 404")
    void signEdgeCases() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1", 1, newRequestId());

        ApiResult noTranslation = sign(docId, "s1", "ja", "jia", 1, newRequestId());
        assertThat(noTranslation.status()).isEqualTo(404);
        ApiResult noActor = postJson("/api/documents/" + docId + "/segments/s1/translations/en/sign",
                "{\"requestId\":\"" + newRequestId() + "\",\"translationVersion\":1}");
        assertThat(noActor.status()).isEqualTo(400);
        ApiResult noDoc = getJson("/api/documents/999999/legal-status");
        assertThat(noDoc.status()).isEqualTo(404);
    }
}
