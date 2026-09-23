package com.example.starter.translation;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语版本退役窗口与草稿原子迁移测试：创建预览、激活撤批、完整迁移、
 * 发布/批准门禁、替代环检测、窗口重叠、幂等与失败分支。
 */
class RetirementApiTest extends AbstractIntegrationTest {

    private static final String RULES_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"}]";
    private static final String RULES_V2 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"NN\"}]";
    private static final String PAST_FROM = "2020-01-01T00:00:00Z";
    private static final String PAST_TO = "2021-01-01T00:00:00Z";
    private static final String FUTURE_FROM = "2099-01-01T00:00:00Z";
    private static final String FUTURE_TO = "2100-01-01T00:00:00Z";
    private static final String ENTRIES_ALL =
            "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                    + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"NN\"},"
                    + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"plain text\"}]";

    /**
     * 标准场景：文档三段，术语 v1 规则两条；s1/s3 批准、s2 发布后重提交变为草稿；
     * 发布版本 1 固化术语 v1；随后建立术语 v2（当前版本），文档草稿版本 7。
     * 退役 v1（替代 v2）的影响：APPROVED s1/s3、DRAFT s2、PUBLISHED 发布版本 1。
     */
    private long setupPublishedDoc() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"神经网络\"},"
                        + "{\"segmentId\":\"s3\",\"sourceText\":\"普通文本\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "neural network", 1, newRequestId());
        submitTranslation(docId, "s3", "en", "alice", "plain text", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "bob", 1, newRequestId());
        approve(docId, "s3", "en", "bob", 1, newRequestId());
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        // 发布后重提交 s2：译文版本 2，旧批准失效，s2 回到草稿态
        submitTranslation(docId, "s2", "en", "alice", "neural network", 1, newRequestId());
        ApiResult v2 = updateTerms(docId, 1, RULES_V2, newRequestId());
        assertThat(v2.status()).isEqualTo(201);
        assertThat(v2.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(v2.body().get("draftVersion").asInt()).isEqualTo(7);
        return docId;
    }

    @Test
    @DisplayName("创建退役单：201 返回只读预览（DRAFT/APPROVED/PUBLISHED 及命中位置），不改写任何内容")
    void createRetirementPreviewsImpactWithoutModifying() throws Exception {
        long docId = setupPublishedDoc();
        JsonNode releaseBefore = getJson("/api/documents/" + docId + "/releases/1").body();

        ApiResult created = createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("replacementVersion").asInt()).isEqualTo(2);
        assertThat(created.body().get("language").asText()).isEqualTo("en");

