package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数值词元 API 集成测试（真实 H2 事务）：合成主流程、失败分支、修订替换、发布冻结与幂等。
 */
class NumericTokenApiTest extends AbstractIntegrationTest {

    /** 建含数值词元的文档：段落 s1 源文"水深 1234.5 米"，词元 depth=1234.5 米。 */
    private long createDocWithToken() throws Exception {
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"水深 1234.5 米\","
                + "\"numericTokens\":[{\"tokenKey\":\"depth\",\"value\":1234.5,\"unit\":\"米\"}]}]}";
        ApiResult result = postJson("/api/documents", body);
        assertThat(result.status()).isEqualTo(201);
        return result.body().get("documentId").asLong();
    }

    /** 提交带单个 depth 词元的译文。 */
    private ApiResult submitWithToken(long docId, String content, int sourceVersion, String target,
                                      String unit, int start, int end, String requestId) throws Exception {
        String body = "{\"requestId\":\"" + requestId + "\",\"content\":\"" + content + "\","
                + "\"sourceVersion\":" + sourceVersion + ",\"numericTokens\":[{\"tokenKey\":\"depth\","
                + "\"target\":\"" + target + "\",\"unit\":\"" + unit + "\",\"start\":" + start
                + ",\"end\":" + end + "}]}";
        return putJson("/api/documents/" + docId + "/segments/s1/translations/en", body, "alice");
    }

    @Test
    @DisplayName("合成主流程：源值 1234.5 米，正文词元 1.234,50 单位米通过；发布快照冻结原值与目标文字")
    void syntheticMainFlow() throws Exception {
        long docId = createDocWithToken();
        ApiResult submitted = submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "米", 3, 11,
                newRequestId());
        assertThat(submitted.status()).isEqualTo(200);

        // 词元落库：冻结目标文字与码点区间
        var tokenRow = jdbc.queryForMap(
                "SELECT target_text, unit, start_offset, end_offset FROM translation_token "
                        + "WHERE document_id = ? AND segment_id = 's1' AND language = 'en'", docId);
        assertThat(tokenRow.get("target_text")).isEqualTo("1.234,50");
        assertThat(tokenRow.get("unit")).isEqualTo("米");
        assertThat(((Number) tokenRow.get("start_offset")).intValue()).isEqualTo(3);
        assertThat(((Number) tokenRow.get("end_offset")).intValue()).isEqualTo(11);

