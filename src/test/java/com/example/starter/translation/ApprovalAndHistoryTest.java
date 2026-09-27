package com.example.starter.translation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 批量批准全成全败、撤回、操作历史（UTC 左闭右开、可控时钟、稳定排序、证据固化）
 * 与幂等边界测试。
 */
class ApprovalAndHistoryTest extends AbstractIntegrationTest {

    /** 替换统一时钟为可控固定时钟，验证历史时间精度与区间边界。 */
    @MockitoBean
    private Clock clock;

    /** 将统一时钟固定到给定 UTC 瞬时。 */
    private void freezeAt(String utc) {
        when(clock.instant()).thenReturn(Instant.parse(utc));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    /** 未显式冻结时也提供有效固定时钟，避免历史写入读取到 null 时间。 */
    @BeforeEach
    void pinDefaultClock() {
        freezeAt("2026-09-28T10:00:00Z");
    }

    private long prepareTwoSegments(String... requiredPlaceholderPerSegment) throws Exception {
        StringBuilder segments = new StringBuilder("[");
        for (int i = 0; i < requiredPlaceholderPerSegment.length; i++) {
            if (i > 0) {
                segments.append(',');
            }
            segments.append("{\"segmentId\":\"s").append(i + 1).append("\",\"sourceText\":\"原文")
                    .append(i + 1).append(' ').append(requiredPlaceholderPerSegment[i])
                    .append("\",\"placeholders\":[\"").append(requiredPlaceholderPerSegment[i]).append("\"]}");
        }
        segments.append(']');
        return createDocument(newRequestId(), "[\"en\",\"ja\"]", segments.toString());
    }

    @Test
    @DisplayName("批量批准：全部条目有效时整批成功；任一条目不完整整次 422 且不改变任何批准状态")
    void batchApproveAllOrNothing() throws Exception {
        long docId = prepareTwoSegments("{a}", "{b}");
        submitTranslation(docId, "s1", "en", "alice", "en {a}", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "ja {a}", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "en {b}", 1, newRequestId());
        submitTranslation(docId, "s2", "ja", "carol", "ja {b}", 1, newRequestId());

        String items = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"ja\",\"translationVersion\":1}]";
        ApiResult ok = batchApprove(docId, "dave", items, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("approvedCount").asInt()).isEqualTo(2);
        assertThat(ok.body().get("approvals")).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 第二批含一条不完整（s1/ja 由作者 carol 自审）：整批 422，s2/en 也不得被批准
        AtomicReference<String> failingBody = new AtomicReference<>();
        String badItems = "[{\"segmentId\":\"s1\",\"language\":\"ja\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1}]";
        ApiResult failed = batchApprove(docId, "carol", badItems, newRequestId());
        failingBody.set(badItems);
        assertThat(failed.status()).isEqualTo(422);
        assertThat(failed.body().get("message").asText()).contains("s1/ja");
        // 只有第一批的两条批准；第二批零落库
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ? AND reviewer = 'carol'",
                Integer.class, docId)).isZero();
        assertThat(failingBody.get()).contains("s2");
    }

    @Test
    @DisplayName("批量批准：同批次段落语言重复条目 422 整批不变；空批次 400")
    void batchApproveDuplicateAndEmpty() throws Exception {
        long docId = prepareTwoSegments("{a}");
        submitTranslation(docId, "s1", "en", "alice", "en {a}", 1, newRequestId());

        String dupItems = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s1\",\"language\":\"EN\",\"translationVersion\":1}]";
        ApiResult dup = batchApprove(docId, "dave", dupItems, newRequestId());
        assertThat(dup.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isZero();

        ApiResult empty = batchApprove(docId, "dave", "[]", newRequestId());
        assertThat(empty.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("撤回：已批准可撤回并使发布 422；不存在批准 404；撤回后可重新批准")
    void withdrawApproval() throws Exception {
        long docId = prepareTwoSegments("{a}");
        submitTranslation(docId, "s1", "en", "alice", "en {a}", 1, newRequestId());
        submitTranslation(docId, "s1", "ja", "carol", "ja {a}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        approve(docId, "s1", "ja", "dave", 1, newRequestId());

        ApiResult withdrawn = withdraw(docId, "s1", "en", "bob", newRequestId());
        assertThat(withdrawn.status()).isEqualTo(200);
        assertThat(withdrawn.body().get("reviewer").asText()).isEqualTo("bob");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);

        // 缺少撤回的批准，发布 422
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 重复撤回 404
        assertThat(withdraw(docId, "s1", "en", "bob", newRequestId()).status()).isEqualTo(404);

        // 重新批准后发布成功
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("操作历史：按提交顺序稳定排序、字段固化、UTC 微秒精度；区间左闭右开；读取不改状态")
    void historyOrderingAndUtcRange() throws Exception {
        freezeAt("2026-09-28T10:00:00.000000Z");
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"{a}\",\"placeholders\":[\"{a}\"]}]");
        freezeAt("2026-09-28T10:00:01.123456Z");
        submitTranslation(docId, "s1", "en", "alice", "x {a}", 1, newRequestId());
        freezeAt("2026-09-28T10:00:02.500000Z");
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        freezeAt("2026-09-28T10:00:03.000000Z");
        publish(docId, 2, 0, newRequestId());

        ApiResult all = getJson("/api/documents/" + docId + "/history");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().get("count").asInt()).isEqualTo(4);
        List<String> types = new ArrayList<>();
        for (var entry : all.body().get("entries")) {
            types.add(entry.get("operationType").asText());
        }
        assertThat(types).containsExactly("CREATE_DOCUMENT", "SUBMIT_TRANSLATION",
                "APPROVE_TRANSLATION", "PUBLISH");
        // sequence 单调递增即提交顺序
        for (int i = 0; i < 4; i++) {
            assertThat(all.body().get("entries").get(i).get("sequence").asLong()).isEqualTo(i + 1);
        }
        // 微秒精度时间原样返回，null 字段（文档级操作 segmentId/actor）保持 null
        assertThat(all.body().get("entries").get(1).get("occurredAtUtc").asText())
                .isEqualTo("2026-09-28T10:00:01.123456Z");
        assertThat(all.body().get("entries").get(1).get("actor").asText()).isEqualTo("alice");
        assertThat(all.body().get("entries").get(3).get("segmentId").isNull()).isTrue();
        assertThat(all.body().get("entries").get(3).get("actor").isNull()).isTrue();
        assertThat(all.body().get("entries").get(1).get("fingerprint").asText()).matches("[0-9a-f]{64}");
        // 明细字段固化
        assertThat(all.body().get("entries").get(1).get("detail").get("actualPlaceholders").toString())
                .isEqualTo("[\"{a}\"]");

        // 左闭：from 等于提交时刻，该记录包含；右开：to 等于提交时刻，该记录排除
        ApiResult leftClosed = getJson("/api/documents/" + docId
                + "/history?from=2026-09-28T10:00:02.500000Z&to=2026-09-28T10:00:04Z");
        assertThat(leftClosed.body().get("count").asInt()).isEqualTo(2);
        assertThat(leftClosed.body().get("entries").get(0).get("operationType").asText())
                .isEqualTo("APPROVE_TRANSLATION");

        ApiResult rightOpen = getJson("/api/documents/" + docId
                + "/history?from=2026-09-28T10:00:00Z&to=2026-09-28T10:00:03.000000Z");
        assertThat(rightOpen.body().get("count").asInt()).isEqualTo(3);

        // 读取历史不改变状态：再查一次结果一致，历史条数不增加
        ApiResult again = getJson("/api/documents/" + docId + "/history");
        assertThat(again.body().get("count").asInt()).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM operation_history WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(4);

        // 非法时间 400；from >= to 400
        assertThat(getJson("/api/documents/" + docId + "/history?from=not-a-time").status()).isEqualTo(400);
        assertThat(getJson("/api/documents/" + docId
                + "/history?from=2026-09-28T11:00:00Z&to=2026-09-28T10:00:00Z").status()).isEqualTo(400);
    }

    @Test
    @DisplayName("历史证据不被后续操作覆盖：撤回与再批准只追加，旧证据字段与时间不变")
    void historyIsAppendOnly() throws Exception {
        freezeAt("2026-09-28T10:00:00Z");
        long docId = prepareTwoSegments("{a}");
        submitTranslation(docId, "s1", "en", "alice", "en {a}", 1, newRequestId());
        freezeAt("2026-09-28T10:00:05Z");
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        String firstFingerprint = getJson("/api/documents/" + docId + "/history")
                .body().get("entries").get(2).get("fingerprint").asText();

        freezeAt("2026-09-28T10:00:09Z");
        withdraw(docId, "s1", "en", "bob", newRequestId());
        freezeAt("2026-09-28T10:00:10Z");
        approve(docId, "s1", "en", "erin", 1, newRequestId());

        ApiResult history = getJson("/api/documents/" + docId + "/history");
        assertThat(history.body().get("entries").get(2).get("fingerprint").asText())
                .isEqualTo(firstFingerprint);
        assertThat(history.body().get("entries").get(2).get("occurredAtUtc").asText())
                .isEqualTo("2026-09-28T10:00:05Z");
        List<String> types = new ArrayList<>();
        for (var entry : history.body().get("entries")) {
            types.add(entry.get("operationType").asText());
        }
        assertThat(types).containsExactly("CREATE_DOCUMENT", "SUBMIT_TRANSLATION",
                "APPROVE_TRANSLATION", "WITHDRAW_APPROVAL", "APPROVE_TRANSLATION");
    }

    @Test
    @DisplayName("批量批准与撤回幂等：同参重放原结果不重复变更，异参 409，失败不占键")
    void batchAndWithdrawIdempotency() throws Exception {
        long docId = prepareTwoSegments("{a}", "{b}");
        submitTranslation(docId, "s1", "en", "alice", "en {a}", 1, newRequestId());
        submitTranslation(docId, "s2", "en", "alice", "en {b}", 1, newRequestId());

        String requestId = newRequestId();
        String items = "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1},"
                + "{\"segmentId\":\"s2\",\"language\":\"en\",\"translationVersion\":1}]";
        ApiResult first = batchApprove(docId, "dave", items, requestId);
        assertThat(first.status()).isEqualTo(200);
        ApiResult replay = batchApprove(docId, "dave", items, requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("approvedCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 同键异参 409
        ApiResult conflict = batchApprove(docId, "dave",
                "[{\"segmentId\":\"s1\",\"language\":\"en\",\"translationVersion\":1}]", requestId);
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：先用该键触发 422（作者自审），再以同键撤回成功
        String failKey = newRequestId();
        ApiResult failed = batchApprove(docId, "alice", items, failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult withdraw = withdraw(docId, "s1", "en", "dave", failKey);
        assertThat(withdraw.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
    }
}
