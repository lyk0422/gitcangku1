package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数值词元校核的真实 H2 集成测试：合成源值 1234.5 米，正文词元 1.234,50 且单位米通过；
 * 12.34,50 格式错误 400，1.234,51 格式合法但值不符 422；覆盖登记、区间、修订、
 * 发布快照冻结、幂等（十进制按值）与并发提交。
 */
class NumericTokenApiTest extends AbstractIntegrationTest {

    private static String sourceToken(String key, String value, String unit) {
        return "{\"tokenKey\":\"" + key + "\",\"value\":\"" + value + "\",\"unit\":\"" + unit + "\"}";
    }

    private static String targetToken(String key, String targetValue, String unit, int start, int end) {
        return "{\"tokenKey\":\"" + key + "\",\"targetValue\":\"" + targetValue + "\",\"unit\":\""
                + unit + "\",\"start\":" + start + ",\"end\":" + end + "}";
    }

    private long createTokenDocument(String requestId, String sourceText, String sourceTokensJson)
            throws Exception {
        return createDocument(requestId, "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"" + sourceText + "\",\"numericTokens\":"
                        + sourceTokensJson + "}]");
    }

    private ApiResult submitWithTokens(long docId, String actor, String content, int sourceVersion,
                                       String targetTokensJson, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"content\":\"" + content
                + "\",\"sourceVersion\":" + sourceVersion + ",\"numericTokens\":" + targetTokensJson + "}";
        return putJson("/api/documents/" + docId + "/segments/s1/translations/en", body, actor);
    }