        ApiResult approved = approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(approved.status()).isEqualTo(200);
        ApiResult published = publish(docId, 2, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.status()).isEqualTo(200);
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("numericTokens")).hasSize(1);
        assertThat(segment.get("numericTokens").get(0).get("tokenKey").asText()).isEqualTo("depth");
        assertThat(segment.get("numericTokens").get(0).get("value").asText()).isEqualTo("1234.5");
        assertThat(segment.get("numericTokens").get(0).get("unit").asText()).isEqualTo("米");
        var translation = segment.get("translations").get(0);
        assertThat(translation.get("author").asText()).isEqualTo("alice");
        assertThat(translation.get("reviewer").asText()).isEqualTo("bob");
        assertThat(translation.get("numericTokens")).hasSize(1);
        assertThat(translation.get("numericTokens").get(0).get("target").asText()).isEqualTo("1.234,50");
        assertThat(translation.get("numericTokens").get(0).get("start").asInt()).isEqualTo(3);
        assertThat(translation.get("numericTokens").get(0).get("end").asInt()).isEqualTo(11);
    }

    @Test
    @DisplayName("提交失败分支：12.34,50 格式错误 400；1.234,51 格式合法但错值 422；缺/多词元、单位不符 422")
    void submitFailures() throws Exception {
        long docId = createDocWithToken();
        // 格式非法 400，不自动修复
        ApiResult badFormat = submitWithToken(docId, "水深 12.34,50 米", 1, "12.34,50", "米", 3, 11,
                newRequestId());
        assertThat(badFormat.status()).isEqualTo(400);
        // 格式合法但值不等 422
        ApiResult wrongValue = submitWithToken(docId, "水深 1.234,51 米", 1, "1.234,51", "米", 3, 11,
                newRequestId());
        assertThat(wrongValue.status()).isEqualTo(422);
        // 缺词元 422
        String noTokens = "{\"requestId\":\"" + newRequestId() + "\",\"content\":\"水深 1.234,50 米\","
                + "\"sourceVersion\":1}";
        ApiResult missing = putJson("/api/documents/" + docId + "/segments/s1/translations/en",
                noTokens, "alice");
        assertThat(missing.status()).isEqualTo(422);
        // 多词元（无对应源词元）422
        ApiResult extra = putJson("/api/documents/" + docId + "/segments/s1/translations/en",
                "{\"requestId\":\"" + newRequestId() + "\",\"content\":\"水深 1.234,50 米\","
                        + "\"sourceVersion\":1,\"numericTokens\":["
                        + "{\"tokenKey\":\"depth\",\"target\":\"1.234,50\",\"unit\":\"米\",\"start\":3,\"end\":11},"
                        + "{\"tokenKey\":\"other\",\"target\":\"1,00\",\"unit\":\"米\",\"start\":12,\"end\":16}]}",
                "alice");
        assertThat(extra.status()).isEqualTo(422);
        // 单位不符 422
        ApiResult wrongUnit = submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "m", 3, 11,
                newRequestId());
        assertThat(wrongUnit.status()).isEqualTo(422);

        // 全部失败不写译文与词元
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ?",
                Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation_token WHERE document_id = ?",
                Integer.class, docId)).isZero();
    }

    @Test
    @DisplayName("区间校核：文字不一致/越界 422；补充字符按 1 码点计，区间不偏移")
    void rangeAndSupplementary() throws Exception {
        long docId = createDocWithToken();
        // 区间文字与目标字符串不一致
        ApiResult mismatch = submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "米", 2, 10,
                newRequestId());
        assertThat(mismatch.status()).isEqualTo(422);
        // 越界
        ApiResult outOfBounds = submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "米", 3, 99,
                newRequestId());
        assertThat(outOfBounds.status()).isEqualTo(422);
        // 补充字符 🌊 占 1 个码点：词元位于码点区间 [3,11)
        ApiResult emoji = submitWithToken(docId, "🌊水深1.234,50米", 1, "1.234,50", "米", 3, 11,
                newRequestId());
        assertThat(emoji.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("源登记边界：6 个词元 400；tokenKey 重复 422；普通无词元源段行为不变")
    void sourceRegistrationBounds() throws Exception {
        StringBuilder six = new StringBuilder("[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                six.append(',');
            }
            six.append("{\"tokenKey\":\"k").append(i).append("\",\"value\":1,\"unit\":\"米\"}");
        }
        six.append(']');
        ApiResult tooMany = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"原文\","
                        + "\"numericTokens\":" + six + "}]}");
        assertThat(tooMany.status()).isEqualTo(400);

        ApiResult duplicate = postJson("/api/documents",
                "{\"requestId\":\"" + newRequestId() + "\",\"targetLanguages\":[\"en\"],"
                        + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"原文\","
                        + "\"numericTokens\":[{\"tokenKey\":\"k\",\"value\":1,\"unit\":\"米\"},"
                        + "{\"tokenKey\":\"k\",\"value\":2,\"unit\":\"米\"}]}]}");
        assertThat(duplicate.status()).isEqualTo(422);

        // 普通无词元源段：提交译文无需词元，行为不变
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
    }

    @Test
    @DisplayName("修订替换词元：源修订推进版本并整体替换词元，原批准失效；发布校核当前完整词元")
    void reviseReplacesTokens() throws Exception {
        long docId = createDocWithToken();
        submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "米", 3, 11, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());

        // 修订源文并替换词元：depth → width=5.0 米；源文版本 2，原批准失效
        ApiResult revised = putJson("/api/documents/" + docId + "/segments/s1/source",
                "{\"requestId\":\"" + newRequestId() + "\",\"sourceText\":\"宽度 5.0 米\","
                        + "\"numericTokens\":[{\"tokenKey\":\"width\",\"value\":5.0,\"unit\":\"米\"}]}");
        assertThat(revised.status()).isEqualTo(200);
        assertThat(revised.body().get("sourceVersion").asInt()).isEqualTo(2);

        // 旧译文基于源文版本 1：发布 422 且无快照
        ApiResult stalePublish = publish(docId, 3, 0, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?",
                Integer.class, docId)).isZero();

        // 旧词元键 depth 已无对应源词元：提交 422
        ApiResult oldKey = submitWithToken(docId, "宽度 5,00 米", 2, "5,00", "米", 3, 7, newRequestId());
        assertThat(oldKey.status()).isEqualTo(422);

        // 新词元键 width：提交、批准、发布成功
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"content\":\"宽度 5,00 米\","
                + "\"sourceVersion\":2,\"numericTokens\":[{\"tokenKey\":\"width\","
                + "\"target\":\"5,00\",\"unit\":\"米\",\"start\":3,\"end\":7}]}";
        ApiResult resubmit = putJson("/api/documents/" + docId + "/segments/s1/translations/en",
                body, "alice");
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);
        ApiResult reApprove = approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(reApprove.status()).isEqualTo(200);
        ApiResult published = publish(docId, 4, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        var segment = release.body().get("segments").get(0);
        assertThat(segment.get("numericTokens").get(0).get("tokenKey").asText()).isEqualTo("width");
        assertThat(segment.get("numericTokens").get(0).get("value").asText()).isEqualTo("5.0");
        assertThat(segment.get("translations").get(0).get("numericTokens").get(0)
                .get("target").asText()).isEqualTo("5,00");
    }

    @Test
    @DisplayName("幂等：十进制按值同参重放；异参 409；失败不占键")
    void idempotencyWithNumericTokens() throws Exception {
        String requestId = newRequestId();
        String template = "{\"requestId\":\"" + requestId + "\",\"targetLanguages\":[\"en\"],"
                + "\"segments\":[{\"segmentId\":\"s1\",\"sourceText\":\"水深 1234.5 米\","
                + "\"numericTokens\":[{\"tokenKey\":\"depth\",\"value\":VALUE,\"unit\":\"米\"}]}]}";
        ApiResult first = postJson("/api/documents", template.replace("VALUE", "1234.5"));
        assertThat(first.status()).isEqualTo(201);

        // 十进制按值：1234.50 与 1234.5 摘要一致，重放首次完整响应，不产生新文档
        ApiResult replay = postJson("/api/documents", template.replace("VALUE", "1234.50"));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("documentId").asLong())
                .isEqualTo(first.body().get("documentId").asLong());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM numeric_token", Integer.class)).isEqualTo(1);

        // 异参（值不同）409
        ApiResult conflict = postJson("/api/documents", template.replace("VALUE", "1234.51"));
        assertThat(conflict.status()).isEqualTo(409);

        // 失败不占键：格式错误 400 后同键修正参数成功
        long docId = first.body().get("documentId").asLong();
        String failKey = newRequestId();
        ApiResult failed = submitWithToken(docId, "水深 12.34,50 米", 1, "12.34,50", "米", 3, 11, failKey);
        assertThat(failed.status()).isEqualTo(400);
        ApiResult retried = submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "米", 3, 11, failKey);
        assertThat(retried.status()).isEqualTo(200);
        // 同键同参重放
        ApiResult replayed = submitWithToken(docId, "水深 1.234,50 米", 1, "1.234,50", "米", 3, 11, failKey);
        assertThat(replayed.status()).isEqualTo(200);
        assertThat(replayed.body().get("translationVersion").asInt())
                .isEqualTo(retried.body().get("translationVersion").asInt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(1);
    }

    @Test
    @DisplayName("并发提交带词元译文：多段并发全部成功，词元落库且草稿版本无丢失更新")
    void concurrentSubmitsWithTokens() throws Exception {
        StringBuilder segments = new StringBuilder("[");
        int segmentCount = 4;
        for (int i = 1; i <= segmentCount; i++) {
            if (i > 1) {
                segments.append(',');
            }
            segments.append("{\"segmentId\":\"s").append(i)
                    .append("\",\"sourceText\":\"水深 1234.5 米\",\"numericTokens\":[")
                    .append("{\"tokenKey\":\"depth\",\"value\":1234.5,\"unit\":\"米\"}]}");
        }
        segments.append(']');
        long docId = createDocument(newRequestId(), "[\"en\"]", segments.toString());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors
                .newFixedThreadPool(segmentCount);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<ApiResult>> futures = new java.util.ArrayList<>();
        for (int i = 1; i <= segmentCount; i++) {
            final String segmentId = "s" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                String body = "{\"requestId\":\"" + newRequestId() + "\",\"content\":\"水深 1.234,50 米\","
                        + "\"sourceVersion\":1,\"numericTokens\":[{\"tokenKey\":\"depth\","
                        + "\"target\":\"1.234,50\",\"unit\":\"米\",\"start\":3,\"end\":11}]}";
                return putJson("/api/documents/" + docId + "/segments/" + segmentId + "/translations/en",
                        body, "alice");
            }));
        }
        gate.countDown();
        for (var future : futures) {
            assertThat(future.get(30, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(200);
        }
        pool.shutdown();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM translation_token WHERE document_id = ?",
                Integer.class, docId)).isEqualTo(segmentCount);
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(1 + segmentCount);
    }
}
