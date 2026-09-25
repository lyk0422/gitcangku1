package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域译文变体：登记/批准/撤销、覆盖优先级、发布快照固化、回退语义与查询的主流程与失败分支。
 */
class RegionVariantApiTest extends AbstractIntegrationTest {

    /** 建双段落 en 文档，提交并批准基线译文，返回当前草稿版本（1 文档 + 2 提交 = 3）。 */
    private long prepareTwoSegmentDoc() throws Exception {        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "default-one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "default-two", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("登记变体：201 PENDING 且草稿版本加一；未批准基线/DEFAULT 区域/版本不符分别 422/422/409")
    void createVariantFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());

        // 基线未批准：422
        ApiResult notApproved = createVariant(docId, "s1", "en", "US", 1, newRequestId());
        assertThat(notApproved.status()).isEqualTo(422);

        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // DEFAULT 为保留区域码：422
        ApiResult defaultRegion = createVariant(docId, "s1", "en", "DEFAULT", 1, newRequestId());
        assertThat(defaultRegion.status()).isEqualTo(422);

        // expectedVersion 与当前基线译文版本不符：409
        ApiResult wrongVersion = createVariant(docId, "s1", "en", "US", 9, newRequestId());
        assertThat(wrongVersion.status()).isEqualTo(409);

