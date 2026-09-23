package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语版本退役单测试：创建预览、窗口与替代环校验、激活撤批与快照标记、
 * 发布/批准门禁、草稿原子迁移、幂等与影响查询。
 */
class RetirementApiTest extends AbstractIntegrationTest {

    private static final String RULES_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]";
    private static final String RULES_V2 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]";
    private static final String RULES_V3 =
            "[{\"sourceTerm\":\"深度学习\",\"language\":\"en\",\"requiredTranslation\":\"DL\"}]";
    private static final String FROM = "2026-02-01T00:00:00Z";
    private static final String TO = "2026-03-01T00:00:00Z";

    /**
     * 标准夹具：术语版本 v1（机器学习→machine learning）下 s1 已发布、s2 为 DRAFT、s3 为 APPROVED，
     * 随后建立替代版本 v2（机器学习→ML）。返回时草稿版本为 8、发布版本为 1、当前术语版本为 2。
     */
    private long fixture() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
        postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s2\","
                        + "\"sourceText\":\"机器学习进阶\"}");
        submitTranslation(docId, "s2", "en", "alice", "machine learning advanced", 1, newRequestId());
        postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s3\","
                        + "\"sourceText\":\"机器学习实践\"}");
        submitTranslation(docId, "s3", "en", "alice", "machine learning practice", 1, newRequestId());
        approve(docId, "s3", "en", "bob", 1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("创建退役单：201 返回 DRAFT/APPROVED/PUBLISHED 影响预览，不改写任何内容")
    void createRetirementPreviewsImpactWithoutModifying() throws Exception {
        long docId = fixture();
        ApiResult created = createRetirement(docId, "ret-1", 1, 2, FROM, TO, newRequestId());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(created.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(created.body().get("replacementVersion").asInt()).isEqualTo(2);
        assertThat(created.body().get("effectiveFrom").asText()).isEqualTo(FROM);

        var impact = created.body().get("impact");
        // DRAFT：仅 s2（s1/s3 的批准仍有效）
        assertThat(impact.get("drafts")).hasSize(1);
        assertThat(impact.get("drafts").get(0).get("segmentId").asText()).isEqualTo("s2");
        assertThat(impact.get("drafts").get(0).get("state").asText()).isEqualTo("DRAFT");
        assertThat(impact.get("drafts").get(0).get("hits").get(0).get("sourceTerm").asText())
                .isEqualTo("机器学习");
        assertThat(impact.get("drafts").get(0).get("hits").get(0).get("index").asInt()).isEqualTo(0);
        // APPROVED：s1 与 s3，稳定排序
        assertThat(impact.get("approved")).hasSize(2);
        assertThat(impact.get("approved").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(impact.get("approved").get(1).get("segmentId").asText()).isEqualTo("s3");
        // PUBLISHED：发布版本 1 的 s1/en，记录实际命中位置
        assertThat(impact.get("published")).hasSize(1);
        var published = impact.get("published").get(0);
        assertThat(published.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(published.get("segmentId").asText()).isEqualTo("s1");
        assertThat(published.get("language").asText()).isEqualTo("en");
        assertThat(published.get("hits").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");

        // 不得改写内容：译文、批准、快照均保持原样
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT content FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", String.class, docId))
                .isEqualTo("machine learning advanced");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_retirement WHERE retirement_key = 'ret-1' AND status = 'PENDING'",
                Integer.class)).isEqualTo(1);

        // 影响查询（未激活）：只读返回同样的实时预览
        ApiResult preview = getRetirementImpact(docId, "ret-1");
        assertThat(preview.status()).isEqualTo(200);
        assertThat(preview.body().get("status").asText()).isEqualTo("PENDING");
        assertThat(preview.body().get("impact").get("approved")).hasSize(2);
    }

    @Test
    @DisplayName("创建退役单校验：窗口无效/版本缺失/替代非 ACTIVE/窗口重叠/环/重复键")
    void createRetirementValidation() throws Exception {
        long docId = fixture();
        updateTerms(docId, 2, RULES_V3, newRequestId());

        // 窗口起点不早于终点：422
        assertThat(createRetirement(docId, "bad-window", 1, 2, TO, FROM, newRequestId()).status())
                .isEqualTo(422);
        assertThat(createRetirement(docId, "bad-window2", 1, 2, FROM, FROM, newRequestId()).status())
                .isEqualTo(422);
        // 被退役版本不存在：404
        assertThat(createRetirement(docId, "no-version", 9, 2, FROM, TO, newRequestId()).status())
                .isEqualTo(404);
        // 替代版本不存在或等于被退役版本：422
        assertThat(createRetirement(docId, "no-replacement", 1, 9, FROM, TO, newRequestId()).status())
                .isEqualTo(422);
        assertThat(createRetirement(docId, "self-replacement", 1, 1, FROM, TO, newRequestId()).status())
                .isEqualTo(422);
        // 参数校验：缺窗口/非法版本号 400
        ApiResult missingWindow = postJson("/api/documents/" + docId + "/term-retirements",
                "{\"requestId\":\"" + newRequestId() + "\",\"retirementKey\":\"x\",\"termVersion\":1,"
                        + "\"replacementVersion\":2,\"effectiveTo\":\"" + TO + "\"}");
        assertThat(missingWindow.status()).isEqualTo(400);

        // 窗口重叠：相邻不重叠允许，相交 422
        assertThat(createRetirement(docId, "win-1", 1, 2, FROM, TO, newRequestId()).status()).isEqualTo(201);
        assertThat(createRetirement(docId, "win-2", 1, 2, TO, "2026-04-01T00:00:00Z", newRequestId())
                .status()).isEqualTo(201);
        assertThat(createRetirement(docId, "win-3", 1, 2, "2026-02-15T00:00:00Z",
                "2026-05-01T00:00:00Z", newRequestId()).status()).isEqualTo(422);

        // retirementKey 全局唯一：重复键 409
        assertThat(createRetirement(docId, "win-1", 2, 3, FROM, TO, newRequestId()).status())
                .isEqualTo(409);

        // 直接替代环：v2→v1 与已有 v1→v2 成环，422
        assertThat(createRetirement(docId, "cyc-direct", 2, 1, FROM, TO, newRequestId()).status())
                .isEqualTo(422);
        // 间接替代环：v2→v3 后 v3→v1 成环，422
        assertThat(createRetirement(docId, "cyc-b", 2, 3, FROM, TO, newRequestId()).status()).isEqualTo(201);
        assertThat(createRetirement(docId, "cyc-c", 3, 1, FROM, TO, newRequestId()).status())
                .isEqualTo(422);

        // 替代版本已退役（已激活退役单且生效起点已过）：422；独立文档避免与上面替代环耦合
        long doc2 = fixture();
        updateTerms(doc2, 2, RULES_V3, newRequestId());
        assertThat(createRetirement(doc2, "ret-v2", 2, 3, "2025-01-01T00:00:00Z",
                "2025-06-01T00:00:00Z", newRequestId()).status()).isEqualTo(201);
        assertThat(activateRetirement(doc2, "ret-v2", newRequestId()).status()).isEqualTo(200);
        assertThat(createRetirement(doc2, "ret-v1", 1, 2, FROM, TO, newRequestId()).status())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("激活：冻结影响快照、撤回 APPROVED 清除批准、发布快照不可变仅落标记、DRAFT 不改文本")
    void activateFreezesImpactAndRevokesApprovals() throws Exception {
        long docId = fixture();
        String snapshotBefore = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId);
        createRetirement(docId, "ret-1", 1, 2, FROM, TO, newRequestId());

        ApiResult activated = activateRetirement(docId, "ret-1", newRequestId());
        assertThat(activated.status()).isEqualTo(200);
        assertThat(activated.body().get("status").asText()).isEqualTo("ACTIVATED");
        var impact = activated.body().get("impact");
        assertThat(impact.get("drafts")).hasSize(1);
        assertThat(impact.get("approved")).hasSize(2);
        assertThat(impact.get("published")).hasSize(1);

        // APPROVED 撤回为 DRAFT：批准全部清除
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();
        // 普通 DRAFT 不自动改文本
        assertThat(jdbc.queryForObject("SELECT content FROM translation WHERE document_id = ? "
                + "AND segment_id = 's2' AND language = 'en'", String.class, docId))
                .isEqualTo("machine learning advanced");
        // 已发布快照不可变，仅落受影响标记与命中位置
        String snapshotAfter = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId);
        assertThat(snapshotAfter).isEqualTo(snapshotBefore);
        var marking = jdbc.queryForMap(
                "SELECT published_version, segment_id, language, hit_terms FROM retirement_snapshot_impact "
                        + "WHERE retirement_key = 'ret-1'");
        assertThat(marking.get("published_version")).isEqualTo(1);
        assertThat(marking.get("segment_id")).isEqualTo("s1");
        assertThat(marking.get("hit_terms").toString()).contains("机器学习");
        // 草稿版本加一（8 → 9）
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(9);

        // 重复激活 409；影响查询返回冻结快照
        assertThat(activateRetirement(docId, "ret-1", newRequestId()).status()).isEqualTo(409);
        ApiResult frozen = getRetirementImpact(docId, "ret-1");
        assertThat(frozen.body().get("status").asText()).isEqualTo("ACTIVATED");
        assertThat(frozen.body().get("impact").get("approved")).hasSize(2);
        // 激活后新提交译文不改变冻结快照
        submitTranslation(docId, "s2", "en", "alice", "ML v2 draft", 1, newRequestId());
        ApiResult frozenAfter = getRetirementImpact(docId, "ret-1");
        assertThat(frozenAfter.body().get("impact").get("drafts")).hasSize(1);
        assertThat(frozenAfter.body().get("impact").get("drafts").get(0)
                .get("translationVersion").asInt()).isEqualTo(1);

        // 退役单不存在/跨文档：404
        assertThat(activateRetirement(docId, "no-such-key", newRequestId()).status()).isEqualTo(404);
        assertThat(getRetirementImpact(docId, "no-such-key").status()).isEqualTo(404);
        long otherDoc = createDocument(newRequestId(), "[\"en\"]", "[]");
        assertThat(getRetirementImpact(otherDoc, "ret-1").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("发布门禁：生效时刻后新批准与发布不得引用已退役版本，窗口结束后不自动恢复")
    void approveAndPublishGateAfterEffectiveFrom() throws Exception {
        long docId = fixture();
        // 窗口在未来：2026-06-01 ~ 2026-07-01，当前 2026-01-01
        createRetirement(docId, "ret-future", 1, 2,
                "2026-06-01T00:00:00Z", "2026-07-01T00:00:00Z", newRequestId());
        assertThat(activateRetirement(docId, "ret-future", newRequestId()).status()).isEqualTo(200);

        // 生效时刻之前：批准绑定退役版本的译文仍允许
        assertThat(approve(docId, "s2", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        // 进入生效窗口：批准与发布均 422
        clock.setInstant(Instant.parse("2026-06-15T00:00:00Z"));
        ApiResult gatedApprove = approve(docId, "s3", "en", "bob", 1, newRequestId());
        assertThat(gatedApprove.status()).isEqualTo(422);
        assertThat(gatedApprove.body().get("message").asText()).contains("退役");
        ApiResult gatedPublish = publish(docId, 9, 1, newRequestId());
        assertThat(gatedPublish.status()).isEqualTo(422);

        // 窗口结束后不自动恢复：仍 422
        clock.setInstant(Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(approve(docId, "s3", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);
        assertThat(publish(docId, 9, 1, newRequestId()).status()).isEqualTo(422);
    }

    @Test
    @DisplayName("草稿迁移：恰好覆盖仍受影响草稿后逐稿增版并保存摘要，重新批准后发布成功")
    void migrateDraftsFullFlow() throws Exception {
        long docId = fixture();
        createRetirement(docId, "ret-1", 1, 2, FROM, TO, newRequestId());
        activateRetirement(docId, "ret-1", newRequestId());
        // 激活后草稿版本 9；仍受影响草稿：s1、s2、s3（s1/s3 已撤回为 DRAFT）

        String drafts = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML practice\"}]";
        ApiResult migrated = migrateDrafts(docId, "ret-1", "carol", 9, drafts, newRequestId());
        assertThat(migrated.status()).isEqualTo(200);
        assertThat(migrated.body().get("migratedCount").asInt()).isEqualTo(3);
        assertThat(migrated.body().get("draftVersion").asInt()).isEqualTo(10);
        var entries = migrated.body().get("entries");
        assertThat(entries).hasSize(3);
        // 稳定排序：s1、s2、s3，逐稿增版并保存旧/新摘要与规则版本
        for (int i = 0; i < 3; i++) {
            var entry = entries.get(i);
            assertThat(entry.get("segmentId").asText()).isEqualTo("s" + (i + 1));
            assertThat(entry.get("translationVersion").asInt()).isEqualTo(2);
            assertThat(entry.get("ruleVersion").asInt()).isEqualTo(2);
            assertThat(entry.get("oldDigest").asText()).hasSize(64);
            assertThat(entry.get("newDigest").asText()).hasSize(64);
        }
        assertThat(entries.get(0).get("oldDigest").asText()).isEqualTo(sha256("machine learning"));
        assertThat(entries.get(0).get("newDigest").asText()).isEqualTo(sha256("ML"));

        // 数据库终态：译文绑定替代版本、迁移记录落库
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ? "
                + "AND term_version = 2 AND translation_version = 2", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_migration "
                + "WHERE retirement_key = 'ret-1' AND rule_version = 2", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT new_digest FROM retirement_migration "
                + "WHERE retirement_key = 'ret-1' AND segment_id = 's2' AND language = 'en'",
                String.class)).isEqualTo(sha256("ML advanced"));

        // 迁移后重新批准并发布成功（当前时刻在窗口之前，且译文已绑定替代版本）
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        approve(docId, "s2", "en", "bob", 2, newRequestId());
        approve(docId, "s3", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 10, 1, newRequestId()).status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("草稿迁移失败分支：遗漏/多余/违规/版本不符/未激活均整体失败且不落任何变更")
    void migrateDraftsFailuresAreAtomic() throws Exception {
        long docId = fixture();
        createRetirement(docId, "ret-1", 1, 2, FROM, TO, newRequestId());

        // 未激活：409
        String all = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML practice\"}]";
        assertThat(migrateDrafts(docId, "ret-1", "carol", 9, all, newRequestId()).status())
                .isEqualTo(409);

        activateRetirement(docId, "ret-1", newRequestId());

        // expectedVersion 不符：409
        assertThat(migrateDrafts(docId, "ret-1", "carol", 8, all, newRequestId()).status())
                .isEqualTo(409);
        // 遗漏：422
        String missing = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"}]";
        ApiResult missingResult = migrateDrafts(docId, "ret-1", "carol", 9, missing, newRequestId());
        assertThat(missingResult.status()).isEqualTo(422);
        assertThat(missingResult.body().get("message").asText()).contains("遗漏");
        // 多余：422
        String extra = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML practice\"},"
                + "{\"segmentId\":\"s9\",\"language\":\"en\",\"content\":\"ML extra\"}]";
        ApiResult extraResult = migrateDrafts(docId, "ret-1", "carol", 9, extra, newRequestId());
        assertThat(extraResult.status()).isEqualTo(422);
        assertThat(extraResult.body().get("message").asText()).contains("多余");
        // 任一违规：整体 422 并返回全部违规术语
        String violating = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"no term\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"also no term\"}]";
        ApiResult violated = migrateDrafts(docId, "ret-1", "carol", 9, violating, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violated.body().get("violations")).hasSize(2);

        // 全部失败整体回滚：译文仍绑定 v1、无迁移记录、草稿版本仍为 9
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ? "
                + "AND term_version = 1 AND translation_version = 1", Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_migration", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(9);
    }

    @Test
    @DisplayName("退役幂等：创建/迁移同键重放、异参 409、失败不占键、迁移集合换序等价")
    void retirementIdempotency() throws Exception {
        long docId = fixture();
        String createKey = newRequestId();
        ApiResult first = createRetirement(docId, "ret-1", 1, 2, FROM, TO, createKey);
        assertThat(first.status()).isEqualTo(201);
        // 同键同参重放：返回首次响应，不新增退役单
        ApiResult replay = createRetirement(docId, "ret-1", 1, 2, FROM, TO, createKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().toString()).isEqualTo(first.body().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_retirement", Integer.class)).isEqualTo(1);
        // 同键异参：409
        assertThat(createRetirement(docId, "ret-2", 1, 2, FROM, TO, createKey).status()).isEqualTo(409);
        // 失败不占键：先触发 422（窗口无效），再修正参数成功
        String failKey = newRequestId();
        assertThat(createRetirement(docId, "ret-3", 1, 2, TO, FROM, failKey).status()).isEqualTo(422);
        assertThat(createRetirement(docId, "ret-3", 1, 2, TO, "2026-04-01T00:00:00Z", failKey).status())
                .isEqualTo(201);

        activateRetirement(docId, "ret-1", newRequestId());
        // 迁移同键重放且集合换序等价：正序成功后，倒序同键重放首次响应，不重复增版
        String migrateKey = newRequestId();
        String ordered = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML practice\"}]";
        ApiResult migrated = migrateDrafts(docId, "ret-1", "carol", 9, ordered, migrateKey);
        assertThat(migrated.status()).isEqualTo(200);
        String reversed = "[{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML practice\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"}]";
        ApiResult migratedReplay = migrateDrafts(docId, "ret-1", "carol", 9, reversed, migrateKey);
        assertThat(migratedReplay.status()).isEqualTo(200);
        assertThat(migratedReplay.body().toString()).isEqualTo(migrated.body().toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM retirement_migration", Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ? "
                + "AND translation_version = 2", Integer.class, docId)).isEqualTo(3);
        // 同键异参：409
        String different = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML v2\"},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"content\":\"ML advanced\"},"
                + "{\"segmentId\":\"s3\",\"language\":\"en\",\"content\":\"ML practice\"}]";
        assertThat(migrateDrafts(docId, "ret-1", "carol", 9, different, migrateKey).status())
                .isEqualTo(409);
        // 迁移失败不占键：先遗漏 422，再同键完整提交成功
        String retryKey = newRequestId();
        String partial = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"content\":\"ML\"}]";
        assertThat(migrateDrafts(docId, "ret-1", "carol", 10, partial, retryKey).status()).isEqualTo(422);
        // 注意：首次迁移已完成，受影响草稿集合已空，完整集合变为空集
        assertThat(migrateDrafts(docId, "ret-1", "carol", 10, "[]", retryKey).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("影响查询稳定排序：多语言多段落按段落与语言排序输出")
    void impactQueryStableOrdering() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\", \"fr\"]",
                "[{\"segmentId\":\"s2\",\"sourceText\":\"机器学习二\"},"
                        + "{\"segmentId\":\"s1\",\"sourceText\":\"机器学习一\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"fr\","
                        + "\"requiredTranslation\":\"apprentissage automatique\"}]", newRequestId());
        submitTranslation(docId, "s2", "fr", "alice", "apprentissage automatique deux", 1, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning one", 1, newRequestId());
        submitTranslation(docId, "s1", "fr", "alice", "apprentissage automatique un", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "machine learning two", 1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());

        ApiResult created = createRetirement(docId, "ret-order", 1, 2, FROM, TO, newRequestId());
        assertThat(created.status()).isEqualTo(201);
        var drafts = created.body().get("impact").get("drafts");
        assertThat(drafts).hasSize(4);
        assertThat(drafts.get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(drafts.get(0).get("language").asText()).isEqualTo("en");
        assertThat(drafts.get(1).get("segmentId").asText()).isEqualTo("s1");
        assertThat(drafts.get(1).get("language").asText()).isEqualTo("fr");
        assertThat(drafts.get(2).get("segmentId").asText()).isEqualTo("s2");
        assertThat(drafts.get(2).get("language").asText()).isEqualTo("en");
        assertThat(drafts.get(3).get("segmentId").asText()).isEqualTo("s2");
        assertThat(drafts.get(3).get("language").asText()).isEqualTo("fr");
    }

    /** 文本 SHA-256 十六进制摘要，与服务端迁移记录口径一致。 */
    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }
}
