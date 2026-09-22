package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 术语版本绑定与违规译文拦截测试：术语命中、全量违规返回、版本失效、发布条件、
 * 历史快照固化与术语查询。
 */
class TermVersionTest extends AbstractIntegrationTest {

    /** 新增术语版本：携带期望版本与完整规则集，成功后术语版本与草稿版本各加一。 */
    private ApiResult updateTerms(long documentId, int expectedTermVersion, String rulesJson) throws Exception {
        String body = "{\"requestId\":\"" + newRequestId() + "\",\"expectedTermVersion\":"
                + expectedTermVersion + ",\"rules\":" + rulesJson + "}";
        return putJson("/api/documents/" + documentId + "/terms", body);
    }

    @Test
    @DisplayName("新增术语版本：201，版本从 1 开始递增，草稿版本加一，规则按提交顺序返回")
    void createTermVersion() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"原文\"}]");
        // 文档初始草稿版本 1
        ApiResult v1 = updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"},"
                        + "{\"language\":\"ja\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"クラウド\"}]");
        assertThat(v1.status()).isEqualTo(201);
        assertThat(v1.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(v1.body().get("draftVersion").asInt()).isEqualTo(2);
        assertThat(v1.body().get("rules")).hasSize(2);
        assertThat(v1.body().get("rules").get(0).get("requiredTranslation").asText()).isEqualTo("Cloud");

        // 空规则集（0 条）也是合法快照
        ApiResult v2 = updateTerms(docId, 1, "[]");
        assertThat(v2.status()).isEqualTo(201);
        assertThat(v2.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(v2.body().get("draftVersion").asInt()).isEqualTo(3);
        assertThat(v2.body().get("rules")).hasSize(0);
    }

    @Test
    @DisplayName("术语版本乐观校验：期望版本不符返回 409，已有版本不可覆盖")
    void termVersionConflict() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult ok = updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"A\",\"requiredTranslation\":\"a\"}]");
        assertThat(ok.status()).isEqualTo(201);

        ApiResult stale = updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"B\",\"requiredTranslation\":\"b\"}]");
        assertThat(stale.status()).isEqualTo(409);
        ApiResult ahead = updateTerms(docId, 5, "[]");
        assertThat(ahead.status()).isEqualTo(409);

        // 冲突后当前版本仍为 1，未被覆盖
        ApiResult current = getJson("/api/documents/" + docId + "/terms");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("rules")).hasSize(1);
    }

    @Test
    @DisplayName("规则校验：语言不在目标语言 422；同语言 sourceTerm 重复 422；超过 100 条 400")
    void invalidRules() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult wrongLanguage = updateTerms(docId, 0,
                "[{\"language\":\"fr\",\"sourceTerm\":\"A\",\"requiredTranslation\":\"a\"}]");
        assertThat(wrongLanguage.status()).isEqualTo(422);

        ApiResult duplicate = updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"A\",\"requiredTranslation\":\"a\"},"
                        + "{\"language\":\"EN\",\"sourceTerm\":\"A\",\"requiredTranslation\":\"b\"}]");
        assertThat(duplicate.status()).isEqualTo(422);

        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("{\"language\":\"en\",\"sourceTerm\":\"T").append(i)
                    .append("\",\"requiredTranslation\":\"r").append(i).append("\"}");
        }
        tooMany.append(']');
        ApiResult overLimit = updateTerms(docId, 0, tooMany.toString());
        assertThat(overLimit.status()).isEqualTo(400);
    }

    @Test
    @DisplayName("术语命中拦截：源文包含 sourceTerm 而译文缺 requiredTranslation 时 422，返回全部违规术语且不写译文")
    void termHitAndAllViolations() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"云端数据库与云存储\"}]");
        updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"},"
                        + "{\"language\":\"en\",\"sourceTerm\":\"数据库\",\"requiredTranslation\":\"Database\"},"
                        + "{\"language\":\"en\",\"sourceTerm\":\"存储\",\"requiredTranslation\":\"Storage\"}]");

        // 三条规则源文均命中，译文只满足其中一条：返回另外两条违规
        ApiResult rejected = submitTranslation(docId, "s1", "en", "alice", "a Cloud text", 1, newRequestId());
        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.body().get("error").asText()).isEqualTo("TERM_VIOLATION");
        assertThat(rejected.body().get("violations")).hasSize(2);
        assertThat(rejected.body().get("violations").get(0).get("sourceTerm").asText()).isEqualTo("数据库");
        assertThat(rejected.body().get("violations").get(0).get("requiredTranslation").asText())
                .isEqualTo("Database");
        assertThat(rejected.body().get("violations").get(1).get("sourceTerm").asText()).isEqualTo("存储");

        // 失败不写译文、不占草稿版本（仍为 2：术语更新加一）
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM translation WHERE document_id = ?", Integer.class, docId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(2);

        // 同一 requestId 在失败后可被成功请求复用（失败不占键）
        String retryId = newRequestId();
        ApiResult firstFail = submitTranslation(docId, "s1", "en", "alice", "bad", 1, retryId);
        assertThat(firstFail.status()).isEqualTo(422);
        ApiResult retry = submitTranslation(docId, "s1", "en", "alice",
                "Cloud Database Storage", 1, retryId);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("termVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("匹配语义：区分大小写的 Unicode 连续子串；源文未命中的规则不参与校验")
    void matchingSemantics() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"API 网关\"}]");
        updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"API\",\"requiredTranslation\":\"api\"},"
                        + "{\"language\":\"en\",\"sourceTerm\":\"网关\",\"requiredTranslation\":\"Gateway\"},"
                        // ja 规则不应校验 en 译文
                        "{\"language\":\"ja\",\"sourceTerm\":\"API\",\"requiredTranslation\":\"エーピーアイ\"}]");

        // 源文包含大写 API，requiredTranslation 要求小写 api 连续子串；给大写 API 不满足
        ApiResult caseSensitive = submitTranslation(docId, "s1", "en", "alice", "API Gateway", 1, newRequestId());
        assertThat(caseSensitive.status()).isEqualTo(422);
        assertThat(caseSensitive.body().get("violations")).hasSize(1);
        assertThat(caseSensitive.body().get("violations").get(0).get("requiredTranslation").asText())
                .isEqualTo("api");

        // 小写 api + Gateway 满足；不要求分词，连续子串即可
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "an api Gateway thing", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);

        // 译文提交后响应带绑定术语版本
        assertThat(ok.body().get("termVersion").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("术语版本失效：更新后旧译文保留但术语过期，既有批准不再满足发布，须重新提交再批准")
    void termVersionInvalidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"云服务\"}]");
        // 术语版本 1：云 -> Cloud
        updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"}]");
        submitTranslation(docId, "s1", "en", "alice", "Cloud service", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 草稿版本：1(初始) +1(术语) +1(译文) = 3
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 术语版本 2：新增规则 服务 -> Service（完整规则集快照）
        ApiResult v2 = updateTerms(docId, 1,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"},"
                        + "{\"language\":\"en\",\"sourceTerm\":\"服务\",\"requiredTranslation\":\"Service\"}]");
        assertThat(v2.status()).isEqualTo(201);
        // 旧译文保留
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM translation WHERE document_id = ? AND segment_id = 's1' AND language = 'en'",
                Integer.class, docId)).isEqualTo(1);

        // 旧批准仍在但发布条件不再满足：期望草稿版本为 4（发布不改草稿版本，术语更新加一）
        ApiResult stalePublish = publish(docId, 4, 1, newRequestId());
        assertThat(stalePublish.status()).isEqualTo(422);

        // 术语状态查询：current=false
        ApiResult status = getJson("/api/documents/" + docId
                + "/segments/s1/translations/en/term-status");
        assertThat(status.status()).isEqualTo(200);
        assertThat(status.body().get("current").asBoolean()).isFalse();
        assertThat(status.body().get("boundTermVersion").asInt()).isEqualTo(1);
        assertThat(status.body().get("currentTermVersion").asInt()).isEqualTo(2);

        // 译者基于当前源文与术语版本重新提交（旧译文不满足新规则时先 422）
        ApiResult resubmitBad = submitTranslation(docId, "s1", "en", "alice", "Cloud service", 1,
                newRequestId());
        assertThat(resubmitBad.status()).isEqualTo(422);
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "Cloud Service", 1,
                newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("translationVersion").asInt()).isEqualTo(2);
        assertThat(resubmit.body().get("termVersion").asInt()).isEqualTo(2);

        // 旧批准针对译文版本 1，发布仍 422；审核人重新批准版本 2 后发布成功
        ApiResult beforeReapproval = publish(docId, 5, 1, newRequestId());
        assertThat(beforeReapproval.status()).isEqualTo(422);
        ApiResult reapprove = approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(reapprove.status()).isEqualTo(200);
        ApiResult second = publish(docId, 5, 1, newRequestId());
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body().get("publishedVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("发布竞争：术语更新与发布并发，只能发布更新前完整状态或因草稿版本变化 409，无混合快照")
    void concurrentPublishAndTermUpdate() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"云\"}]");
        updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"}]");
        submitTranslation(docId, "s1", "en", "alice", "Cloud", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3、发布版本 0、术语版本 1；发布期望 3/0
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Future<ApiResult> publishFuture = pool.submit(() -> {
            gate.await();
            return publish(docId, 3, 0, newRequestId());
        });
        java.util.concurrent.Future<ApiResult> termsFuture = pool.submit(() -> {
            gate.await();
            return updateTerms(docId, 1,
                    "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Klaud\"}]");
        });
        gate.countDown();
        ApiResult publishResult = publishFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
        ApiResult termsResult = termsFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(termsResult.status()).isEqualTo(201);
        assertThat(publishResult.status()).isIn(201, 409);
        Integer snapshots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM release_snapshot WHERE document_id = ?", Integer.class, docId);
        if (publishResult.status() == 201) {
            // 发布先于术语更新：快照固化术语版本 1 与旧规则集 Cloud
            assertThat(snapshots).isEqualTo(1);
            ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
            assertThat(release.body().get("termVersion").asInt()).isEqualTo(1);
            assertThat(release.body().get("termRules").get(0).get("requiredTranslation").asText())
                    .isEqualTo("Cloud");
            assertThat(release.body().get("segments").get(0).get("translations").get(0)
                    .get("termVersion").asInt()).isEqualTo(1);
        } else {
            // 术语更新先于发布：草稿版本变化，发布 409 且无快照
            assertThat(snapshots).isZero();
        }
    }

    @Test
    @DisplayName("历史快照：发布固化术语版本与实际规则集，后续术语更新不改写历史发布查询")
    void snapshotFreezesTerms() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"云\"}]");
        updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"}]");
        submitTranslation(docId, "s1", "en", "alice", "Cloud v1", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(201);

        // 术语更新为版本 2 并再发布
        updateTerms(docId, 1,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Klaud\"}]");
        submitTranslation(docId, "s1", "en", "alice", "Klaud v2", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        assertThat(publish(docId, 5, 1, newRequestId()).status()).isEqualTo(201);

        ApiResult release1 = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release1.status()).isEqualTo(200);
        assertThat(release1.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(release1.body().get("termRules")).hasSize(1);
        assertThat(release1.body().get("termRules").get(0).get("requiredTranslation").asText())
                .isEqualTo("Cloud");
        assertThat(release1.body().get("segments").get(0).get("translations").get(0)
                .get("content").asText()).isEqualTo("Cloud v1");

        ApiResult release2 = getJson("/api/documents/" + docId + "/releases/2");
        assertThat(release2.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(release2.body().get("termRules").get(0).get("requiredTranslation").asText())
                .isEqualTo("Klaud");
    }

    @Test
    @DisplayName("术语查询：当前/指定版本、版本 0 空规则集、历史版本不可变、不存在版本 404、术语状态查询")
    void termQueries() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"云\"}]");

        // 初始当前版本为 0，空规则集
        ApiResult initial = getJson("/api/documents/" + docId + "/terms");
        assertThat(initial.status()).isEqualTo(200);
        assertThat(initial.body().get("termVersion").asInt()).isZero();
        assertThat(initial.body().get("rules")).hasSize(0);
        ApiResult explicitZero = getJson("/api/documents/" + docId + "/terms/0");
        assertThat(explicitZero.status()).isEqualTo(200);
        assertThat(explicitZero.body().get("rules")).hasSize(0);

        updateTerms(docId, 0,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Cloud\"}]");
        updateTerms(docId, 1,
                "[{\"language\":\"en\",\"sourceTerm\":\"云\",\"requiredTranslation\":\"Nube\"}]");

        // 当前版本为 2
        ApiResult current = getJson("/api/documents/" + docId + "/terms");
        assertThat(current.body().get("termVersion").asInt()).isEqualTo(2);
        assertThat(current.body().get("rules").get(0).get("requiredTranslation").asText()).isEqualTo("Nube");
        // 历史版本 1 不可变
        ApiResult history = getJson("/api/documents/" + docId + "/terms/1");
        assertThat(history.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(history.body().get("rules").get(0).get("requiredTranslation").asText()).isEqualTo("Cloud");
        // 不存在版本 404
        assertThat(getJson("/api/documents/" + docId + "/terms/99").status()).isEqualTo(404);

        // 无译文时的术语状态：hasTranslation=false，未绑定当前术语版本（current=false），
        // 并按当前源文与空译文给出全部违规
        ApiResult noTranslation = getJson("/api/documents/" + docId
                + "/segments/s1/translations/en/term-status");
        assertThat(noTranslation.status()).isEqualTo(200);
        assertThat(noTranslation.body().get("hasTranslation").asBoolean()).isFalse();
        assertThat(noTranslation.body().get("current").asBoolean()).isFalse();
        assertThat(noTranslation.body().get("boundTermVersion").asInt()).isZero();
        assertThat(noTranslation.body().get("violations")).hasSize(1);

        // 提交满足当前规则的译文后：current=true 且无违规
        submitTranslation(docId, "s1", "en", "alice", "Nube", 1, newRequestId());
        ApiResult satisfied = getJson("/api/documents/" + docId
                + "/segments/s1/translations/en/term-status");
        assertThat(satisfied.body().get("hasTranslation").asBoolean()).isTrue();
        assertThat(satisfied.body().get("current").asBoolean()).isTrue();
        assertThat(satisfied.body().get("boundTermVersion").asInt()).isEqualTo(2);
        assertThat(satisfied.body().get("violations")).hasSize(0);
    }

    @Test
    @DisplayName("术语更新幂等：同键同参重放同一结果不重复加版本；同键异参 409；失败不占键")
    void termUpdateIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");
        String requestId = newRequestId();
        String body = "{\"requestId\":\"" + requestId + "\",\"expectedTermVersion\":0,"
                + "\"rules\":[{\"language\":\"en\",\"sourceTerm\":\"A\",\"requiredTranslation\":\"a\"}]}";
        ApiResult first = putJson("/api/documents/" + docId + "/terms", body);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.body().get("termVersion").asInt()).isEqualTo(1);

        // 同键同参重放：仍返回版本 1，不产生版本 2
        ApiResult replay = putJson("/api/documents/" + docId + "/terms", body);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM term_version WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT term_version FROM document WHERE document_id = ?", Integer.class, docId)).isEqualTo(1);

        // 同键异参 409
        String different = "{\"requestId\":\"" + requestId + "\",\"expectedTermVersion\":0,\"rules\":[]}";
        assertThat(putJson("/api/documents/" + docId + "/terms", different).status()).isEqualTo(409);

        // 失败不占键：期望版本错误 409 后同键用于另一文档的合法请求仍可成功
        String failKey = newRequestId();
        ApiResult failed = putJson("/api/documents/" + docId + "/terms",
                "{\"requestId\":\"" + failKey + "\",\"expectedTermVersion\":99,\"rules\":[]}");
        assertThat(failed.status()).isEqualTo(409);
        long otherDoc = createDocument(newRequestId(), "[\"en\"]", "[]");
        ApiResult retried = putJson("/api/documents/" + otherDoc + "/terms",
                "{\"requestId\":\"" + failKey + "\",\"expectedTermVersion\":0,"
                        + "\"rules\":[{\"language\":\"en\",\"sourceTerm\":\"B\",\"requiredTranslation\":\"b\"}]}");
        assertThat(retried.status()).isEqualTo(201);
    }
}
