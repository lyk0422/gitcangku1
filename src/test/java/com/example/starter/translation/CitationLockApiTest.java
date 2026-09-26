package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 法定引文锚点：登记、解除、译文修订迁移、批量事务、发布快照固化、历史/诊断与幂等测试。
 * 全部基于真实 H2（MODE=MySQL），区间按 Java 字符偏移左闭右开。
 */
class CitationLockApiTest extends AbstractIntegrationTest {

    /** 建文档+段落，提交并批准译文，返回译文内容长度。 */
    private long prepareApprovedTranslation(String content) throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"}]");
        ApiResult submitted = submitTranslation(docId, "s1", "en", "alice", content, 1, newRequestId());
        assertThat(submitted.status()).isEqualTo(200);
        ApiResult approved = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(approved.status()).isEqualTo(200);
        return docId;
    }

    private JsonNode firstAnchor(ApiResult result) {
        return result.body().get("anchors").get(0);
    }

    @Test
    @DisplayName("锚点登记：201，区间左闭右开取引用文本，明细可查且时间为 UTC 微秒格式")
    void registerAnchorSuccess() throws Exception {
        // "Article 1 says HELLO world" 中 HELLO 位于 [15,20)
        long docId = prepareApprovedTranslation("Article 1 says HELLO world");
        ApiResult result = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/2026/1\",\"rangeStart\":15,\"rangeEnd\":20,"
                        + "\"lockReason\":\"法定引文第1条\"}]");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("registeredCount").asInt()).isEqualTo(1);
        JsonNode anchor = firstAnchor(result);
        assertThat(anchor.get("anchorText").asText()).isEqualTo("HELLO");
        assertThat(anchor.get("rangeStart").asInt()).isEqualTo(15);
        assertThat(anchor.get("rangeEnd").asInt()).isEqualTo(20);
        assertThat(anchor.get("status").asText()).isEqualTo("LOCKED");
        assertThat(anchor.get("createdBy").asText()).isEqualTo("alice");
        assertThat(anchor.get("releasedBy").isNull()).isTrue();
        assertThat(anchor.get("releasedAt").isNull()).isTrue();
        assertThat(anchor.get("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");

        ApiResult list = getJson("/api/documents/" + docId + "/segments/s1/translations/en/anchors");
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body().get("count").asInt()).isEqualTo(1);
        assertThat(list.body().get("anchors").get(0).get("citationKey").asText()).isEqualTo("L/2026/1");
    }

    @Test
    @DisplayName("锚点登记失败：未批准/越界/交叉/重复标识均 422 且返回可区分原因，不产生锚点半成品")
    void registerAnchorFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"}]");
        submitTranslation(docId, "s1", "en", "alice", "Article 1 says HELLO world", 1, newRequestId());
        // 未批准
        ApiResult notApproved = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":20,\"lockReason\":\"r\"}]");
        assertThat(notApproved.status()).isEqualTo(422);
        assertThat(notApproved.body().get("error").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(notApproved.body().get("issues").get(0).get("code").asText())
                .isEqualTo("NO_APPROVED_TRANSLATION");
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 越界：end=99 > 实际长度 26，错误信息含实际值、要求值与差额
        ApiResult outOfBounds = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":99,\"lockReason\":\"r\"}]");
        assertThat(outOfBounds.status()).isEqualTo(422);
        JsonNode oobIssue = outOfBounds.body().get("issues").get(0);
        assertThat(oobIssue.get("code").asText()).isEqualTo("ANCHOR_OUT_OF_BOUNDS");
        assertThat(oobIssue.get("message").asText()).contains("26", "99", "73");

        // 区间交叉（左闭右开：相邻 [0,5) 与 [5,10) 不交叉，重叠才交叉）
        ApiResult overlap = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":0,\"rangeEnd\":6,\"lockReason\":\"r\"},"
                        + "{\"citationKey\":\"L/2\",\"rangeStart\":5,\"rangeEnd\":10,\"lockReason\":\"r\"}]");
        assertThat(overlap.status()).isEqualTo(422);
        assertThat(issuesContain(overlap, "ANCHOR_RANGE_OVERLAP")).isTrue();

        // 同请求内引用标识重复
        ApiResult dupRequest = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/9\",\"rangeStart\":0,\"rangeEnd\":3,\"lockReason\":\"r\"},"
                        + "{\"citationKey\":\"L/9\",\"rangeStart\":4,\"rangeEnd\":7,\"lockReason\":\"r\"}]");
        assertThat(dupRequest.status()).isEqualTo(422);
        assertThat(issuesContain(dupRequest, "ANCHOR_KEY_DUPLICATE_REQUEST")).isTrue();

        // 合法登记一次后，同一引用标识再次登记 422（即使将来解除也不可复用）
        ApiResult first = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/LOCKED\",\"rangeStart\":0,\"rangeEnd\":7,\"lockReason\":\"r\"}]");
        assertThat(first.status()).isEqualTo(201);
        ApiResult again = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/LOCKED\",\"rangeStart\":8,\"rangeEnd\":12,\"lockReason\":\"r2\"}]");
        assertThat(again.status()).isEqualTo(422);
        assertThat(issuesContain(again, "ANCHOR_KEY_ALREADY_EXISTS")).isTrue();

        // 全部失败不产生半成品
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("中文译文锚点：按字符偏移左闭右开取文本，相邻区间不交叉")
    void unicodeAnchors() throws Exception {
        // "根据法条一与法条二之规定"：法条一 [2,5)，法条二 [6,9)
        long docId = prepareApprovedTranslation("根据法条一与法条二之规定");
        ApiResult result = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"C/1\",\"rangeStart\":2,\"rangeEnd\":5,\"lockReason\":\"法条一\"},"
                        + "{\"citationKey\":\"C/2\",\"rangeStart\":6,\"rangeEnd\":9,\"lockReason\":\"法条二\"}]");
        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().get("anchors").get(0).get("anchorText").asText()).isEqualTo("法条一");
        assertThat(result.body().get("anchors").get(1).get("anchorText").asText()).isEqualTo("法条二");
    }

    @Test
    @DisplayName("译文修订：引用文本原位保留无需映射；区间移动须一一映射，迁移成功版本递增")
    void revisionWithMapping() throws Exception {
        long docId = prepareApprovedTranslation("Article 1 says HELLO world");
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":20,\"lockReason\":\"法定引文\"}]");
        long anchorId = firstAnchor(registered).get("anchorId").asLong();

        // 原位保留：仅在文本前增加不影响原区间的内容不可能；改为相同文本（原位文本一致）无需映射
        ApiResult unchanged = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "Article 1 says HELLO world", 1, "[]");
        assertThat(unchanged.status()).isEqualTo(200);
        assertThat(unchanged.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(unchanged.body().get("migratedAnchorCount").asInt()).isZero();

        // HELLO 移动到 [0,5)：提供映射后成功
        ApiResult moved = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "HELLO says Article 1 world", 1,
                "[{\"anchorId\":" + anchorId + ",\"rangeStart\":0,\"rangeEnd\":5}]");
        assertThat(moved.status()).isEqualTo(200);
        assertThat(moved.body().get("translationVersion").asInt()).isEqualTo(3);
        assertThat(moved.body().get("migratedAnchorCount").asInt()).isEqualTo(1);

        ApiResult list = getJson("/api/documents/" + docId + "/segments/s1/translations/en/anchors");
        JsonNode anchor = list.body().get("anchors").get(0);
        assertThat(anchor.get("rangeStart").asInt()).isZero();
        assertThat(anchor.get("rangeEnd").asInt()).isEqualTo(5);
        assertThat(anchor.get("anchorText").asText()).isEqualTo("HELLO");
        assertThat(anchor.get("translationVersion").asInt()).isEqualTo(3);

        ApiResult history = getJson("/api/documents/" + docId + "/anchors/events?anchorId=" + anchorId);
        assertThat(history.body().get("count").asInt()).isEqualTo(2);
        JsonNode migratedEvent = history.body().get("events").get(1);
        assertThat(migratedEvent.get("eventType").asText()).isEqualTo("MIGRATED");
        assertThat(migratedEvent.get("previousStart").asInt()).isEqualTo(15);
        assertThat(migratedEvent.get("previousEnd").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("译文修订失败：映射缺失/重复/指向不存在锚点/越界/文本不一致均 422，译文与锚点不变")
    void revisionMappingFailures() throws Exception {
        long docId = prepareApprovedTranslation("Article 1 says HELLO world");
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":20,\"lockReason\":\"法定引文\"}]");
        long anchorId = firstAnchor(registered).get("anchorId").asLong();

        // 缺失映射：HELLO 既不在原位也不给映射
        ApiResult missing = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "HELLO says Article 1 world", 1, null);
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("issues").get(0).get("code").asText())
                .isEqualTo("ANCHOR_MAPPING_MISSING");

        // 文本不一致：新区间实际为 "world"
        ApiResult mismatch = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "HELLO says Article 1 world", 1,
                "[{\"anchorId\":" + anchorId + ",\"rangeStart\":21,\"rangeEnd\":26}]");
        assertThat(mismatch.status()).isEqualTo(422);
        JsonNode mismatchIssue = mismatch.body().get("issues").get(0);
        assertThat(mismatchIssue.get("code").asText()).isEqualTo("ANCHOR_TEXT_MISMATCH");
        assertThat(mismatchIssue.get("anchorId").asLong()).isEqualTo(anchorId);
        assertThat(mismatchIssue.get("message").asText()).contains("world", "HELLO", "长度差 0");

        // 重复映射
        ApiResult duplicate = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "HELLO says Article 1 world", 1,
                "[{\"anchorId\":" + anchorId + ",\"rangeStart\":0,\"rangeEnd\":5},"
                        + "{\"anchorId\":" + anchorId + ",\"rangeStart\":0,\"rangeEnd\":5}]");
        assertThat(duplicate.status()).isEqualTo(422);
        assertThat(issuesContain(duplicate, "ANCHOR_MAPPING_DUPLICATE")).isTrue();

        // 指向不存在锚点
        ApiResult notFound = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "HELLO says Article 1 world", 1,
                "[{\"anchorId\":999999,\"rangeStart\":0,\"rangeEnd\":5}]");
        assertThat(notFound.status()).isEqualTo(422);
        assertThat(issuesContain(notFound, "ANCHOR_NOT_FOUND")).isTrue();

        // 越界映射
        ApiResult oob = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "HELLO says Article 1 world", 1,
                "[{\"anchorId\":" + anchorId + ",\"rangeStart\":20,\"rangeEnd\":99}]");
        assertThat(oob.status()).isEqualTo(422);
        assertThat(issuesContain(oob, "ANCHOR_OUT_OF_BOUNDS")).isTrue();

        // 全部失败：译文版本仍为 1，锚点区间不变
        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT range_start FROM citation_anchor WHERE anchor_id = ?", Integer.class, anchorId))
                .isEqualTo(15);
    }

    @Test
    @DisplayName("锚点解除：非法务 403、登记人本人 403、重复解除 422；法务他者解除成功并写入不可变理由")
    void releaseAnchorRules() throws Exception {
        long docId = prepareApprovedTranslation("Article 1 says HELLO world");
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":20,\"lockReason\":\"法定引文\"}]");
        long anchorId = firstAnchor(registered).get("anchorId").asLong();

        // 无法务角色
        ApiResult noRole = releaseAnchor(docId, anchorId, "carol", null, "法院裁定解除");
        assertThat(noRole.status()).isEqualTo(403);
        assertThat(noRole.body().get("error").asText()).isEqualTo("LEGAL_ROLE_REQUIRED");

        // 法务但与登记人相同
        ApiResult self = releaseAnchor(docId, anchorId, "alice", "legal", "法院裁定解除");
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("error").asText()).isEqualTo("SELF_CONFIRMATION_FORBIDDEN");

        // 不同于登记人的法务解除成功
        ApiResult released = releaseAnchor(docId, anchorId, "carol", "legal,reviewer", "法院裁定解除引用");
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body().get("status").asText()).isEqualTo("RELEASED");
        assertThat(released.body().get("releasedBy").asText()).isEqualTo("carol");

        // 重复解除 422
        ApiResult twice = releaseAnchor(docId, anchorId, "dave", "legal", "再次解除");
        assertThat(twice.status()).isEqualTo(422);

        // 解除后译文修订不再受锚点约束，且引用标识不可重新登记
        ApiResult revise = submitTranslationWithMappings(docId, "s1", "en", "alice",
                "totally different content now", 1, null);
        assertThat(revise.status()).isEqualTo(200);
        ApiResult reRegister = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":0,\"rangeEnd\":7,\"lockReason\":\"新原因\"}]");
        assertThat(reRegister.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("发布快照固化锚点集合：之后迁移与解除不改写旧快照，已发布快照锚点仍可追溯")
    void snapshotFreezesAnchors() throws Exception {
        long docId = prepareApprovedTranslation("Article 1 says HELLO world");
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":20,\"lockReason\":\"法定引文\"}]");
        long anchorId = firstAnchor(registered).get("anchorId").asLong();
        // 草稿版本：建文档1、提交2、批准不增、登记3
        ApiResult publish1 = publish(docId, 3, 0, newRequestId());
        assertThat(publish1.status()).isEqualTo(201);

        // 修订迁移锚点（新版本译文需重新批准）
        submitTranslationWithMappings(docId, "s1", "en", "alice", "HELLO says Article 1 world", 1,
                "[{\"anchorId\":" + anchorId + ",\"rangeStart\":0,\"rangeEnd\":5}]");
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        // 解除锚点
        assertThat(releaseAnchor(docId, anchorId, "carol", "legal", "法院裁定解除").status()).isEqualTo(200);
        // 草稿版本：迁移4、解除5
        ApiResult publish2 = publish(docId, 5, 1, newRequestId());
        assertThat(publish2.status()).isEqualTo(201);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        JsonNode anchorInV1 = release1.body().get("segments").get(0).get("translations").get(0)
                .get("citationAnchors").get(0);
        assertThat(anchorInV1.get("anchorId").asLong()).isEqualTo(anchorId);
        assertThat(anchorInV1.get("rangeStart").asInt()).isEqualTo(15);
        assertThat(anchorInV1.get("anchorText").asText()).isEqualTo("HELLO");
        assertThat(anchorInV1.get("status").asText()).isEqualTo("LOCKED");
        assertThat(anchorInV1.get("releasedBy").isNull()).isTrue();

        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        JsonNode anchorInV2 = release2.body().get("segments").get(0).get("translations").get(0)
                .get("citationAnchors").get(0);
        assertThat(anchorInV2.get("rangeStart").asInt()).isZero();
        assertThat(anchorInV2.get("status").asText()).isEqualTo("RELEASED");
        assertThat(anchorInV2.get("releasedBy").asText()).isEqualTo("carol");
        assertThat(anchorInV2.get("releaseReason").asText()).isEqualTo("法院裁定解除");
    }

    @Test
    @DisplayName("批量修订：先校验全部段落锚点与术语，一项失败整次回滚，查询不到半成品")
    void batchRevisionAtomicity() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文一\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"原文二\"}]");
        submitTranslation(docId, "s1", "en", "alice", "AAA HELLO", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "BBB WORLD", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        ApiResult a1 = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"K/1\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r\"}]");
        ApiResult a2 = registerAnchors(docId, "s2", "en", "alice",
                "[{\"citationKey\":\"K/2\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r\"}]");
        long id1 = firstAnchor(a1).get("anchorId").asLong();
        long id2 = firstAnchor(a2).get("anchorId").asLong();

        // s1 映射正确、s2 缺失映射：整次 422
        ApiResult failed = submitBatch(docId, "alice",
                "[{"
                        + "\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"HELLO AAA\","
                        + "\"sourceVersion\":1,\"anchorMappings\":[{\"anchorId\":" + id1
                        + ",\"rangeStart\":0,\"rangeEnd\":5}]},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"WORLD BBB\","
                        + "\"sourceVersion\":1,\"anchorMappings\":[]}]");
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("issues").get(0).get("code").asText())
                .isEqualTo("ANCHOR_MAPPING_MISSING");
        assertThat(failed.body().get("issues").get(0).get("segmentId").asText()).isEqualTo("s2");

        // 两段译文版本均保持 1，草稿版本保持在登记后的 5（建文档1+两次提交2,3+两次登记4,5）
        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's1'",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT translation_version FROM translation WHERE document_id = ? AND segment_id = 's2'",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'MIGRATED'",
                Integer.class)).isZero();
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(5);

        // 全部映射正确：一次事务成功，两段都迁移，草稿版本加一
        ApiResult ok = submitBatch(docId, "alice",
                "[{"
                        + "\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"HELLO AAA\","
                        + "\"sourceVersion\":1,\"anchorMappings\":[{\"anchorId\":" + id1
                        + ",\"rangeStart\":0,\"rangeEnd\":5}]},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"WORLD BBB\","
                        + "\"sourceVersion\":1,\"anchorMappings\":[{\"anchorId\":" + id2
                        + ",\"rangeStart\":0,\"rangeEnd\":5}]}]");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("itemCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("results").get(0).get("migratedAnchorCount").asInt()).isEqualTo(1);
        assertThat(ok.body().get("results").get(1).get("migratedAnchorCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM citation_anchor_event WHERE event_type = 'MIGRATED'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("批量修订：术语违规与源文版本不符的全部问题一次性返回，整次回滚")
    void batchRevisionCollectsAllIssues() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"深度学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"深度学习\",\"language\":\"en\",\"requiredTranslation\":\"DL\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "DL", 1, newRequestId());

        ApiResult failed = submitBatch(docId, "alice",
                "[{"
                        + "\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"machine learning\","
                        + "\"sourceVersion\":1,\"anchorMappings\":[]},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"deep learning\","
                        + "\"sourceVersion\":9,\"anchorMappings\":[]}]");
        assertThat(failed.status()).isEqualTo(422);
        assertThat(issuesContain(failed, "TERM_VIOLATION")).isTrue();
        assertThat(issuesContain(failed, "SOURCE_VERSION_MISMATCH")).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE content IN ('machine learning','deep learning')",
                Integer.class)).isZero();
    }

    @Test
    @DisplayName("锚点事件历史：REGISTERED/MIGRATED/RELEASED 按序追加，null 语义稳定")
    void anchorEventHistory() throws Exception {
        long docId = prepareApprovedTranslation("Article 1 says HELLO world");
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"L/1\",\"rangeStart\":15,\"rangeEnd\":20,\"lockReason\":\"法定引文\"}]");
        long anchorId = firstAnchor(registered).get("anchorId").asLong();
        submitTranslationWithMappings(docId, "s1", "en", "alice", "HELLO says Article 1 world", 1,
                "[{\"anchorId\":" + anchorId + ",\"rangeStart\":0,\"rangeEnd\":5}]");
        releaseAnchor(docId, anchorId, "carol", "legal", "法院裁定解除");

        ApiResult history = getJson("/api/documents/" + docId + "/anchors/events");
        assertThat(history.status()).isEqualTo(200);
        assertThat(history.body().get("count").asInt()).isEqualTo(3);
        JsonNode registeredEvent = history.body().get("events").get(0);
        assertThat(registeredEvent.get("eventType").asText()).isEqualTo("REGISTERED");
        assertThat(registeredEvent.get("previousStart").isNull()).isTrue();
        assertThat(registeredEvent.get("previousEnd").isNull()).isTrue();
        assertThat(registeredEvent.get("reason").asText()).isEqualTo("法定引文");
        assertThat(history.body().get("events").get(1).get("eventType").asText()).isEqualTo("MIGRATED");
        JsonNode releasedEvent = history.body().get("events").get(2);
        assertThat(releasedEvent.get("eventType").asText()).isEqualTo("RELEASED");
        assertThat(releasedEvent.get("actorId").asText()).isEqualTo("carol");
        assertThat(releasedEvent.get("reason").asText()).isEqualTo("法院裁定解除");
        assertThat(releasedEvent.get("previousStart").isNull()).isTrue();

        // 读取不改变状态：再查一次事件数不变
        assertThat(getJson("/api/documents/" + docId + "/anchors/events").body().get("count").asInt())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("诊断查询：给出实际长度与一致性；只读且含已解除锚点，计数为实际值")
    void diagnostics() throws Exception {
        long docId = prepareApprovedTranslation("AAA HELLO");
        ApiResult registered = registerAnchors(docId, "s1", "en", "alice",
                "[{\"citationKey\":\"K/1\",\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"r\"}]");
        long anchorId = firstAnchor(registered).get("anchorId").asLong();

        ApiResult healthy = getJson("/api/documents/" + docId + "/anchors/diagnostics");
        assertThat(healthy.status()).isEqualTo(200);
        assertThat(healthy.body().get("lockedCount").asInt()).isEqualTo(1);
        assertThat(healthy.body().get("releasedCount").asInt()).isZero();
        assertThat(healthy.body().get("inconsistentCount").asInt()).isZero();
        assertThat(healthy.body().get("anchors").get(0).get("textPreserved").asBoolean()).isTrue();
        assertThat(healthy.body().get("anchors").get(0).get("actualTextAtRange").asText()).isEqualTo("HELLO");

        // 解除后：已解除锚点计入 releasedCount，不计入不一致
        releaseAnchor(docId, anchorId, "carol", "legal", "裁定");
        ApiResult afterRelease = getJson("/api/documents/" + docId + "/anchors/diagnostics");
        assertThat(afterRelease.body().get("releasedCount").asInt()).isEqualTo(1);
        assertThat(afterRelease.body().get("inconsistentCount").asInt()).isZero();
        assertThat(afterRelease.body().get("anchors").get(0).get("status").asText()).isEqualTo("RELEASED");

        // 直接在库中构造生效锚点与当前译文错位的证据场景，诊断须如实报告实际长度与不一致
        jdbc.update("UPDATE citation_anchor SET status = 'LOCKED', released_by = NULL, release_reason = NULL,"
                + " released_at = NULL WHERE anchor_id = ?", anchorId);
        jdbc.update("UPDATE translation SET content = 'XX' WHERE document_id = ?", docId);
        ApiResult broken = getJson("/api/documents/" + docId + "/anchors/diagnostics");
        JsonNode item = broken.body().get("anchors").get(0);
        assertThat(item.get("currentContentLength").asInt()).isEqualTo(2);
        assertThat(item.get("rangeInBounds").asBoolean()).isFalse();
        assertThat(item.get("textPreserved").asBoolean()).isFalse();
        assertThat(item.get("actualTextAtRange").isNull()).isTrue();
        assertThat(broken.body().get("inconsistentCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("锚点登记幂等：同键同参重放同一锚点，异参 409，失败不占键")
    void anchorIdempotency() throws Exception {
        long docId = prepareApprovedTranslation("AAA HELLO");
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"anchors\":[{\"citationKey\":\"K/1\","
                + "\"rangeStart\":4,\"rangeEnd\":9,\"lockReason\":\"法定引文\"}]}";
        String url = "/api/documents/" + docId + "/segments/s1/translations/en/anchors";
        ApiResult first = postJson(url, body, "alice");
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = postJson(url, body, "alice");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(firstAnchor(replay).get("anchorId").asLong())
                .isEqualTo(firstAnchor(first).get("anchorId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM citation_anchor", Integer.class)).isEqualTo(1);

        String different = "{\"requestId\":\"" + requestId + "\",\"anchors\":[{\"citationKey\":\"K/2\","
                + "\"rangeStart\":0,\"rangeEnd\":3,\"lockReason\":\"其他\"}]}";
        ApiResult conflict = postJson(url, different, "alice");
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用该键触发越界 422，再用同键合法登记成功
        String failKey = newRequestId();
        ApiResult failed = postJson(url,
                "{\"requestId\":\"" + failKey + "\",\"anchors\":[{\"citationKey\":\"K/9\","
                        + "\"rangeStart\":0,\"rangeEnd\":99,\"lockReason\":\"r\"}]}", "alice");
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = postJson(url,
                "{\"requestId\":\"" + failKey + "\",\"anchors\":[{\"citationKey\":\"K/9\","
                        + "\"rangeStart\":0,\"rangeEnd\":3,\"lockReason\":\"r\"}]}", "alice");
        assertThat(retried.status()).isEqualTo(201);
    }

    private boolean issuesContain(ApiResult result, String code) {
        for (JsonNode issue : result.body().get("issues")) {
            if (code.equals(issue.get("code").asText())) {
                return true;
            }
        }
        return false;
    }
}