        ApiResult ok = createVariant(docId, "s1", "en", "us", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("region").asText()).isEqualTo("US");
        assertThat(ok.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(ok.body().get("translationVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(3);

        // 同段落/语言/区域/译文版本重复登记：409
        ApiResult duplicate = createVariant(docId, "s1", "en", "US", 1, newRequestId());
        assertThat(duplicate.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("批准变体：作者不能自审 422；重复批准 409；批准后旧版本 SUPERSEDED")
    void approveVariant() throws Exception {
        long docId = prepareTwoSegmentDoc();
        createVariant(docId, "s1", "en", "US", 1, newRequestId());

        ApiResult byAuthor = approveVariant(docId, "s1", "en", "US", "alice", 1, newRequestId());
        assertThat(byAuthor.status()).isEqualTo(422);

        ApiResult ok = approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(ok.body().get("draftVersion").asInt()).isEqualTo(4);

        // 再次批准同一版本：409（已 ACTIVE）
        ApiResult again = approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());
        assertThat(again.status()).isEqualTo(409);

        // expectedVersion 不存在：404
        ApiResult missing = approveVariant(docId, "s1", "en", "US", "carol", 7, newRequestId());
        assertThat(missing.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("覆盖解析：无变体回退 DEFAULT；区域有效变体优先；撤销后回退 DEFAULT")
    void resolutionPriorityAndRevokeFallback() throws Exception {
        long docId = prepareTwoSegmentDoc();

        // 初始：全部回退 DEFAULT
        ApiResult before = getJson("/api/documents/" + docId + "/regions/US/resolution");
        assertThat(before.status()).isEqualTo(200);
        assertThat(before.body().get("items")).hasSize(2);
        assertThat(before.body().get("items").get(0).get("selectedRegion").asText()).isEqualTo("DEFAULT");
        assertThat(before.body().get("items").get(0).get("fallbackSource").asText()).isEqualTo("DEFAULT");
        assertThat(before.body().get("items").get(0).get("content").asText()).isEqualTo("default-one");

        // 仅 s1/US 变体；s2 仍回退 DEFAULT
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());
        ApiResult after = getJson("/api/documents/" + docId + "/regions/US/resolution");
        var s1 = after.body().get("items").get(0);
        var s2 = after.body().get("items").get(1);
        assertThat(s1.get("selectedRegion").asText()).isEqualTo("US");
        assertThat(s1.get("fallbackSource").asText()).isEqualTo("REGION");
        assertThat(s1.get("content").asText()).isEqualTo("default-one");
        assertThat(s2.get("selectedRegion").asText()).isEqualTo("DEFAULT");
        assertThat(s2.get("fallbackSource").asText()).isEqualTo("DEFAULT");

        // 撤销 s1/US 后回退 DEFAULT
        ApiResult revoked = revokeVariant(docId, "s1", "en", "US", 1, newRequestId());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().get("status").asText()).isEqualTo("REVOKED");
        ApiResult fallbackAgain = getJson("/api/documents/" + docId + "/regions/US/resolution");
        assertThat(fallbackAgain.body().get("items").get(0).get("selectedRegion").asText())
                .isEqualTo("DEFAULT");

        // 撤销非 ACTIVE 变体：409；不存在版本：404
        assertThat(revokeVariant(docId, "s1", "en", "US", 1, newRequestId()).status()).isEqualTo(409);
        assertThat(revokeVariant(docId, "s1", "en", "US", 5, newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("区域发布：快照固化区域码、译文版本与回退来源；回退历史可查；快照不随后续撤销改写")
    void regionalPublishSnapshotAndHistory() throws Exception {
        long docId = prepareTwoSegmentDoc();
        // 草稿版本：建文档 1 + 2 提交 = 3；s1 变体登记后为 4
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());

        ApiResult published = publish(docId, 4, 0, "US", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(published.body().get("region").asText()).isEqualTo("US");

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("region").asText()).isEqualTo("US");
        var translations = release.body().get("segments");
        assertThat(translations.get(0).get("translations").get(0).get("region").asText()).isEqualTo("US");
        assertThat(translations.get(0).get("translations").get(0).get("fallbackSource").asText())
                .isEqualTo("REGION");
        assertThat(translations.get(0).get("translations").get(0).get("translationVersion").asInt())
                .isEqualTo(1);
        assertThat(translations.get(1).get("translations").get(0).get("region").asText())
                .isEqualTo("DEFAULT");
        assertThat(translations.get(1).get("translations").get(0).get("fallbackSource").asText())
                .isEqualTo("DEFAULT");

        // 回退历史：仅 s2 回退
        ApiResult history = getJson("/api/documents/" + docId + "/regions/US/fallbacks");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("items")).hasSize(1);
        assertThat(history.body().get("items").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(history.body().get("items").get(0).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(history.body().get("items").get(0).get("translationVersion").asInt()).isEqualTo(1);

        // 撤销变体后再发版（v2 全量回退）；v1 快照与 v1 回退历史不变
        revokeVariant(docId, "s1", "en", "US", 1, newRequestId());
        ApiResult second = publish(docId, 4, 1, "US", newRequestId());
        assertThat(second.status()).isEqualTo(201);
        ApiResult release1Again = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1Again.body().get("segments").get(0).get("translations").get(0)
                .get("region").asText()).isEqualTo("US");
        ApiResult historyAfter = getJson("/api/documents/" + docId + "/regions/US/fallbacks");
        assertThat(historyAfter.body().get("items")).hasSize(3);
    }

    @Test
    @DisplayName("缺失段落 422：具体区域与 DEFAULT 均无有效译文时整次失败、稳定排序返回缺失段落、既有发布版本不变")
    void publishMissingSegments422() throws Exception {
        // s1 基线完备；s2 只有译文未批准（DEFAULT 不可用），也无 US 变体
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "default-one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "default-two", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        ApiResult failed = publish(docId, 3, 0, "US", newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("error").asText()).isEqualTo("MISSING_SEGMENTS");
        assertThat(textList(failed.body().get("missingSegments"))).containsExactly("s2");

        // 既有发布版本不变、无快照与回退记录
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM fallback_record WHERE document_id = ?", Integer.class, docId)).isZero();

        // 多段落缺失按 segmentId 稳定排序
        ApiResult failedDefault = publish(docId, 3, 0, newRequestId());
        assertThat(failedDefault.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("基线译文修订后旧变体失效：区域发布在 DEFAULT 也失效时 422，重新提交基线后回退新 DEFAULT")
    void variantInvalidatedByBaselineChange() throws Exception {
        long docId = prepareTwoSegmentDoc();
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());

        // s1 源文修订：基线与 US 变体的源文版本均落后
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文一v2\"}");
        ApiResult resolution = getJson("/api/documents/" + docId + "/regions/US/resolution");
        var s1 = resolution.body().get("items").get(0);
        assertThat(s1.get("fallbackSource").asText()).isEqualTo("NONE");
        assertThat(s1.get("selectedRegion").isNull());

        // 区域发布：s1 区域与 DEFAULT 均失效 -> 422 缺失 s1
        ApiResult failed = publish(docId, 5, 0, "US", newRequestId());
        assertThat(failed.status()).isEqualTo(422);
        assertThat(textList(failed.body().get("missingSegments"))).containsExactly("s1");

        // 重新提交并批准基线 v2 后，US 发布回退新 DEFAULT（旧变体仍不可用）
        submitTranslation(docId, "s1", "en", "alice", "default-one-v2", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 6, 0, "US", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var chosen = release.body().get("segments").get(0).get("translations").get(0);
        assertThat(chosen.get("region").asText()).isEqualTo("DEFAULT");
        assertThat(chosen.get("content").asText()).isEqualTo("default-one-v2");
        assertThat(chosen.get("translationVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("新版本变体登记批准后取代旧版本：解析与发布采用新 ACTIVE，旧版本 SUPERSEDED 不参与")
    void newVariantVersionSupersedesOld() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "v1-text", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());

        // 重新提交基线译文 v2 并批准
        submitTranslation(docId, "s1", "en", "alice", "v2-text", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        createVariant(docId, "s1", "en", "US", 2, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 2, newRequestId());

        Integer activeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM regional_variant WHERE document_id = ? AND status = 'ACTIVE'",
                Integer.class, docId);
        Integer supersededCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM regional_variant WHERE document_id = ? AND status = 'SUPERSEDED'",
                Integer.class, docId);
        assertThat(activeCount).isEqualTo(1);
        assertThat(supersededCount).isEqualTo(1);

        ApiResult resolution = getJson("/api/documents/" + docId + "/regions/US/resolution");
        assertThat(resolution.body().get("items").get(0).get("translationVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("术语版本退役：旧术语版本的变体失效；基线更新到新术语版本后区域发布回退新 DEFAULT")
    void variantFallsBackAfterTermRetirement() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"AI 平台\"}]");
        submitTranslation(docId, "s1", "en", "alice", "ai-platform", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());

        // 新增术语版本 1：基线译文与 US 变体绑定的术语版本 0 均退役
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"AI\",\"language\":\"en\",\"requiredTranslation\":\"artificial intelligence\"}]",
                newRequestId());
        ApiResult staleResolution = getJson("/api/documents/" + docId + "/regions/US/resolution");
        assertThat(staleResolution.body().get("items").get(0).get("fallbackSource").asText()).isEqualTo("NONE");

        // 区域发布在基线也过期时 422
        assertThat(publish(docId, 4, 0, "US", newRequestId()).status()).isEqualTo(422);

        // 基线按新术语版本重新提交并批准（草稿版本 5）；US 变体仍绑定旧术语版本而失效
        submitTranslation(docId, "s1", "en", "alice", "artificial intelligence platform", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult resolution = getJson("/api/documents/" + docId + "/regions/US/resolution");
        var item = resolution.body().get("items").get(0);
        assertThat(item.get("selectedRegion").asText()).isEqualTo("DEFAULT");
        assertThat(item.get("fallbackSource").asText()).isEqualTo("DEFAULT");
        assertThat(item.get("translationVersion").asInt()).isEqualTo(2);

        ApiResult published = publish(docId, 5, 0, "US", newRequestId());
        assertThat(published.status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var chosen = release.body().get("segments").get(0).get("translations").get(0);
        assertThat(chosen.get("region").asText()).isEqualTo("DEFAULT");
        assertThat(chosen.get("termVersion").asInt()).isEqualTo(1);
        ApiResult history = getJson("/api/documents/" + docId + "/regions/US/fallbacks");
        assertThat(history.body().get("items")).hasSize(1);
    }

    @Test
    @DisplayName("区域隔离：en/US 变体不覆盖 ja 语言与其他段落；快照中各语言/段落独立解析")
    void variantDoesNotCrossLanguageOrSegment() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        for (String seg : new String[]{"s1", "s2"}) {
            submitTranslation(docId, seg, "en", "alice", seg + "-en-default", 1, newRequestId());
            submitTranslation(docId, seg, "ja", "eri", seg + "-ja-default", 1, newRequestId());
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "bob", 1, newRequestId());
        }
        // 仅 s1/en/US 登记并批准变体
        createVariant(docId, "s1", "en", "US", 1, newRequestId());
        approveVariant(docId, "s1", "en", "US", "carol", 1, newRequestId());
        // 草稿版本：1 + 4 提交 + 1 变体登记 = 6
        ApiResult published = publish(docId, 6, 0, "US", newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var s1Translations = release.body().get("segments").get(0).get("translations");
        var s2Translations = release.body().get("segments").get(1).get("translations");
        // s1/en 用 US 变体；s1/ja、s2/en、s2/ja 全部回退 DEFAULT
        assertThat(s1Translations.get(0).get("language").asText()).isEqualTo("en");
        assertThat(s1Translations.get(0).get("region").asText()).isEqualTo("US");
        assertThat(s1Translations.get(1).get("language").asText()).isEqualTo("ja");
        assertThat(s1Translations.get(1).get("region").asText()).isEqualTo("DEFAULT");
        assertThat(s2Translations.get(0).get("region").asText()).isEqualTo("DEFAULT");
        assertThat(s2Translations.get(1).get("region").asText()).isEqualTo("DEFAULT");

        ApiResult history = getJson("/api/documents/" + docId + "/regions/US/fallbacks");
        assertThat(history.body().get("items")).hasSize(3);
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }
}