    @Test
    @DisplayName("合成场景：源值 1234.5 米，正文词元 1.234,50 且单位米通过，快照冻结原值与目标文字")
    void syntheticScenarioPassAndSnapshotFreezes() throws Exception {
        long docId = createTokenDocument(newRequestId(), "深度 1234.5 米", "[" + sourceToken("n1", "1234.5", "米") + "]");
        String requestId = newRequestId();
        ApiResult ok = submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 3, 11) + "]", requestId);
        assertThat(ok.status()).isEqualTo(200);

        // 同键同参重放：译文版本不递增
        ApiResult replay = submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 3, 11) + "]", requestId);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.body().get("translationVersion").asInt()).isEqualTo(1);

        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 2, 0, newRequestId()).status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("numericTokens")).hasSize(1);
        assertThat(segment.get("numericTokens").get(0).get("tokenKey").asText()).isEqualTo("n1");
        assertThat(segment.get("numericTokens").get(0).get("value").asText()).isEqualTo("1234.5");
        assertThat(segment.get("numericTokens").get(0).get("unit").asText()).isEqualTo("米");
        var target = segment.get("translations").get(0).get("numericTokens").get(0);
        assertThat(target.get("targetValue").asText()).isEqualTo("1.234,50");
        assertThat(target.get("unit").asText()).isEqualTo("米");
        assertThat(target.get("start").asInt()).isEqualTo(3);
        assertThat(target.get("end").asInt()).isEqualTo(11);
    }

    @Test
    @DisplayName("合成失败分支：12.34,50 格式错误 400；1.234,51 格式合法但值不符 422；缺词元 422")
    void syntheticFailures() throws Exception {
        long docId = createTokenDocument(newRequestId(), "深度 1234.5 米", "[" + sourceToken("n1", "1234.5", "米") + "]");

        ApiResult badFormat = submitWithTokens(docId, "alice", "深度 12.34,50 米", 1,
                "[" + targetToken("n1", "12.34,50", "米", 3, 11) + "]", newRequestId());
        assertThat(badFormat.status()).isEqualTo(400);

        ApiResult wrongValue = submitWithTokens(docId, "alice", "深度 1.234,51 米", 1,
                "[" + targetToken("n1", "1.234,51", "米", 3, 11) + "]", newRequestId());
        assertThat(wrongValue.status()).isEqualTo(422);

        ApiResult missing = submitTranslation(docId, "s1", "en", "alice", "深度 1.234,50 米", 1, newRequestId());
        assertThat(missing.status()).isEqualTo(422);

        ApiResult wrongUnit = submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "千克", 3, 11) + "]", newRequestId());
        assertThat(wrongUnit.status()).isEqualTo(422);

        ApiResult extraKey = submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 3, 11) + ","
                        + targetToken("n2", "1.234,50", "米", 3, 11) + "]", newRequestId());
        assertThat(extraKey.status()).isEqualTo(422);

        // 全部失败不落译文与词元
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_numeric_token WHERE document_id = ?", Integer.class, docId))
                .isZero();
    }

    @Test
    @DisplayName("区间校核：文字不一致、越界、重叠均 422；补充字符按 1 个码点不偏移")
    void rangeValidation() throws Exception {
        long docId = createTokenDocument(newRequestId(), "深度 1234.5 米", "[" + sourceToken("n1", "1234.5", "米") + "]");

        ApiResult mismatch = submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 3, 10) + "]", newRequestId());
        assertThat(mismatch.status()).isEqualTo(422);

        ApiResult outOfBounds = submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 3, 99) + "]", newRequestId());
        assertThat(outOfBounds.status()).isEqualTo(422);

        // 补充字符 🎉 计 1 个码点：词元位于码点区间 [1, 9)
        ApiResult emoji = submitWithTokens(docId, "alice", "🎉1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 1, 9) + "]", newRequestId());
        assertThat(emoji.status()).isEqualTo(200);

        // 重叠：两个词元指向同一区间
        long docId2 = createTokenDocument(newRequestId(), "双值 1.5 米",
                "[" + sourceToken("a", "1.5", "米") + "," + sourceToken("b", "1.5", "米") + "]");
        ApiResult overlap = submitWithTokens(docId2, "alice", "1,5", 1,
                "[" + targetToken("a", "1,5", "米", 0, 3) + "," + targetToken("b", "1,5", "米", 0, 3) + "]",
                newRequestId());
        assertThat(overlap.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("源词元登记：tokenKey 重复 422；超过 5 个 400；原值非法十进制 400；普通无词元源段不变")
    void sourceTokenRegistration() throws Exception {
        ApiResult duplicate = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"x\",\"numericTokens\":["
                        + sourceToken("n1", "1.5", "米") + "," + sourceToken("n1", "2.5", "米") + "]}]}");
        assertThat(duplicate.status()).isEqualTo(422);

        StringBuilder six = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                six.append(',');
            }
            six.append(sourceToken("k" + i, "1.5", "米"));
        }
        six.append(']');
        ApiResult tooMany = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"x\",\"numericTokens\":"
                        + six + "}]}");
        assertThat(tooMany.status()).isEqualTo(400);

        ApiResult badValue = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"x\",\"numericTokens\":["
                        + sourceToken("n1", "1e3", "米") + "]}]}");
        assertThat(badValue.status()).isEqualTo(400);

        // 普通无词元源段：提交译文不得携带词元，不携带词元行为不变
        long plainDoc = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult withToken = submitWithTokens(plainDoc, "alice", "hello 1,5", 1,
                "[" + targetToken("n1", "1,5", "米", 6, 9) + "]", newRequestId());
        assertThat(withToken.status()).isEqualTo(422);
        ApiResult plain = submitTranslation(plainDoc, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(plain.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("源修订推进版本并整体替换词元集：原批准失效，发布 422；按新词元重新提交批准后发布成功")
    void sourceRevisionReplacesTokens() throws Exception {
        long docId = createTokenDocument(newRequestId(), "深度 1234.5 米", "[" + sourceToken("n1", "1234.5", "米") + "]");
        submitWithTokens(docId, "alice", "深度 1.234,50 米", 1,
                "[" + targetToken("n1", "1.234,50", "米", 3, 11) + "]", newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 2

        // 源修订：新词元值 2345.6 米，源文版本 2，草稿版本 3，原批准失效
        ApiResult revised = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"深度 2345.6 米\","
                        + "\"numericTokens\":[" + sourceToken("n1", "2345.6", "米") + "]}");
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 旧值词元提交：值不符新源词元 422
        ApiResult staleValue = submitWithTokens(docId, "alice", "深度 1.234,50 米", 2,
                "[" + targetToken("n1", "1.234,50", "米", 3, 11) + "]", newRequestId());
        assertThat(staleValue.status()).isEqualTo(422);

        // 新值词元提交并批准后发布成功，快照冻结新原值与目标文字
        ApiResult resubmit = submitWithTokens(docId, "alice", "深度 2.345,60 米", 2,
                "[" + targetToken("n1", "2.345,60", "米", 3, 11) + "]", newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("numericTokens").get(0).get("value").asText()).isEqualTo("2345.6");
        assertThat(segment.get("translations").get(0).get("numericTokens").get(0)
                .get("targetValue").asText()).isEqualTo("2.345,60");
    }

    @Test
    @DisplayName("幂等：十进制按值（1234.5 与 1234.50 同参重放），异值 409，失败可复用键")
    void idempotencyDecimalByValue() throws Exception {
        String requestId = newRequestId();
        String bodyPrefix = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"深度 1234.5 米\",\"numericTokens\":[";
        ApiResult first = postJson("/api/documents",
                bodyPrefix + sourceToken("n1", "1234.5", "米") + "]}]}");
        assertThat(first.status()).isEqualTo(201);

        // 同键、十进制等值（1234.50）：重放首次完整响应，不产生新文档
        ApiResult replay = postJson("/api/documents",
                bodyPrefix + sourceToken("n1", "1234.50", "米") + "]}]}");
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("documentId").asLong())
                .isEqualTo(first.body().get("documentId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);

        // 同键异值：409
        ApiResult conflict = postJson("/api/documents",
                bodyPrefix + sourceToken("n1", "1234.6", "米") + "]}]}");
        assertThat(conflict.status()).isEqualTo(409);

        // 失败可复用键：先以某键触发 422（tokenKey 重复），再用同键修正参数后成功
        String failKey = newRequestId();
        ApiResult failed = postJson("/api/documents",
                "{\"requestId\":\"" + failKey + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"x\",\"numericTokens\":["
                        + sourceToken("n1", "1.5", "米") + "," + sourceToken("n1", "2.5", "米") + "]}]}");
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = postJson("/api/documents",
                "{\"requestId\":\"" + failKey + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"x\",\"numericTokens\":["
                        + sourceToken("n1", "1.5", "米") + "]}]}");
        assertThat(retried.status()).isEqualTo(201);
    }

    @Test
    @DisplayName("并发提交带词元译文：多段落并发全部成功，词元行数与草稿版本无丢失更新")
    void concurrentTokenSubmits() throws Exception {
        int segmentCount = 4;
        StringBuilder segments = new StringBuilder("[");
        for (int i = 1; i <= segmentCount; i++) {
            if (i > 1) {
                segments.append(',');
            }
            segments.append("{\"segmentId\":\"s").append(i).append("\",\"sourceText\":\"深度 1234.5 米\",")
                    .append("\"numericTokens\":[").append(sourceToken("n1", "1234.5", "米")).append("]}");
        }
        segments.append(']');
        long docId = createDocument(newRequestId(), "[\"en\"]", segments.toString());

        ExecutorService pool = Executors.newFixedThreadPool(segmentCount);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ApiResult>> futures = new ArrayList<>();
        for (int i = 1; i <= segmentCount; i++) {
            final String segmentId = "s" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                String body = "{\"requestId\":\"" + newRequestId()
                        + "\",\"content\":\"深度 1.234,50 米\",\"sourceVersion\":1,\"numericTokens\":["
                        + targetToken("n1", "1.234,50", "米", 3, 11) + "]}";
                return putJson("/api/documents/" + docId + "/segments/" + segmentId + "/translations/en",
                        body, "alice");
            }));
        }
        gate.countDown();
        for (Future<ApiResult> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(1 + segmentCount);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation_numeric_token WHERE document_id = ?", Integer.class, docId))
                .isEqualTo(segmentCount);
    }
}