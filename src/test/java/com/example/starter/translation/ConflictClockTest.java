package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可控时钟下的 UTC 区间边界与时态语义测试：左闭右开、未来/过去区间、区间自然到期后资格恢复。
 * 时钟仅在单个用例内替换，基类 @AfterEach 统一恢复系统 UTC 时钟。
 */
class ConflictClockTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant T_FROM = Instant.parse("2026-06-01T12:00:00Z");
    private static final Instant T_TO = Instant.parse("2026-06-10T12:00:00Z");

    private void setClock(Instant instant) {
        timeProvider.setClock(Clock.fixed(instant, ZoneOffset.UTC));
    }

    private long readyDocument() throws Exception {
        setClock(T0);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        return docId;
    }

    @Test
    @DisplayName("左闭：时钟到达 validFrom 时刻冲突生效，评审被标记且批准被拒（403）")
    void leftBoundaryInclusive() throws Exception {
        long docId = readyDocument();

        // 起点前一刻：不生效
        setClock(T_FROM.minusSeconds(1));
        assertThat(declareConflict(docId, "bob", "en", T_FROM.toString(), T_TO.toString(), "边界",
                newRequestId()).body().get("declarationId").asLong()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND review_status = 'CONFLICTED'",
                Integer.class, docId)).isZero();
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(200);

        // 到达起点（含）：冲突生效，新的评审被拒
        setClock(T_FROM);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(403);
        // 诊断确认有效
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/conflicts/diagnostics");
        assertThat(diagnostics.body().get("activeConflicts")).hasSize(1);
    }

    @Test
    @DisplayName("右开：时钟到达 validTo 时刻冲突不再生效，诊断无有效冲突，可提交评审并发布")
    void rightBoundaryExclusive() throws Exception {
        long docId = readyDocument();
        setClock(T_FROM);
        long declarationId = declareConflict(docId, "bob", "en", T_FROM.toString(), T_TO.toString(), "边界",
                newRequestId()).body().get("declarationId").asLong();
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM approval WHERE document_id = ?", String.class, docId))
                .isEqualTo("CONFLICTED");
        // 区间内发布：法定人数不足
        assertThat(publish(docId, 2, 0, newRequestId()).body().get("error").asText())
                .isEqualTo("QUORUM_NOT_MET");

        // 到达终点（不含）：区间自然失效，评审重新计入法定人数
        setClock(T_TO);
        ApiResult diagnostics = getJson("/api/documents/" + docId + "/conflicts/diagnostics");
        assertThat(diagnostics.body().get("activeConflicts")).isEmpty();
        assertThat(diagnostics.body().get("effectiveApprovals").asInt()).isEqualTo(1);
        assertThat(diagnostics.body().get("missingApprovals").asInt()).isZero();
        // 存储标记保留为历史证据（未解除），但发布按当前有效冲突重算后成功
        assertThat(jdbc.queryForObject(
                "SELECT review_status FROM approval WHERE document_id = ?", String.class, docId))
                .isEqualTo("CONFLICTED");
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        // 声明仍为 ACTIVE（自然到期不等于解除），但快照记录其发布时无效
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("conflicts").get(0).get("status").asText()).isEqualTo("ACTIVE");
        assertThat(release.body().get("conflicts").get(0).get("effectiveAtPublish").asBoolean()).isFalse();
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("effectiveConflictDeclarationId").isNull()).isTrue();
        // 到期后可登记相邻新区间（与旧区间端点相邻不重叠）
        setClock(T_TO);
        assertThat(declareConflict(docId, "bob", "en", T_TO.toString(),
                T_TO.plusSeconds(3600).toString(), "下一区间", newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("区间覆盖时段内登记冲突立即标记已有评审；时钟推进越过终点后资格按当前状态重算恢复，无需解除")
    void conflictExpiresNaturallyAndQuorumRecomputed() throws Exception {
        long docId = readyDocument();
        setClock(T_FROM.plusSeconds(60));
        long declarationId = declareConflict(docId, "bob", "en", T_FROM.toString(), T_TO.toString(), "临时冲突",
                newRequestId()).body().get("declarationId").asLong();

        // 登记后历史为 VALID -> CONFLICTED
        ApiResult during = getJson("/api/documents/" + docId + "/conflicts/reviews/history");
        assertThat(during.body()).hasSize(2);
        assertThat(during.body().get(1).get("reviewStatus").asText()).isEqualTo("CONFLICTED");
        assertThat(during.body().get(1).get("conflictDeclarationId").asLong()).isEqualTo(declarationId);

        // 推进到区间外：不产生新历史记录（自然到期非操作事件），但诊断与发布按恢复后的资格计算
        setClock(T_TO.plusSeconds(1));
        assertThat(getJson("/api/documents/" + docId + "/conflicts/reviews/history").body()).hasSize(2);
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);
    }
}
