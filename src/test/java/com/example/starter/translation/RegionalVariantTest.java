package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 区域译文变体功能测试：区域覆盖优先级、发布快照固化、回退语义、
 * 变体生命周期校验与幂等，均通过真实 H2 库验证。
 */
class RegionalVariantTest extends AbstractIntegrationTest {

    /** 建文档（单语言 en、单段落 s1）并提交批准基础译文，返回 documentId；完成后草稿版本 2。 */
    private long approvedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("区域覆盖优先级：具体区域命中 EXACT，缺失回退 DEFAULT，未指定区域解析 DEFAULT")
    void regionalOverridePriority() throws Exception {
        long docId = approvedDoc();
        // 草稿版本 2：创建 DEFAULT 变体并批准
        ApiResult createDefault = createVariant(docId, "s1", "en", "alice", "DEFAULT",
                "hello-global", 2, newRequestId());
        assertThat(createDefault.status()).isEqualTo(201);
        assertThat(createDefault.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(createDefault.body().get("draftVersion").asInt()).isEqualTo(3);
        assertThat(approveVariant(docId, "s1", "en", "DEFAULT", "carol", newRequestId()).status())
                .isEqualTo(200);
        // 草稿版本 3：创建 CN 变体并批准
        assertThat(createVariant(docId, "s1", "en", "alice", "cn", "hello-cn", 3, newRequestId())
                .status()).isEqualTo(201);
        assertThat(approveVariant(docId, "s1", "en", "CN", "carol", newRequestId()).status())
                .isEqualTo(200);

        // 给定 CN：优先 CN 变体
        ApiResult cn = getJson("/api/documents/" + docId + "/resolution?region=CN");
        assertThat(cn.status()).isEqualTo(200);
        assertThat(cn.body().get("region").asText()).isEqualTo("CN");
        assertThat(cn.body().get("missing").size()).isZero();
        assertThat(cn.body().get("entries").get(0).get("content").asText()).isEqualTo("hello-cn");
        assertThat(cn.body().get("entries").get(0).get("regionCode").asText()).isEqualTo("CN");
        assertThat(cn.body().get("entries").get(0).get("fallbackSource").asText()).isEqualTo("EXACT");

        // 给定 US：无 US 变体，回退 DEFAULT
        ApiResult us = getJson("/api/documents/" + docId + "/resolution?region=US");
        assertThat(us.status()).isEqualTo(200);
        assertThat(us.body().get("entries").get(0).get("content").asText()).isEqualTo("hello-global");
        assertThat(us.body().get("entries").get(0).get("regionCode").asText()).isEqualTo("DEFAULT");
        assertThat(us.body().get("entries").get(0).get("fallbackSource").asText())
                .isEqualTo("FALLBACK_DEFAULT");

        // 未指定区域：解析 DEFAULT 变体
        ApiResult none = getJson("/api/documents/" + docId + "/resolution");
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.body().get("region").asText()).isEqualTo("DEFAULT");
        assertThat(none.body().get("entries").get(0).get("content").asText()).isEqualTo("hello-global");
        assertThat(none.body().get("entries").get(0).get("fallbackSource").asText()).isEqualTo("EXACT");
    }