        JsonNode impact = created.body().get("impact");
        assertThat(impact).hasSize(5);
        // 稳定排序：APPROVED(s1, s3) → DRAFT(s2) → PUBLISHED(1/s1, 1/s2)
        assertThat(impact.get(0).get("kind").asText()).isEqualTo("APPROVED");
        assertThat(impact.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(impact.get(0).get("hitTerms")).hasSize(1);
        assertThat(impact.get(0).get("hitTerms").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(impact.get(0).get("hitTerms").get(0).get("startOffset").asInt()).isZero();
        assertThat(impact.get(0).get("hitTerms").get(0).get("endOffset").asInt()).isEqualTo(4);
        assertThat(impact.get(1).get("kind").asText()).isEqualTo("APPROVED");
        assertThat(impact.get(1).get("segmentId").asText()).isEqualTo("s3");
        assertThat(impact.get(1).get("hitTerms")).isEmpty();
        assertThat(impact.get(2).get("kind").asText()).isEqualTo("DRAFT");
        assertThat(impact.get(2).get("segmentId").asText()).isEqualTo("s2");
        assertThat(impact.get(2).get("hitTerms").get(0).get("sourceTerm").asText()).isEqualTo("神经网络");
        assertThat(impact.get(3).get("kind").asText()).isEqualTo("PUBLISHED");
        assertThat(impact.get(3).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(impact.get(3).get("segmentId").asText()).isEqualTo("s1");
        assertThat(impact.get(4).get("kind").asText()).isEqualTo("PUBLISHED");
        assertThat(impact.get(4).get("segmentId").asText()).isEqualTo("s2");

        // 预览不改写内容：译文、批准、发布快照均保持原样
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT content FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", String.class, docId))
                .isEqualTo("neural network");
        assertThat(getJson("/api/documents/" + docId + "/releases/1").body()).isEqualTo(releaseBefore);

        // 影响查询只读且稳定排序：两次查询结果一致
        ApiResult first = getRetirement(docId, "ret-1");
        ApiResult second = getRetirement(docId, "ret-1");
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body()).isEqualTo(second.body());
        assertThat(first.body().get("impact")).hasSize(5);
        assertThat(getRetirement(docId, "no-such-key").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("创建退役单失败分支：版本缺失 404/422、直接环 422、窗口非法 422、语言越界 422、窗口重叠与键重复 409")
    void createRetirementFailures() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());

        assertThat(createRetirement(docId, 99, "en", 2, PAST_FROM, PAST_TO, "k-x", newRequestId()).status())
                .isEqualTo(404);
        assertThat(createRetirement(docId, 1, "en", 99, PAST_FROM, PAST_TO, "k-x", newRequestId()).status())
                .isEqualTo(422);
        // 直接替代环：替代版本与退役版本相同
        assertThat(createRetirement(docId, 1, "en", 1, PAST_FROM, PAST_TO, "k-x", newRequestId()).status())
                .isEqualTo(422);
        // 窗口非法：起止相等、起晚于止、格式错误
        assertThat(createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_FROM, "k-x", newRequestId()).status())
                .isEqualTo(422);
        assertThat(createRetirement(docId, 1, "en", 2, PAST_TO, PAST_FROM, "k-x", newRequestId()).status())
                .isEqualTo(422);
        assertThat(createRetirement(docId, 1, "en", 2, "not-a-date", PAST_TO, "k-x", newRequestId()).status())
                .isEqualTo(422);
        // 语言不在文档目标语言中
        assertThat(createRetirement(docId, 1, "fr", 2, PAST_FROM, PAST_TO, "k-x", newRequestId()).status())
                .isEqualTo(422);

        ApiResult created = createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "k-1", newRequestId());
        assertThat(created.status()).isEqualTo(201);
        // 窗口重叠（左闭右开区间相交）：409
        assertThat(createRetirement(docId, 1, "en", 2, "2020-06-01T00:00:00Z", "2022-01-01T00:00:00Z",
                "k-2", newRequestId()).status()).isEqualTo(409);
        // 相邻窗口不重叠：201
        assertThat(createRetirement(docId, 1, "en", 2, "2021-01-01T00:00:00Z", "2022-01-01T00:00:00Z",
                "k-3", newRequestId()).status()).isEqualTo(201);
        // retirementKey 重复：同版本同语言换不重叠窗口仍因键重复 409（不涉及替代环）
        assertThat(createRetirement(docId, 1, "en", 2, "2022-01-01T00:00:00Z", "2023-01-01T00:00:00Z",
                "k-1", newRequestId()).status()).isEqualTo(409);
        // 间接替代环：v1→v2 已存在，再建 v2→v1 成环（使用新键与未来窗口）
        assertThat(createRetirement(docId, 2, "en", 1, FUTURE_FROM, FUTURE_TO, "k-4", newRequestId()).status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("替代环检测：三版本间接环 422；替代版本已退役（非 ACTIVE）422")
    void replacementCycleAndInactiveReplacement() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());
        updateTerms(docId, 2, "[]", newRequestId());

