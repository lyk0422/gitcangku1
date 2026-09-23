package com.example.starter.translation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布快照撤回、当前可用版本回退与发布目录查询的主流程、失败分支与幂等边界测试。
 * 时间断言使用可控固定 Clock（UTC），撤回时刻验证精确到秒；
 * 嵌套 @TestConfiguration 由 Spring Boot 测试支持自动注册，@Primary 覆盖系统时钟。
 */
class ReleaseRevocationApiTest extends AbstractIntegrationTest {

    static final Instant FIXED_NOW = Instant.parse("2026-09-23T10:00:00Z");

    @BeforeEach
    void resetClock() {
        FixedClockConfig.NOW.set(FIXED_NOW);
    }

    /** 可控 Clock：默认固定在 FIXED_NOW，测试可随时推进；@Primary 覆盖生产的系统时钟。 */
    @TestConfiguration
    static class FixedClockConfig {

        static final AtomicReference<Instant> NOW = new AtomicReference<>(FIXED_NOW);

        @Bean
        @Primary
        Clock testClock() {
            return new Clock() {
                @Override
                public Instant instant() {
                    return NOW.get();
                }

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }
            };
        }
    }

    private long prepareSingleRelease() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 草稿版本 2，发布版本 0
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        return docId;
    }

    private long prepareTwoReleases() throws Exception {
        long docId = prepareSingleRelease();
        // 修订源文并重新翻译、批准后发布 v2
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v2\"}");
        submitTranslation(docId, "s1", "en", "alice", "hello v2", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult second = publish(docId, 4, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);
        return docId;
    }

    private long prepareThreeReleases() throws Exception {
        long docId = prepareTwoReleases();
        // 再修订源文并重新翻译、批准后发布 v3
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"原文v3\"}");
        submitTranslation(docId, "s1", "en", "alice", "hello v3", 3, newRequestId());
        approve(docId, "s1", "en", "bob", 3, newRequestId());
        ApiResult third = publish(docId, 6, 2, newRequestId());
        assertThat(third.status()).isEqualTo(201);
        assertThat(third.body().get("publishedVersion").asInt()).isEqualTo(3);
        return docId;
    }

    @Test
    @DisplayName("从未发布：当前可用返回 200、快照 null，目录修订号 0，目录为空")
    void neverPublished() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("snapshot").isNull());
        assertThat(current.body().get("releaseRevision").asInt()).isZero();

        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.body().get("releaseRevision").asInt()).isZero();
        assertThat(catalog.body().get("releases")).isEmpty();
    }

    @Test
    @DisplayName("发布推进目录修订号：首次发布后当前可用返回该完整快照，修订号 1")
    void publishAdvancesRevision() throws Exception {
        long docId = prepareSingleRelease();
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("releaseRevision").asInt()).isEqualTo(1);
        assertThat(current.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("snapshot").get("segments").get(0)
                .get("translations").get(0).get("content").asText()).isEqualTo("hello");

        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releases")).hasSize(1);
        assertThat(catalog.body().get("releases").get(0).get("revoked").asBoolean()).isFalse();
        assertThat(catalog.body().get("releases").get(0).get("reason").isNull()).isTrue();
        assertThat(catalog.body().get("releases").get(0).get("revokedAt").isNull()).isTrue();
    }

    @Test
    @DisplayName("撤回当前版本：200，修订号加一；当前可用变 null；目录带原因与 UTC 时刻；按版本仍可取原始快照")
    void revokeCurrentVersion() throws Exception {
        long docId = prepareSingleRelease();
        ApiResult revoked = revoke(docId, 1, "内容有误", 1, newRequestId());
        assertThat(revoked.status()).isEqualTo(200);
        assertThat(revoked.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(revoked.body().get("reason").asText()).isEqualTo("内容有误");
        assertThat(revoked.body().get("revokedAt").asText()).isEqualTo("2026-09-23T10:00:00Z");
        assertThat(revoked.body().get("releaseRevision").asInt()).isEqualTo(2);

        // 当前可用：全部撤回 → 200、快照 null、修订号 2
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("snapshot").isNull());
        assertThat(current.body().get("releaseRevision").asInt()).isEqualTo(2);

        // 目录按发布编号排序，带撤回原因与 UTC 时刻
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releaseRevision").asInt()).isEqualTo(2);
        assertThat(catalog.body().get("releases")).hasSize(1);
        var entry = catalog.body().get("releases").get(0);
        assertThat(entry.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(entry.get("revoked").asBoolean()).isTrue();
        assertThat(entry.get("reason").asText()).isEqualTo("内容有误");
        assertThat(entry.get("revokedAt").asText()).isEqualTo("2026-09-23T10:00:00Z");

        // 按版本查询即使已撤回仍返回原始完整快照
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("segments").get(0).get("sourceText").asText()).isEqualTo("原文");

        // 快照正文未被删除或修改
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        assertThat(snapshots).isEqualTo(1);
        String snapshotJson = jdbc.queryForObject(
                "SELECT snapshot_json FROM release_snapshot WHERE document_id = ? AND published_version = 1",
                String.class, docId);
        assertThat(snapshotJson).contains("hello");
    }

    @Test
    @DisplayName("撤回非当前版本：当前指向不变，仍推进修订号；撤回最新版本后当前回退到历史快照原文")
    void revokeNonCurrentAndFallback() throws Exception {
        long docId = prepareThreeReleases();
        // 当前目录修订号 3；撤回中间版本 v2（非当前），期望修订号 3
        ApiResult revokeMiddle = revoke(docId, 2, "中间版作废", 3, newRequestId());
        assertThat(revokeMiddle.status()).isEqualTo(200);
        assertThat(revokeMiddle.body().get("releaseRevision").asInt()).isEqualTo(4);

        // 当前仍指向 v3
        ApiResult currentBefore = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(currentBefore.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(3);
        assertThat(currentBefore.body().get("releaseRevision").asInt()).isEqualTo(4);

        // 目录：v1 未撤回，v2 撤回，v3 未撤回，按编号排序
        ApiResult catalogBefore = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalogBefore.body().get("releaseRevision").asInt()).isEqualTo(4);
        assertThat(catalogBefore.body().get("releases")).hasSize(3);
        assertThat(catalogBefore.body().get("releases").get(0).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(catalogBefore.body().get("releases").get(0).get("revoked").asBoolean()).isFalse();
        assertThat(catalogBefore.body().get("releases").get(1).get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(catalogBefore.body().get("releases").get(1).get("revoked").asBoolean()).isTrue();
        assertThat(catalogBefore.body().get("releases").get(2).get("publishedVersion").asInt()).isEqualTo(3);
        assertThat(catalogBefore.body().get("releases").get(2).get("revoked").asBoolean()).isFalse();

        // 撤回当前最新 v3，期望修订号 4 → 修订号 5；当前可用直接回退为历史快照 v1（v2 已撤回）
        ApiResult revokeNew = revoke(docId, 3, "新版下线", 4, newRequestId());
        assertThat(revokeNew.status()).isEqualTo(200);
        assertThat(revokeNew.body().get("releaseRevision").asInt()).isEqualTo(5);

        ApiResult currentAfter = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(currentAfter.status()).isEqualTo(200);
        assertThat(currentAfter.body().get("releaseRevision").asInt()).isEqualTo(5);
        assertThat(currentAfter.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(1);
        // 直接使用历史快照：v1 中的源文与译文为发布时内容，不按当前草稿重新拼装
        assertThat(currentAfter.body().get("snapshot").get("segments").get(0)
                .get("sourceText").asText()).isEqualTo("原文");
        assertThat(currentAfter.body().get("snapshot").get("segments").get(0)
                .get("translations").get(0).get("content").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("回退快照不受术语更新影响：术语更新后当前可用仍返回固化旧术语的历史快照")
    void fallbackSnapshotUnaffectedByTermsUpdate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 草稿 3、发布 0 → 发布 v1（修订号 1）
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 再次发布 v2：术语改为 ML 后重新提交、批准（草稿：建 1→术语 2→提交 3→发布；术语 4→提交 5）
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 5, 1, newRequestId()).status()).isEqualTo(201);

        // 撤回 v2（期望修订号 2）→ 当前回退 v1，v1 固化术语版本 1 与 machine learning 规则
        assertThat(revoke(docId, 2, "下线", 2, newRequestId()).status()).isEqualTo(200);
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("snapshot").get("termVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("snapshot").get("terms").get(0)
                .get("requiredTranslation").asText()).isEqualTo("machine learning");
    }

    @Test
    @DisplayName("撤回失败分支：版本不存在 404；修订号过期 409；已撤回 409；空原因/非正版本 400")
    void revokeFailures() throws Exception {
        long docId = prepareTwoReleases();

        // 版本不存在（修订号正确为 2）→ 404
        ApiResult missing = revoke(docId, 99, "原因", 2, newRequestId());
        assertThat(missing.status()).isEqualTo(404);

        // 文档不存在 → 404
        ApiResult missingDoc = revoke(999999, 1, "原因", 0, newRequestId());
        assertThat(missingDoc.status()).isEqualTo(404);

        // 修订号过期 → 409
        ApiResult stale = revoke(docId, 1, "原因", 1, newRequestId());
        assertThat(stale.status()).isEqualTo(409);

        // 成功撤回 v1 → 修订号 3
        assertThat(revoke(docId, 1, "旧版作废", 2, newRequestId()).status()).isEqualTo(200);

        // 重复撤回同一版本 → 409
        ApiResult duplicate = revoke(docId, 1, "再次撤回", 3, newRequestId());
        assertThat(duplicate.status()).isEqualTo(409);

        // 参数非法：空原因 400、版本号非正 400
        String blankReason = "{\"requestId\":\"" + newRequestId() + "\",\"publishedVersion\":2,"
                + "\"reason\":\"   \",\"expectedReleaseRevision\":3}";
        assertThat(postJson("/api/documents/" + docId + "/releases/revoke", blankReason).status())
                .isEqualTo(400);
        String nonPositiveVersion = "{\"requestId\":\"" + newRequestId() + "\",\"publishedVersion\":0,"
                + "\"reason\":\"x\",\"expectedReleaseRevision\":3}";
        assertThat(postJson("/api/documents/" + docId + "/releases/revoke", nonPositiveVersion).status())
                .isEqualTo(400);

        // 任何失败不改变目录与历史：修订号仍为 3，仅一条撤回记录
        Integer revision = jdbc.queryForObject(
                "SELECT release_revision FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(revision).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_revocation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("失败不占键：同 requestId 先过期 409 后以正确修订号成功；撤回不改变草稿/译文/批准")
    void failureDoesNotOccupyKeyAndStateUntouched() throws Exception {
        long docId = prepareSingleRelease();
        String requestId = newRequestId();

        // 错误修订号先失败
        assertThat(revoke(docId, 1, "原因", 99, requestId).status()).isEqualTo(409);
        // 同键以正确参数重试成功（失败未占键）
        ApiResult ok = revoke(docId, 1, "原因", 1, requestId);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("releaseRevision").asInt()).isEqualTo(2);

        // 撤回不修改草稿、译文和批准状态
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("撤回幂等：同键同参重放首次结果（修订号已前进也不报过期）；改参 409")
    void revokeIdempotentReplay() throws Exception {
        long docId = prepareTwoReleases();
        String requestId = newRequestId();

        ApiResult first = revoke(docId, 1, "旧版作废", 2, requestId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("releaseRevision").asInt()).isEqualTo(3);

        // 同键同参重放：返回首次结果
        ApiResult replay = revoke(docId, 1, "旧版作废", 2, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body()).usingRecursiveComparison().isEqualTo(first.body());

        // 推进修订号（撤回 v2，修订号 3→4）后，再以同键同参重放：仍返回首次结果，不报过期
        assertThat(revoke(docId, 2, "新版下线", 3, newRequestId()).status()).isEqualTo(200);
        ApiResult replayAfterAdvance = revoke(docId, 1, "旧版作废", 2, requestId);
        assertThat(replayAfterAdvance.status()).isEqualTo(200);
        assertThat(replayAfterAdvance.body().get("releaseRevision").asInt()).isEqualTo(3);

        // 同键改参（原因不同）→ 409
        ApiResult changed = revoke(docId, 1, "不同原因", 2, requestId);
        assertThat(changed.status()).isEqualTo(409);

        // 仅两条撤回记录；去重记录为全部成功写操作：建文档、提交、批准、发布 v1、修订、提交、
        // 批准、发布 v2、两次撤回 = 10，重放与改参失败均不新增
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_revocation WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(2);
        Integer logs = jdbc.queryForObject("SELECT COUNT(*) FROM request_log", Integer.class);
        assertThat(logs).isEqualTo(10);
    }

    @Test
    @DisplayName("撤回后再次发布：编号单调递增不复用，执行原门禁，发布推进修订号，当前指向新版本")
    void republishAfterRevoke() throws Exception {
        long docId = prepareSingleRelease();
        // 撤回 v1（修订号 1→2），当前无可用版本
        assertThat(revoke(docId, 1, "作废", 1, newRequestId()).status()).isEqualTo(200);
        assertThat(getJson("/api/documents/" + docId + "/releases/current").body().get("snapshot").isNull())
                .isTrue();

        // 未重新走门禁（译文与批准仍在，源文未变）直接再次发布：编号为 2 而非复用 1
        ApiResult republish = publish(docId, 2, 1, newRequestId());
        assertThat(republish.status()).isEqualTo(201);
        assertThat(republish.body().get("publishedVersion").asInt()).isEqualTo(2);

        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.body().get("releaseRevision").asInt()).isEqualTo(3);
        assertThat(current.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(2);

        // v1 撤回记录仍在，v2 未撤回；目录三条以下版本（1、2）
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releases")).hasSize(2);
        assertThat(catalog.body().get("releases").get(0).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(catalog.body().get("releases").get(0).get("revoked").asBoolean()).isTrue();
        assertThat(catalog.body().get("releases").get(1).get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(catalog.body().get("releases").get(1).get("revoked").asBoolean()).isFalse();

        // 发布版本号单调：document.published_version=2，v1 快照仍可取
        Integer publishedVersion = jdbc.queryForObject(
                "SELECT published_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(publishedVersion).isEqualTo(2);
        assertThat(getJson("/api/documents/" + docId + "/releases/1").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("撤回后源文修订：新发布仍执行原门禁，缺有效批准时 422")
    void gateEnforcedOnRepublishAfterSourceChange() throws Exception {
        long docId = prepareSingleRelease();
        assertThat(revoke(docId, 1, "作废", 1, newRequestId()).status()).isEqualTo(200);

        // 修订源文：旧译文/批准失效，草稿 3、发布 1
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后原文\"}");
        ApiResult blocked = publish(docId, 3, 1, newRequestId());
        assertThat(blocked.status()).isEqualTo(422);

        // 门禁通过后发布 v2（修订源文后草稿 3、重新提交后草稿 4）
        submitTranslation(docId, "s1", "en", "alice", "updated", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult ok = publish(docId, 4, 1, newRequestId());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("publishedVersion").asInt()).isEqualTo(2);
    }
}
