package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 发布快照撤回、当前可用版本回退与发布目录的主流程、失败分支及幂等测试。
 */
class WithdrawApiTest extends AbstractIntegrationTest {

    /** 建文档、提交译文并批准后发布 v1，返回 documentId；发布后草稿版本 2、发布版本 1、目录修订号 1。 */
    private long createAndPublishV1(String content) throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", content, 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);
        assertThat(published.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(published.body().get("releaseRevision").asInt()).isEqualTo(1);
        return docId;
    }

    @Test
    @DisplayName("撤回主流程：撤回后当前可用为 null，按版本查询仍返回原始快照，目录显示撤回原因与 UTC 时刻")
    void withdrawMainFlow() throws Exception {
        long docId = createAndPublishV1("hello");

        ApiResult currentBefore = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(currentBefore.status()).isEqualTo(200);
        assertThat(currentBefore.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(currentBefore.body().get("releaseRevision").asInt()).isEqualTo(1);
        assertThat(currentBefore.body().get("snapshot").get("publishedVersion").asInt()).isEqualTo(1);

        ApiResult withdrawn = withdraw(docId, 1, "内容过期", 1, newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(withdrawn.body().get("releaseRevision").asInt()).isEqualTo(2);
        assertThat(withdrawn.body().get("withdrawn").asBoolean()).isTrue();
        String withdrawnAt = withdrawn.body().get("withdrawnAt").asText();
        assertThat(OffsetDateTime.parse(withdrawnAt).getOffset()).isEqualTo(ZoneOffset.UTC);

        // 全部撤回：当前可用返回 200、快照 null
        ApiResult currentAfter = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(currentAfter.status()).isEqualTo(200);
        assertThat(currentAfter.body().get("releaseRevision").asInt()).isEqualTo(2);
        assertThat(currentAfter.body().get("publishedVersion").isNull()).isTrue();
        assertThat(currentAfter.body().get("snapshot").isNull()).isTrue();

        // 按版本查询即使已撤回也返回原始快照
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("hello");

        // 目录与撤回历史：按发布编号排序，含撤回原因与 UTC 时刻
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.body().get("releaseRevision").asInt()).isEqualTo(2);
        assertThat(catalog.body().get("releases")).hasSize(1);
        var entry = catalog.body().get("releases").get(0);
        assertThat(entry.get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(entry.get("withdrawn").asBoolean()).isTrue();
        assertThat(entry.get("withdrawReason").asText()).isEqualTo("内容过期");
        assertThat(entry.get("withdrawnAt").asText()).isEqualTo(withdrawnAt);
    }

    @Test
    @DisplayName("撤回非当前版本：当前指向不变但仍推进修订号；回退直接使用历史快照，不按当前源文重新拼装")
    void withdrawNonCurrentAndRollbackToHistoricalSnapshot() throws Exception {
        long docId = createAndPublishV1("v1-content");

        // 修订源文并发布 v2：草稿版本 4、发布版本 2、目录修订号 2
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"新原文\"}");
        submitTranslation(docId, "s1", "en", "alice", "v2-content", 2, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult second = publish(docId, 4, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("releaseRevision").asInt()).isEqualTo(2);

        // 撤回非当前版本 v1：当前仍指向 v2，修订号推进为 3
        ApiResult withdrawV1 = withdraw(docId, 1, "旧版本下线", 2, newRequestId());
        assertThat(withdrawV1.status()).isEqualTo(200);
        assertThat(withdrawV1.body().get("releaseRevision").asInt()).isEqualTo(3);
        ApiResult currentAfterV1Withdraw = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(currentAfterV1Withdraw.body().get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(currentAfterV1Withdraw.body().get("releaseRevision").asInt()).isEqualTo(3);
        assertThat(currentAfterV1Withdraw.body().get("snapshot").get("segments").get(0)
                .get("translations").get(0).get("content").asText()).isEqualTo("v2-content");

        // 撤回当前版本 v2：当前回退到 v1 的历史快照（旧源文、旧译文），不按当前草稿重新拼装
        ApiResult withdrawV2 = withdraw(docId, 2, "发布有误", 3, newRequestId());
        assertThat(withdrawV2.status()).isEqualTo(200);
        assertThat(withdrawV2.body().get("releaseRevision").asInt()).isEqualTo(4);
        ApiResult rolledBack = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(rolledBack.status()).isEqualTo(200);
        assertThat(rolledBack.body().get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(rolledBack.body().get("releaseRevision").asInt()).isEqualTo(4);
        var snapshotSegment = rolledBack.body().get("snapshot").get("segments").get(0);
        assertThat(snapshotSegment.get("sourceText").asText()).isEqualTo("原文");
        assertThat(snapshotSegment.get("translations").get(0).get("content").asText())
                .isEqualTo("v1-content");

        // 目录按发布编号排序，v1 未撤回、v2 已撤回
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releaseRevision").asInt()).isEqualTo(4);
        assertThat(catalog.body().get("releases")).hasSize(2);
        assertThat(catalog.body().get("releases").get(0).get("publishedVersion").asInt()).isEqualTo(1);
        assertThat(catalog.body().get("releases").get(0).get("withdrawn").asBoolean()).isFalse();
        assertThat(catalog.body().get("releases").get(0).get("withdrawReason").isNull()).isTrue();
        assertThat(catalog.body().get("releases").get(1).get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(catalog.body().get("releases").get(1).get("withdrawn").asBoolean()).isTrue();
        assertThat(catalog.body().get("releases").get(1).get("withdrawReason").asText())
                .isEqualTo("发布有误");
    }

    @Test
    @DisplayName("撤回失败分支：版本不存在 404、修订号过期或已撤回 409、参数非法 400；失败不改变目录与去重记录")
    void withdrawFailures() throws Exception {
        long docId = createAndPublishV1("hello");

        // 版本不存在 404；文档不存在 404
        assertThat(withdraw(docId, 99, "原因", 1, newRequestId()).status()).isEqualTo(404);
        assertThat(withdraw(999999, 1, "原因", 0, newRequestId()).status()).isEqualTo(404);

        // 修订号过期 409
        String staleKey = newRequestId();
        assertThat(withdraw(docId, 1, "原因", 0, staleKey).status()).isEqualTo(409);

        // 参数非法 400：空原因、缺 requestId、负修订号
        ApiResult blankReason = postJson("/api/documents/" + docId + "/releases/1/withdraw",
                "{\"requestId\":\"" + newRequestId() + "\",\"reason\":\"  \",\"expectedReleaseRevision\":1}");
        assertThat(blankReason.status()).isEqualTo(400);
        ApiResult noRequestId = postJson("/api/documents/" + docId + "/releases/1/withdraw",
                "{\"reason\":\"原因\",\"expectedReleaseRevision\":1}");
        assertThat(noRequestId.status()).isEqualTo(400);
        ApiResult negativeRevision = postJson("/api/documents/" + docId + "/releases/1/withdraw",
                "{\"requestId\":\"" + newRequestId() + "\",\"reason\":\"原因\",\"expectedReleaseRevision\":-1}");
        assertThat(negativeRevision.status()).isEqualTo(400);

        // 失败不改变目录、历史与去重记录
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releaseRevision").asInt()).isEqualTo(1);
        assertThat(catalog.body().get("releases").get(0).get("withdrawn").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, staleKey)).isZero();

        // 失败不占键：同一 requestId 修正修订号后成功
        ApiResult retried = withdraw(docId, 1, "修正后撤回", 1, staleKey);
        assertThat(retried.status()).isEqualTo(200);
        assertThat(retried.body().get("releaseRevision").asInt()).isEqualTo(2);

        // 已撤回：以当前修订号重复撤回仍 409
        assertThat(withdraw(docId, 1, "再次撤回", 2, newRequestId()).status()).isEqualTo(409);
    }

    @Test
    @DisplayName("撤回幂等：同键同参重放首次结果（修订号已前进也不报过期），同键异参 409")
    void withdrawIdempotency() throws Exception {
        long docId = createAndPublishV1("hello");

        String requestId = newRequestId();
        ApiResult first = withdraw(docId, 1, "内容过期", 1, requestId);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("releaseRevision").asInt()).isEqualTo(2);

        // 撤回不修改草稿/译文/批准状态：以原草稿版本再次发布成功，发布编号递增为 2 不复用
        ApiResult republished = publish(docId, 2, 1, newRequestId());
        assertThat(republished.status()).isEqualTo(201);
        assertThat(republished.body().get("publishedVersion").asInt()).isEqualTo(2);
        assertThat(republished.body().get("releaseRevision").asInt()).isEqualTo(3);

        // 同键同参：即使目录修订号已前进到 3，仍重放首次结果（修订号 2），不报过期
        ApiResult replay = withdraw(docId, 1, "内容过期", 1, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("releaseRevision").asInt()).isEqualTo(2);
        assertThat(replay.body().get("withdrawnAt").asText())
                .isEqualTo(first.body().get("withdrawnAt").asText());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_log WHERE request_id = ?",
                Integer.class, requestId)).isEqualTo(1);

        // 同键异参 409
        ApiResult conflict = withdraw(docId, 1, "另一个原因", 1, requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 重放未产生额外撤回：目录修订号仍为 3，仅 v1 撤回、v2 为当前可用
        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.body().get("releaseRevision").asInt()).isEqualTo(3);
        assertThat(catalog.body().get("releases")).hasSize(2);
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.body().get("publishedVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("从未发布：当前可用返回 200、快照 null、修订号 0，目录为空；文档不存在 404")
    void neverPublished() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");

        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("releaseRevision").asInt()).isEqualTo(0);
        assertThat(current.body().get("publishedVersion").isNull()).isTrue();
        assertThat(current.body().get("snapshot").isNull()).isTrue();

        ApiResult catalog = getJson("/api/documents/" + docId + "/releases");
        assertThat(catalog.status()).isEqualTo(200);
        assertThat(catalog.body().get("releaseRevision").asInt()).isEqualTo(0);
        assertThat(catalog.body().get("releases")).isEmpty();

        assertThat(getJson("/api/documents/999999/releases/current").status()).isEqualTo(404);
        assertThat(getJson("/api/documents/999999/releases").status()).isEqualTo(404);
    }

    @Test
    @DisplayName("术语更新不影响历史快照：当前可用与按版本查询均返回发布时固化的快照")
    void termUpdateDoesNotAffectSnapshots() throws Exception {
        long docId = createAndPublishV1("hello");

        ApiResult updated = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"原文\",\"language\":\"en\",\"requiredTranslation\":\"original\"}]",
                newRequestId());
        assertThat(updated.status()).isEqualTo(201);

        // 当前可用直接返回历史快照：术语版本仍为发布时的 0，不按当前术语重新拼装
        ApiResult current = getJson("/api/documents/" + docId + "/releases/current");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("snapshot").get("termVersion").asInt()).isEqualTo(0);
        assertThat(current.body().get("snapshot").get("terms")).isEmpty();

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(0);
    }
}
