package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 操作历史查询测试：使用可控时钟固定 UTC 时间，验证稳定排序、左闭右开区间与过滤语义。
 */
class HistoryQueryTest extends AbstractIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-01-01T00:00:01Z");
    private static final Instant T2 = Instant.parse("2026-01-01T00:00:02Z");
    private static final Instant T3 = Instant.parse("2026-01-01T00:00:03Z");
    private static final Instant T4 = Instant.parse("2026-01-01T00:00:04Z");

    @MockitoBean
    private Clock clock;

    @Test
    @DisplayName("历史查询：按时间稳定排序，区间左闭右开，支持操作类型过滤")
    void historyOrderingAndRange() throws Exception {
        when(clock.instant()).thenReturn(T1, T2, T3);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());       // T1
        withdraw(docId, "s1", "en", "carol", newRequestId());       // T2
        putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"修订后\"}"); // T3

        ApiResult all = getJson("/api/documents/" + docId + "/history");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().get("records")).hasSize(3);
        var first = all.body().get("records").get(0);
        var second = all.body().get("records").get(1);
        var third = all.body().get("records").get(2);
        assertThat(first.get("operation").asText()).isEqualTo("TRANSLATION_APPROVED");
        assertThat(first.get("occurredAt").asText()).isEqualTo("2026-01-01T00:00:01Z");
        assertThat(second.get("operation").asText()).isEqualTo("APPROVAL_WITHDRAWN");
        assertThat(second.get("occurredAt").asText()).isEqualTo("2026-01-01T00:00:02Z");
        assertThat(third.get("operation").asText()).isEqualTo("SOURCE_REVISED");
        assertThat(third.get("occurredAt").asText()).isEqualTo("2026-01-01T00:00:03Z");
        assertThat(first.get("historyId").asLong()).isLessThan(second.get("historyId").asLong());
        assertThat(second.get("historyId").asLong()).isLessThan(third.get("historyId").asLong());

        // 左闭右开：[T2, T3) 只含 T2
        ApiResult range = getJson("/api/documents/" + docId + "/history?from=" + T2 + "&to=" + T3);
        assertThat(range.body().get("records")).hasSize(1);
        assertThat(range.body().get("records").get(0).get("operation").asText())
                .isEqualTo("APPROVAL_WITHDRAWN");

        // [T1, T4) 含全部三条
        ApiResult wide = getJson("/api/documents/" + docId + "/history?from=" + T1 + "&to=" + T4);
        assertThat(wide.body().get("records")).hasSize(3);

        // to 为开区间端点：to=T1 不含 T1 记录
        ApiResult empty = getJson("/api/documents/" + docId + "/history?to=" + T1);
        assertThat(empty.body().get("records")).isEmpty();

        // 按操作类型与段落过滤
        ApiResult filtered = getJson("/api/documents/" + docId
                + "/history?operation=SOURCE_REVISED&segmentId=s1");
        assertThat(filtered.body().get("records")).hasSize(1);
        assertThat(filtered.body().get("records").get(0).get("detail").get("sourceVersion").asInt())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("历史查询：非法时间参数 400；未知操作类型 400；文档不存在 404")
    void historyQueryValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult badTime = getJson("/api/documents/" + docId + "/history?from=not-a-time");
        assertThat(badTime.status()).isEqualTo(400);
        ApiResult badOperation = getJson("/api/documents/" + docId + "/history?operation=NOPE");
        assertThat(badOperation.status()).isEqualTo(400);
        ApiResult missingDoc = getJson("/api/documents/999999/history");
        assertThat(missingDoc.status()).isEqualTo(404);
    }

    @Test
    @DisplayName("历史不可变：后续操作不改写已记录的业务证据")
    void historyIsImmutableEvidence() throws Exception {
        when(clock.instant()).thenReturn(T1, T2);
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\",\"placeholders\":[\"{1}\"]}]");
        submitTranslation(docId, "s1", "en", "alice", "hello {1}", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());   // T1
        // 后续操作：占位符修订使批准失效
        revisePlaceholders(docId, "s1", "[\"{2}\"]", newRequestId()); // T2

        ApiResult history = getJson("/api/documents/" + docId + "/history?operation=TRANSLATION_APPROVED");
        assertThat(history.body().get("records")).hasSize(1);
        var record = history.body().get("records").get(0);
        assertThat(record.get("actor").asText()).isEqualTo("bob");
        assertThat(record.get("detail").get("translationVersion").asInt()).isEqualTo(1);
        assertThat(record.get("detail").get("sourceVersion").asInt()).isEqualTo(1);
        assertThat(record.get("occurredAt").asText()).isEqualTo("2026-01-01T00:00:01Z");

        ApiResult placeholderHistory = getJson("/api/documents/" + docId
                + "/history?operation=PLACEHOLDERS_REVISED");
        assertThat(placeholderHistory.body().get("records")).hasSize(1);
        var revised = placeholderHistory.body().get("records").get(0);
        assertThat(revised.get("detail").get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(revised.get("detail").get("placeholders").get(0).asText()).isEqualTo("{2}");
        assertThat(revised.get("segmentId").asText()).isEqualTo("s1");
        assertThat(revised.get("language").isNull()).isTrue();
        assertThat(revised.get("actor").isNull()).isTrue();
    }
}