    @Test
    @DisplayName("发布快照固化区域选择：撤销变体不改写既有快照，后续发布自动回退 DEFAULT，回退历史可查")
    void publishSnapshotFreezesRegionalPick() throws Exception {
        long docId = approvedDoc();
        createVariant(docId, "s1", "en", "alice", "DEFAULT", "hello-global", 2, newRequestId());
        approveVariant(docId, "s1", "en", "DEFAULT", "carol", newRequestId());
        createVariant(docId, "s1", "en", "alice", "CN", "hello-cn", 3, newRequestId());
        approveVariant(docId, "s1", "en", "CN", "carol", newRequestId());
        // 当前草稿版本 4、发布版本 0

        ApiResult publish1 = publishWithRegion(docId, 4, 0, "CN", newRequestId());
        assertThat(publish1.status()).isEqualTo(201);
        assertThat(publish1.body().get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.status()).isEqualTo(200);
        assertThat(release1.body().get("requestedRegion").asText()).isEqualTo("CN");
        var t1 = release1.body().get("segments").get(0).get("translations").get(0);
        assertThat(t1.get("content").asText()).isEqualTo("hello-cn");
        assertThat(t1.get("regionCode").asText()).isEqualTo("CN");
        assertThat(t1.get("fallbackSource").asText()).isEqualTo("EXACT");
        assertThat(t1.get("translationVersion").asInt()).isEqualTo(1);

        // 撤销 CN 变体：既有快照不改写
        ApiResult revoke = revokeVariant(docId, "s1", "en", "CN", 4, newRequestId());
        assertThat(revoke.status()).isEqualTo(200);
        assertThat(revoke.body().get("status").asText()).isEqualTo("REVOKED");
        ApiResult release1Again = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1Again.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("hello-cn");

        // 后续发布自动回退 DEFAULT
        ApiResult publish2 = publishWithRegion(docId, 5, 1, "CN", newRequestId());
        assertThat(publish2.status()).isEqualTo(201);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        var t2 = release2.body().get("segments").get(0).get("translations").get(0);
        assertThat(t2.get("content").asText()).isEqualTo("hello-global");
        assertThat(t2.get("regionCode").asText()).isEqualTo("DEFAULT");
        assertThat(t2.get("fallbackSource").asText()).isEqualTo("FALLBACK_DEFAULT");

        // 回退历史：两次发布的解析记录按版本、段落、语言稳定排序
        ApiResult history = getJson("/api/documents/" + docId + "/fallback-history");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("entries").size()).isEqualTo(2);
        var e1 = history.body().get("entries").get(0);
        assertThat(e1.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(e1.get("requestedRegion").asText()).isEqualTo("CN");
        assertThat(e1.get("resolvedRegion").asText()).isEqualTo("CN");
        assertThat(e1.get("fallbackSource").asText()).isEqualTo("EXACT");
        var e2 = history.body().get("entries").get(1);
        assertThat(e2.get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(e2.get("resolvedRegion").asText()).isEqualTo("DEFAULT");
        assertThat(e2.get("fallbackSource").asText()).isEqualTo("FALLBACK_DEFAULT");
    }

    @Test
    @DisplayName("DEFAULT 也不可用：发布整次 422，缺失段落稳定排序，既有发布版本与快照不变")
    void publishFailsWhenDefaultMissing() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "two", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        // 草稿版本 3；仅 s1 登记 DEFAULT 变体
        createVariant(docId, "s1", "en", "alice", "DEFAULT", "one-global", 3, newRequestId());
        approveVariant(docId, "s1", "en", "DEFAULT", "carol", newRequestId());

        ApiResult publish = publishWithRegion(docId, 4, 0, "CN", newRequestId());
        assertThat(publish.status()).isEqualTo(422);
        assertThat(publish.body().get("error").asText()).isEqualTo("REGIONAL_MISSING");
        // s2 缺少 CN 与 DEFAULT 变体，稳定排序返回
        assertThat(publish.body().get("missing").size()).isEqualTo(1);
        assertThat(publish.body().get("missing").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(publish.body().get("missing").get(0).get("language").asText()).isEqualTo("en");

        // 既有发布版本不变、无快照与解析记录产生
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_resolution WHERE document_id = ?", Integer.class, docId))
                .isZero();

        // 撤销 s1 的 DEFAULT 变体后：两个段落均缺失，按段落稳定排序
        revokeVariant(docId, "s1", "en", "DEFAULT", 4, newRequestId());
        ApiResult publishAllMissing = publishWithRegion(docId, 5, 0, "CN", newRequestId());
        assertThat(publishAllMissing.status()).isEqualTo(422);
        assertThat(publishAllMissing.body().get("missing").size()).isEqualTo(2);
        assertThat(publishAllMissing.body().get("missing").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(publishAllMissing.body().get("missing").get(1).get("segmentId").asText()).isEqualTo("s2");
        assertThat(jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isZero();
    }

    @Test
    @DisplayName("变体创建校验：期望版本不符 409；译文无有效批准 422；同译文版本重复创建 409；术语违规 422")
    void variantCreateValidation() throws Exception {
        long docId = approvedDoc();
        // 期望版本不符
        assertThat(createVariant(docId, "s1", "en", "alice", "CN", "x", 99, newRequestId()).status())
                .isEqualTo(409);
        // 语言不在目标语言
        assertThat(createVariant(docId, "s1", "fr", "alice", "CN", "x", 2, newRequestId()).status())
                .isEqualTo(422);
        // 区域代码不合法
        assertThat(createVariant(docId, "s1", "en", "alice", "非法区域!", "x", 2, newRequestId()).status())
                .isEqualTo(422);

        // 未批准译文不能登记变体
        long doc2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(doc2, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(createVariant(doc2, "s1", "en", "alice", "CN", "x", 2, newRequestId()).status())
                .isEqualTo(422);

        // 正常创建后同译文版本重复创建 409；PENDING 不参与解析
        assertThat(createVariant(docId, "s1", "en", "alice", "CN", "hello-cn", 2, newRequestId()).status())
                .isEqualTo(201);
        assertThat(createVariant(docId, "s1", "en", "alice", "CN", "hello-cn-2", 3, newRequestId()).status())
                .isEqualTo(409);
        ApiResult resolution = getJson("/api/documents/" + docId + "/resolution?region=CN");
        assertThat(resolution.body().get("missing").size()).isEqualTo(1);

        // 术语违规：变体内容不含必译文本
        long doc3 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(doc3, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        submitTranslation(doc3, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(doc3, "s1", "en", "bob", 1, newRequestId());
        ApiResult violation = createVariant(doc3, "s1", "en", "alice", "CN", "ML 译文", 3, newRequestId());
        assertThat(violation.status()).isEqualTo(422);
        assertThat(violation.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violation.body().get("violations").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("变体批准与撤销：作者不能自审 422；非待批准状态 422；撤销期望版本不符 409；重复撤销 422；不存在 404")
    void variantApproveAndRevokeValidation() throws Exception {
        long docId = approvedDoc();
        createVariant(docId, "s1", "en", "alice", "CN", "hello-cn", 2, newRequestId());
        // 作者自审
        assertThat(approveVariant(docId, "s1", "en", "CN", "alice", newRequestId()).status())
                .isEqualTo(422);
        // 批准不存在
        assertThat(approveVariant(docId, "s1", "en", "US", "carol", newRequestId()).status())
                .isEqualTo(404);
        // 正常批准；重复批准 422
        assertThat(approveVariant(docId, "s1", "en", "CN", "carol", newRequestId()).status())
                .isEqualTo(200);
        assertThat(approveVariant(docId, "s1", "en", "CN", "carol", newRequestId()).status())
                .isEqualTo(422);
        // 撤销期望版本不符 409
        assertThat(revokeVariant(docId, "s1", "en", "CN", 99, newRequestId()).status()).isEqualTo(409);
        // 正常撤销；重复撤销 422；撤销不存在 404
        assertThat(revokeVariant(docId, "s1", "en", "CN", 3, newRequestId()).status()).isEqualTo(200);
        assertThat(revokeVariant(docId, "s1", "en", "CN", 4, newRequestId()).status()).isEqualTo(422);
        assertThat(revokeVariant(docId, "s1", "en", "US", 4, newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("撤销后重建：变体版本递增，新变体绑定当前译文版本并参与解析")
    void recreateAfterRevoke() throws Exception {
        long docId = approvedDoc();
        createVariant(docId, "s1", "en", "alice", "CN", "hello-cn-v1", 2, newRequestId());
        approveVariant(docId, "s1", "en", "CN", "carol", newRequestId());
        revokeVariant(docId, "s1", "en", "CN", 3, newRequestId());

        ApiResult recreate = createVariant(docId, "s1", "en", "alice", "CN", "hello-cn-v2", 4,
                newRequestId());
        assertThat(recreate.status()).isEqualTo(201);
        assertThat(recreate.body().get("variantVersion").asInt()).isEqualTo(2);
        approveVariant(docId, "s1", "en", "CN", "carol", newRequestId());

        ApiResult resolution = getJson("/api/documents/" + docId + "/resolution?region=CN");
        assertThat(resolution.body().get("entries").get(0).get("content").asText())
                .isEqualTo("hello-cn-v2");
        assertThat(resolution.body().get("entries").get(0).get("fallbackSource").asText())
                .isEqualTo("EXACT");
    }

    @Test
    @DisplayName("译文重提与术语版本退役使旧变体失效：解析自动回退，不混合新旧区域选择")
    void staleVariantFallsBack() throws Exception {
        long docId = approvedDoc();
        createVariant(docId, "s1", "en", "alice", "CN", "hello-cn", 2, newRequestId());
        approveVariant(docId, "s1", "en", "CN", "carol", newRequestId());

        // 术语版本退役：变体绑定术语版本 0，当前术语版本升为 1，变体失效
        updateTerms(docId, 0, "[]", newRequestId());
        ApiResult afterTermUpdate = getJson("/api/documents/" + docId + "/resolution?region=CN");
        assertThat(afterTermUpdate.body().get("missing").size()).isEqualTo(1);

        // 译文重提并批准：变体绑定译文版本 1，当前译文版本 2，变体仍失效
        submitTranslation(docId, "s1", "en", "alice", "hello-2", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult afterResubmit = getJson("/api/documents/" + docId + "/resolution?region=CN");
        assertThat(afterResubmit.body().get("missing").size()).isEqualTo(1);
        // 未指定区域时回退基础译文（当前版本内容）
        ApiResult base = getJson("/api/documents/" + docId + "/resolution");
        assertThat(base.body().get("entries").get(0).get("content").asText()).isEqualTo("hello-2");
        assertThat(base.body().get("entries").get(0).get("fallbackSource").asText()).isEqualTo("BASE");
    }

    @Test
    @DisplayName("具体区域变体不跨段落不跨语言：仅对登记的段落与语言生效")
    void variantDoesNotLeakAcrossSegmentsOrLanguages() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"fr\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "one-en", 1, newRequestId());
        submitTranslation(docId, "s1", "fr", "alice", "one-fr", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "two-en", 1, newRequestId());
        submitTranslation(docId, "s2", "fr", "alice", "two-fr", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "fr", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "fr", "bob", 1, newRequestId());
        // 草稿版本 5；仅 s1/en 登记 CN 变体
        createVariant(docId, "s1", "en", "alice", "CN", "one-en-cn", 5, newRequestId());
        approveVariant(docId, "s1", "en", "CN", "carol", newRequestId());

        ApiResult resolution = getJson("/api/documents/" + docId + "/resolution?region=CN");
        assertThat(resolution.status()).isEqualTo(200);
        var entries = resolution.body().get("entries");
        assertThat(entries.size()).isEqualTo(4);
        // s1/en 命中 CN 变体
        assertThat(entries.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(entries.get(0).get("language").asText()).isEqualTo("en");
        assertThat(entries.get(0).get("content").asText()).isEqualTo("one-en-cn");
        // s1/fr、s2/en、s2/fr 均缺失（变体不跨语言、不跨段落）
        assertThat(entries.get(1).get("missing").asBoolean()).isTrue();
        assertThat(entries.get(2).get("missing").asBoolean()).isTrue();
        assertThat(entries.get(3).get("missing").asBoolean()).isTrue();
        assertThat(resolution.body().get("missing").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("变体幂等：同键同参重放首次完整响应且不重复变更；同键异参 409；失败不占键")
    void variantIdempotency() throws Exception {
        long docId = approvedDoc();
        String requestId = newRequestId();
        ApiResult first = createVariant(docId, "s1", "en", "alice", "CN", "hello-cn", 2, requestId);
        assertThat(first.status()).isEqualTo(201);
        // 同键同参：重放首次响应，草稿版本不变、变体仍一条
        ApiResult replay = createVariant(docId, "s1", "en", "alice", "CN", "hello-cn", 2, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM regional_variant WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        // 同键异参 409
        assertThat(createVariant(docId, "s1", "en", "alice", "CN", "other", 2, requestId).status())
                .isEqualTo(409);
        // 失败不占键：先以错误期望版本失败，再同键正确参数成功
        String failThenSucceed = newRequestId();
        assertThat(createVariant(docId, "s1", "en", "alice", "US", "hello-us", 99, failThenSucceed)
                .status()).isEqualTo(409);
        ApiResult succeed = createVariant(docId, "s1", "en", "alice", "US", "hello-us", 3,
                failThenSucceed);
        assertThat(succeed.status()).isEqualTo(201);
    }
}