        assertThat(createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "r-1", newRequestId()).status())
                .isEqualTo(201);
        assertThat(createRetirement(docId, 2, "en", 3, PAST_FROM, PAST_TO, "r-2", newRequestId()).status())
                .isEqualTo(201);
        // v3→v1：沿链 v1→v2→v3 回到 v3，构成间接替代环
        ApiResult cycle = createRetirement(docId, 3, "en", 1, FUTURE_FROM, FUTURE_TO, "r-3", newRequestId());
        assertThat(cycle.status()).isEqualTo(422);
        assertThat(cycle.body().get("message").asText()).contains("替代环");

        // 激活 r-1 后 v1 退役：再引用 v1 作为替代版本 422
        assertThat(activateRetirement(docId, "r-1", newRequestId()).status()).isEqualTo(200);
        ApiResult inactive = createRetirement(docId, 3, "en", 1, FUTURE_FROM, FUTURE_TO, "r-4", newRequestId());
        assertThat(inactive.status()).isEqualTo(422);
        assertThat(inactive.body().get("message").asText()).contains("ACTIVE");
    }

    @Test
    @DisplayName("激活退役单：事务内冻结影响、撤批 APPROVED、术语版本置 RETIRED；快照与草稿文本不变；重复激活 422")
    void activateRetirementFreezesImpactAndWithdrawsApprovals() throws Exception {
        long docId = setupPublishedDoc();
        JsonNode releaseBefore = getJson("/api/documents/" + docId + "/releases/1").body();
        createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", newRequestId());

        ApiResult activated = activateRetirement(docId, "ret-1", newRequestId());
        assertThat(activated.status()).isEqualTo(200);
        assertThat(activated.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(activated.body().get("impact")).hasSize(5);

        // APPROVED 撤批：批准清空；普通 DRAFT 文本不变；术语版本置 RETIRED 不自动恢复
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT content FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", String.class, docId))
                .isEqualTo("neural network");
        assertThat(jdbc.queryForObject("SELECT status FROM term_version WHERE document_id = ? "
                + "AND term_version = 1", String.class, docId)).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM term_version WHERE document_id = ? "
                + "AND term_version = 2", String.class, docId)).isEqualTo("ACTIVE");

        // 已发布快照不可变，仅登记历史影响
        assertThat(getJson("/api/documents/" + docId + "/releases/1").body()).isEqualTo(releaseBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_impact WHERE document_id = ? "
                + "AND kind = 'PUBLISHED'", Integer.class, docId)).isEqualTo(2);

        // 影响查询返回冻结快照
        ApiResult view = getRetirement(docId, "ret-1");
        assertThat(view.body().get("status").asText()).isEqualTo("ACTIVE");
        assertThat(view.body().get("impact")).hasSize(5);

        // 重复激活 422；不存在退役单 404
        assertThat(activateRetirement(docId, "ret-1", newRequestId()).status()).isEqualTo(422);
        assertThat(activateRetirement(docId, "no-such", newRequestId()).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("生效门禁：窗口开始后新批准与发布不得引用已退役版本；窗口结束不自动恢复；生效前不拦截")
    void retirementGatesApproveAndPublish() throws Exception {
        long docId = setupPublishedDoc();
        createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", newRequestId());
        activateRetirement(docId, "ret-1", newRequestId());

        // 窗口（含结束后）新批准引用已退役版本：422
        ApiResult approveRetired = approve(docId, "s2", "en", "bob", 2, newRequestId());
        assertThat(approveRetired.status()).isEqualTo(422);
        assertThat(approveRetired.body().get("message").asText()).contains("已退役");

        // 发布引用已退役版本：422（窗口 2021 年已结束也不恢复）
        ApiResult publishRetired = publish(docId, 7, 1, newRequestId());
        assertThat(publishRetired.status()).isEqualTo(422);

        // 生效前不拦截：未来窗口的退役单激活后，批准仍可进行
        long docId2 = setupPublishedDoc();
        createRetirement(docId2, 1, "en", 2, FUTURE_FROM, FUTURE_TO, "ret-f", newRequestId());
        assertThat(activateRetirement(docId2, "ret-f", newRequestId()).status()).isEqualTo(200);
        ApiResult approveBeforeEffective = approve(docId2, "s2", "en", "bob", 2, newRequestId());
        assertThat(approveBeforeEffective.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("草稿原子迁移：完整覆盖成功逐稿增版并记录摘要与规则版本；遗漏/多余/违规/版本不符整体失败")
    void migrateDraftsAtomically() throws Exception {
        long docId = setupPublishedDoc();
        createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", newRequestId());

        // 未激活不能迁移
        assertThat(migrateDrafts(docId, "ret-1", 7, ENTRIES_ALL, newRequestId()).status()).isEqualTo(422);

        activateRetirement(docId, "ret-1", newRequestId());

        // expectedVersion 不符：409
        assertThat(migrateDrafts(docId, "ret-1", 6, ENTRIES_ALL, newRequestId()).status()).isEqualTo(409);

        // 遗漏仍受影响草稿：422
        ApiResult missing = migrateDrafts(docId, "ret-1", 7,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"}]", newRequestId());
        assertThat(missing.status()).isEqualTo(422);
        assertThat(missing.body().get("message").asText()).contains("遗漏");

        // 多余条目：422
        ApiResult extra = migrateDrafts(docId, "ret-1", 7,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"NN\"},"
                        + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"plain text\"},"
                        + "{\"segmentId\":\"s9\",\"language\":\"en\",\"content\":\"x\"}]", newRequestId());
        assertThat(extra.status()).isEqualTo(422);
        assertThat(extra.body().get("message").asText()).contains("不在受影响草稿集合");

        // 替换结果违反替代版本规则：422 返回全部违规术语
        ApiResult violated = migrateDrafts(docId, "ret-1", 7,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"wrong\"},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"NN\"},"
                        + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"plain text\"}]",
                newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violated.body().get("violations")).hasSize(1);
        assertThat(violated.body().get("violations").get(0).get("requiredTranslation").asText())
                .isEqualTo("ML");

        // 失败均整体回滚：无迁移记录、译文未改、草稿版本不变
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM draft_migration WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(7);

        // 完整迁移成功：逐稿增版、改绑替代版本、记录摘要与规则版本
        ApiResult migrated = migrateDrafts(docId, "ret-1", 7, ENTRIES_ALL, newRequestId());
        assertThat(migrated.status()).isEqualTo(200);
        assertThat(migrated.body().get("migratedCount").asInt()).isEqualTo(3);
        assertThat(migrated.body().get("draftVersion").asInt()).isEqualTo(8);
        JsonNode migrations = migrated.body().get("migrations");
        assertThat(migrations).hasSize(3);
        assertThat(migrations.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(migrations.get(0).get("translationVersion").asInt()).isEqualTo(2);
        assertThat(migrations.get(0).get("ruleVersion").asInt()).isEqualTo(2);
        assertThat(migrations.get(0).get("oldSummary").asText()).hasSize(32);
        assertThat(migrations.get(0).get("newSummary").asText()).hasSize(32);
        assertThat(migrations.get(0).get("oldSummary").asText())
                .isNotEqualTo(migrations.get(0).get("newSummary").asText());
        assertThat(migrations.get(1).get("segmentId").asText()).isEqualTo("s2");
        assertThat(migrations.get(1).get("translationVersion").asInt()).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM draft_migration WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT term_version FROM translation WHERE document_id = ? "
                + "AND segment_id = 's1' AND language = 'en'", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM translation WHERE document_id = ? "
                + "AND segment_id = 's1' AND language = 'en'", String.class, docId)).isEqualTo("ML");

        // 迁移后可重新批准并发布
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        approve(docId, "s2", "en", "bob", 3, newRequestId());
        approve(docId, "s3", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 8, 1, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(getJson("/api/documents/" + docId + "/releases/2").body()
                .get("termVersion").asInt()).isEqualTo(2);

        // 受影响草稿已全部迁移：再次迁移（含原条目）因多余而 422
        assertThat(migrateDrafts(docId, "ret-1", 8, ENTRIES_ALL, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("退役与迁移幂等：同键同参重放、异参 409、失败不占键、迁移集合换序等价")
    void retirementAndMigrationIdempotency() throws Exception {
        long docId = setupPublishedDoc();
        String createKey = newRequestId();
        ApiResult first = createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", createKey);
        assertThat(first.status()).isEqualTo(201);
        // 同键同参重放首次响应，不产生新退役单
        ApiResult replay = createRetirement(docId, 1, "en", 2, PAST_FROM, PAST_TO, "ret-1", createKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(first.body());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_retirement WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        // 同键异参 409
        assertThat(createRetirement(docId, 1, "en", 2, FUTURE_FROM, FUTURE_TO, "ret-1", createKey).status())
                .isEqualTo(409);
        // 失败不占键：先 422（窗口非法），再同键修正参数成功（同版本不重叠新窗口，不涉及替代环）
        String failKey = newRequestId();
        assertThat(createRetirement(docId, 1, "en", 2, "2030-01-01T00:00:00Z", "2030-01-01T00:00:00Z",
                "ret-2", failKey).status()).isEqualTo(422);
        assertThat(createRetirement(docId, 1, "en", 2, "2030-01-01T00:00:00Z", "2031-01-01T00:00:00Z",
                "ret-2", failKey).status()).isEqualTo(201);

        // 迁移集合换序等价：相同条目不同顺序重放首次响应，不重复迁移
        activateRetirement(docId, "ret-1", newRequestId());
        String migrateKey = newRequestId();
        ApiResult migrated = migrateDrafts(docId, "ret-1", 7, ENTRIES_ALL, migrateKey);
        assertThat(migrated.status()).isEqualTo(200);
        ApiResult reordered = migrateDrafts(docId, "ret-1", 7,
                "[{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"plain text\"},"
                        + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"NN\"},"
                        + "{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"}]", migrateKey);
        assertThat(reordered.status()).isEqualTo(200);
        assertThat(reordered.body()).isEqualTo(migrated.body());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM draft_migration WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT translation_version FROM translation WHERE document_id = ? "
                + "AND segment_id = 's1' AND language = 'en'", Integer.class, docId)).isEqualTo(2);
        // 同键异参（内容不同）409
        assertThat(migrateDrafts(docId, "ret-1", 7,
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"}]", migrateKey).status())
                .isEqualTo(409);
    }
}
