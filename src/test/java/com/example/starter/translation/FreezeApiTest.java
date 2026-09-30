package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文档术语冻结窗口测试：UTC 左闭右开区间、窗口内 409、解冻整单校验 422 回滚、
 * 幂等重放、查询视图与历史发布快照不回写。时钟固定/拨动，不使用真实休眠。
 */
class FreezeApiTest extends AbstractIntegrationTest {

    private static final Instant T0900 = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant T1000 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant T1030 = Instant.parse("2026-10-01T10:30:00Z");
    private static final Instant T1059 = Instant.parse("2026-10-01T10:59:59.999Z");
    private static final Instant T1100 = Instant.parse("2026-10-01T11:00:00Z");

    private static final String RULES_V2 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]";
    private static final String RULES_V3 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]";

    /** 建两段落 en 文档，建立术语 V2、翻译批准并发布一次（旧发布固化 V2）。 */
    private long preparePublishedV2() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习平台\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"机器学习应用\"}]");
        // 术语 V1 -> V2，模拟题干中“冻结术语 V2”
        updateTerms(docId, 0, "[]", newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning (ML) platform", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "carol", "machine learning (ML) app", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s2", "en", "dave", 1, newRequestId());
        // 草稿版本：建文档 1 + 2 次术语 + 2 次译文 = 5
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        return docId;
    }

    @Test
    @DisplayName("题干主流程：10:00-11:00 冻结 V2，10:30 修改一律 409 原草稿不变；11:00 整单校验通过生成新草稿，旧发布仍为 V2")
    void freezeWindowScenario() throws Exception {
        long docId = preparePublishedV2();

        ApiResult freeze = createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        assertThat(freeze.status()).isEqualTo(201);
        assertThat(freeze.body().get("freezeId").asInt()).isEqualTo(1);
        assertThat(freeze.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(freeze.body().get("status").asText()).isEqualTo("SCHEDULED");

        // 10:30 窗口内：换术语、改译文、发布均 409
        clock.set(T1030);
        assertThat(updateTerms(docId, 2, RULES_V3, newRequestId()).status()).isEqualTo(409);
        ApiResult blockedSubmit = submitTranslation(docId, "s1", "en", "alice",
                "ML platform v2", 1, newRequestId());
        assertThat(blockedSubmit.status()).isEqualTo(409);
        assertThat(publish(docId, 6, 1, newRequestId()).status()).isEqualTo(409);
        // 解冻更新入口在窗口内同样 409
        assertThat(updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId()).status()).isEqualTo(409);

        // 原草稿不变：术语仍 V2、译文正文/版本不变、草稿版本与发布版本不变
        assertThat(getJson("/api/documents/" + docId + "/terms").body().get("termVersion").asInt()).isEqualTo(2);
        Integer termVersion = jdbc.queryForObject(
                "SELECT term_version FROM translation WHERE document_id = ? AND segment_id = ?",
                Integer.class, docId, "s1");
        assertThat(termVersion).isEqualTo(2);
        String content = jdbc.queryForObject(
                "SELECT content FROM translation WHERE document_id = ? AND segment_id = ?",
                String.class, docId, "s1");
        assertThat(content).isEqualTo("machine learning (ML) platform");
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT published_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        assertThat(getFreeze(docId, 1).body().get("status").asText()).isEqualTo("FROZEN");

        // 11:00（右开边界）解冻：整单校验通过，生成新草稿，受影响段落为两个段落/en
        clock.set(T1100);
        ApiResult updated = updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId());
        assertThat(updated.status()).isEqualTo(201);
        assertThat(updated.body().get("termVersion").asInt()).isEqualTo(3);
        assertThat(updated.body().get("ruleCount").asInt()).isEqualTo(1);
        assertThat(updated.body().get("draftVersion").asInt()).isEqualTo(6);
        assertThat(updated.body().get("affectedSegments")).hasSize(2);
        assertThat(updated.body().get("affectedSegments").toString())
                .contains("s1", "s2", "\"language\":\"en\"");

        // 译文随整单提交原子绑定到 V3，正文不变；查询视图为 UPDATED
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM translation WHERE document_id = ? AND segment_id = ?",
                Integer.class, docId, "s1")).isEqualTo(3);
        ApiResult view = getFreeze(docId, 1);
        assertThat(view.body().get("status").asText()).isEqualTo("UPDATED");
        assertThat(view.body().get("draftTermVersion").asInt()).isEqualTo(3);
        assertThat(view.body().get("updatedTermVersion").asInt()).isEqualTo(3);
        assertThat(view.body().get("affectedSegments")).hasSize(2);

        // 基于已完整校验的新草稿可发布，新发布固化 V3
        ApiResult republished = publish(docId, 6, 1, newRequestId());
        assertThat(republished.status()).isEqualTo(201);
        assertThat(republished.body().get("publishedVersion").asInt()).isEqualTo(2);
        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("termVersion").asInt()).isEqualTo(3);
        assertThat(release2.body().get("terms").get(0).get("requiredTranslation").asText()).isEqualTo("ML");

        // 旧发布仍为 V2，历史正文不回写
        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(release1.body().get("terms").get(0).get("requiredTranslation").asText())
                .isEqualTo("machine learning");
        assertThat(release1.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("machine learning (ML) platform");
    }

    @Test
    @DisplayName("左闭右开边界：10:00 整点已冻结（操作 409）；11:00 整点已解冻可更新；09:00 为 SCHEDULED 可改草稿")
    void freezeBoundaries() throws Exception {
        long docId = preparePublishedV2();
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());

        clock.set(T0900);
        assertThat(getFreeze(docId, 1).body().get("status").asText()).isEqualTo("SCHEDULED");
        // 未到窗口：普通术语更新仍被拦截（存在未完成窗口，须走整单校验入口），但非术语类草稿修改允许
        assertThat(updateTerms(docId, 2, RULES_V3, newRequestId()).status()).isEqualTo(409);
        ApiResult addSegment = postJson("/api/documents/" + docId + "/segments",
                "{\"requestId\":\"" + newRequestId() + "\",\"segmentId\":\"s3\",\"sourceText\":\"无关段落\"}");
        assertThat(addSegment.status()).isEqualTo(201);

        // 左闭：10:00:00.000 即冻结
        clock.set(T1000);
        assertThat(getFreeze(docId, 1).body().get("status").asText()).isEqualTo("FROZEN");
        assertThat(updateTerms(docId, 2, RULES_V3, newRequestId()).status()).isEqualTo(409);

        // 窗口最后一毫秒仍冻结
        clock.set(T1059);
        assertThat(updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId()).status()).isEqualTo(409);

        // 右开：11:00:00.000 即解冻
        clock.set(T1100);
        assertThat(getFreeze(docId, 1).body().get("status").asText()).isEqualTo("ENDED");
        assertThat(updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("解冻整单校验失败：一个受影响段落缺译时整单 422，不留下任何部分术语替换且失败不占键")
    void freezeUpdateValidationFailureRollsBack() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习平台\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"机器学习应用\"}]");
        updateTerms(docId, 0, RULES_V2, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning (ML) platform", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // s2 缺译
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        clock.set(T1100);

        String requestId = newRequestId();
        ApiResult failed = updateTermsAfterFreeze(docId, 1, 1, RULES_V3, requestId);
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("message").asText()).contains("s2");

        // 无部分替换：术语版本仍 1、无新版本/规则、无解冻记录与受影响段落、草稿版本不变、译文仍绑定 V1
        assertThat(getJson("/api/documents/" + docId + "/terms").body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_version WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_term_update WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_affected_segment WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT draft_version FROM document WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM translation WHERE document_id = ? AND segment_id = ?",
                Integer.class, docId, "s1")).isEqualTo(1);

        // 失败不占键：同一 requestId 补齐 s2 译文与批准后重试成功
        submitTranslation(docId, "s2", "en", "carol", "machine learning (ML) app", 1, newRequestId());
        approve(docId, "s2", "en", "dave", 1, newRequestId());
        ApiResult retried = updateTermsAfterFreeze(docId, 1, 1, RULES_V3, requestId);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(retried.body().get("affectedSegments")).hasSize(2);
    }

    @Test
    @DisplayName("解冻校验：译文术语版本与窗口不一致或违反新术语规则时整单 422")
    void freezeUpdateStaleOrViolatingTranslations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        // 译文在术语 V1 提交（V1 与 V2 同规则），术语更新到 V2 后未重提：译文仍绑定 V1
        updateTerms(docId, 0, RULES_V2, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        updateTerms(docId, 1, RULES_V2, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        clock.set(T1100);

        // 窗口绑定 V2，译文绑定 V1：422
        ApiResult stale = updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId());
        assertThat(stale.status()).isEqualTo(422);
        assertThat(stale.body().get("message").asText()).contains("术语版本与窗口不一致");

        // 重提绑定 V2 并重新批准后，若译文不满足新规则（缺 ML）：TERM_VIOLATION 422 且返回违规明细
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult violation = updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId());
        assertThat(violation.status()).isEqualTo(422);
        assertThat(violation.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(violation.body().get("violations")).hasSize(1);
        assertThat(violation.body().get("violations").get(0).get("requiredTranslation").asText())
                .isEqualTo("ML");

        // 满足新规则后通过（第三次提交，提交侧仍按 V2 校验，故正文同时含新旧术语；译文版本 3）
        submitTranslation(docId, "s1", "en", "alice", "machine learning (ML)", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 3, newRequestId());
        assertThat(updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("解冻更新幂等：同 requestId 同参重放首次版本与受影响段落；换术语或换窗口 409；重复更新 409")
    void freezeUpdateIdempotency() throws Exception {
        long docId = preparePublishedV2();
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        clock.set(T1100);

        String requestId = newRequestId();
        ApiResult first = updateTermsAfterFreeze(docId, 1, 2, RULES_V3, requestId);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("termVersion").asInt()).isEqualTo(3);

        // 同键同参：重放首次版本与受影响段落，不产生新版本
        ApiResult replay = updateTermsAfterFreeze(docId, 1, 2, RULES_V3, requestId);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("termVersion").asInt()).isEqualTo(3);
        assertThat(replay.body().get("affectedSegments")).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM term_version WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freeze_term_update WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);

        // 同键异参（换术语内容）：409
        ApiResult differentRules = updateTermsAfterFreeze(docId, 1, 2,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"AI\"}]", requestId);
        assertThat(differentRules.status()).isEqualTo(409);

        // 同键异参（换窗口）：第二个窗口生命周期走完后用同一 requestId 调窗口 2，仍 409
        createFreeze(docId, T1100.toEpochMilli(),
                Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(), newRequestId());
        clock.set(Instant.parse("2026-10-01T12:00:00Z"));
        ApiResult differentWindow = updateTermsAfterFreeze(docId, 2, 3, RULES_V3, requestId);
        assertThat(differentWindow.status()).isEqualTo(409);

        // 新 requestId 对窗口 1 重复更新：409（每窗口仅一次解冻更新）
        ApiResult duplicate = updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId());
        assertThat(duplicate.status()).isEqualTo(409);

        // 期望术语版本不等于窗口绑定版本：409
        ApiResult wrongExpected = updateTermsAfterFreeze(docId, 2, 9, RULES_V3, newRequestId());
        assertThat(wrongExpected.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("窗口结束后未完成更新前，普通换术语入口仍 409；完成后恢复普通术语更新")
    void regularTermBlockedUntilFreezeResolved() throws Exception {
        long docId = preparePublishedV2();
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        clock.set(T1100);

        assertThat(updateTerms(docId, 2, RULES_V3, newRequestId()).status()).isEqualTo(409);
        assertThat(updateTermsAfterFreeze(docId, 1, 2, RULES_V3, newRequestId()).status()).isEqualTo(201);

        // 窗口生命周期完成，后续普通术语更新恢复（但现有译文将术语过期，需重新提交，这里只验证入口放行）
        ApiResult next = updateTerms(docId, 3, "[]", newRequestId());
        assertThat(next.status()).isEqualTo(201);
        assertThat(next.body().get("termVersion").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("查询：窗口列表与详情展示冻结起止、绑定/草稿术语版本、状态与受影响段落；文档或窗口不存在 404")
    void freezeQueryViews() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0, RULES_V2, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning (ML)", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());

        ApiResult list = listFreezes(docId);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).hasSize(1);
        assertThat(list.body().get(0).get("startAt").asLong()).isEqualTo(T1000.toEpochMilli());
        assertThat(list.body().get(0).get("endAt").asLong()).isEqualTo(T1100.toEpochMilli());
        assertThat(list.body().get(0).get("termVersion").asInt()).isEqualTo(1);
        assertThat(list.body().get(0).get("draftTermVersion").asInt()).isEqualTo(1);
        assertThat(list.body().get(0).get("updatedTermVersion").isNull()).isTrue();
        assertThat(list.body().get(0).get("affectedSegments")).isEmpty();

        assertThat(getFreeze(docId, 9).status()).isEqualTo(404);
        assertThat(getFreeze(999999, 1).status()).isEqualTo(404);
        assertThat(listFreezes(999999).status()).isEqualTo(404);

        clock.set(T1100);
        assertThat(updateTermsAfterFreeze(docId, 1, 1, RULES_V3, newRequestId()).status()).isEqualTo(201);
        ApiResult after = getFreeze(docId, 1);
        assertThat(after.body().get("status").asText()).isEqualTo("UPDATED");
        assertThat(after.body().get("updatedTermVersion").asInt()).isEqualTo(2);
        assertThat(after.body().get("affectedSegments")).hasSize(1);
        assertThat(after.body().get("affectedSegments").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(after.body().get("affectedSegments").get(0).get("sourceVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("多语言：仅规则语言且源文命中的组合计入受影响段落；解冻后非受影响译文一并绑定新版本并可发布")
    void affectedSegmentsRespectLanguageAndHit() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"},"
                        + "{\"segmentId\":\"s2\",\"sourceText\":\"普通段落\"}]");
        updateTerms(docId, 0, RULES_V2, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "alice", "機械学習", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "carol", "ordinary", 1, newRequestId());
        submitTranslation(docId, "s2", "ja", "carol", "通常", 1, newRequestId());
        for (String seg : new String[]{"s1", "s2"}) {
            approve(docId, seg, "en", "bob", 1, newRequestId());
            approve(docId, seg, "ja", "bob", 1, newRequestId());
        }
        createFreeze(docId, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        clock.set(T1100);

        // 新规则仅 en 且只命中 s1：受影响段落仅 s1/en；新规则必译文与现有译文一致，整单校验通过
        String rulesSame = "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\","
                + "\"requiredTranslation\":\"machine learning\"}]";
        ApiResult updated = updateTermsAfterFreeze(docId, 1, 1, rulesSame, newRequestId());
        assertThat(updated.status()).isEqualTo(201);
        assertThat(updated.body().get("affectedSegments")).hasSize(1);
        assertThat(updated.body().get("affectedSegments").get(0).get("segmentId").asText()).isEqualTo("s1");
        assertThat(updated.body().get("affectedSegments").get(0).get("language").asText()).isEqualTo("en");

        // 四篇译文全部原子绑定新版本，整份草稿完整可发布
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ? AND term_version = ?",
                Integer.class, docId, 2)).isEqualTo(4);
        // 草稿版本：建文档 1 + 术语更新 1 + 四次译文提交 4 + 解冻更新 1 = 7
        ApiResult published = publish(docId, 7, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(getJson("/api/documents/" + docId + "/releases/1").body().get("termVersion").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("创建窗口参数与状态约束：结束不晚于开始 422；创建时已过期 422；立即生效与未完成窗口时再建 409")
    void createFreezeValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult badRange = createFreeze(docId, T1100.toEpochMilli(), T1000.toEpochMilli(), newRequestId());
        assertThat(badRange.status()).isEqualTo(422);

        // 将时钟拨到 10:00：09:00-10:00 的窗口创建时已整体过期（右开），422
        clock.set(T1000);
        ApiResult expired = createFreeze(docId, T0900.toEpochMilli(), T1000.toEpochMilli(), newRequestId());
        assertThat(expired.status()).isEqualTo(422);

        // startAt 早于当前但 endAt 在未来：允许，创建即 FROZEN
        ApiResult immediate = createFreeze(docId, T0900.toEpochMilli(), T1100.toEpochMilli(), newRequestId());
        assertThat(immediate.status()).isEqualTo(201);
        assertThat(immediate.body().get("status").asText()).isEqualTo("FROZEN");

        ApiResult again = createFreeze(docId,
                Instant.parse("2026-10-01T12:00:00Z").toEpochMilli(),
                Instant.parse("2026-10-01T13:00:00Z").toEpochMilli(), newRequestId());
        assertThat(again.status()).isEqualTo(409);

        // 文档不存在 404
        assertThat(createFreeze(999999, T1000.toEpochMilli(), T1100.toEpochMilli(), newRequestId()).status())
                .isEqualTo(404);
    }
}
