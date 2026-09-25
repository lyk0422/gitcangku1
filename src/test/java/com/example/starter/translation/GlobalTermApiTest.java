package com.example.starter.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 共享全局术语库测试：版本生命周期、生效规则集合成（覆盖/抑制）、引用失效与升级、
 * 发布快照固化与幂等边界。
 */
class GlobalTermApiTest extends AbstractIntegrationTest {

    private static final String GLOBAL_V1 =
            "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                    + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"}]";

    @Test
    @DisplayName("全局术语版本：提交 201 且版本递增、不可变；期望版本不符 409；不改变文档草稿版本")
    void globalTermVersionLifecycle() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");

        ApiResult v1 = updateGlobalTerms(0, GLOBAL_V1, newRequestId());
        assertThat(v1.status()).isEqualTo(201);
        assertThat(v1.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(v1.body().get("ruleCount").asInt()).isEqualTo(2);

        ApiResult current = getJson("/api/global-terms");
        assertThat(current.status()).isEqualTo(200);
        assertThat(current.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(current.body().get("rules")).hasSize(2);
        assertThat(current.body().get("rules").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");

        ApiResult specified = getJson("/api/global-terms/1");
        assertThat(specified.status()).isEqualTo(200);
        assertThat(specified.body().get("rules")).hasSize(2);
        assertThat(getJson("/api/global-terms/2").status()).isEqualTo(404);

        // 全局更新不改变文档草稿版本
        Integer draftVersion = jdbc.queryForObject(
                "SELECT draft_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(draftVersion).isEqualTo(1);

        // 第二版：版本递增，旧版本不可覆盖
        ApiResult v2 = updateGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        assertThat(v2.status()).isEqualTo(201);
        assertThat(v2.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(getJson("/api/global-terms/1").body().get("rules")).hasSize(2);
        assertThat(getJson("/api/global-terms").body().get("rules")).hasSize(1);

        // 期望版本不符 409
        assertThat(updateGlobalTerms(1, "[]", newRequestId()).status()).isEqualTo(409);

        // 空规则集允许
        ApiResult empty = updateGlobalTerms(2, "[]", newRequestId());
        assertThat(empty.status()).isEqualTo(201);
        assertThat(empty.body().get("ruleCount").asInt()).isZero();
    }

    @Test
    @DisplayName("全局术语版本：规则重复/suppressed 422；超 200 条或空必译 400；失败不占版本")
    void globalTermVersionFailures() throws Exception {
        ApiResult duplicate = updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"EN\",\"requiredTranslation\":\"ml\"}]",
                newRequestId());
        assertThat(duplicate.status()).isEqualTo(422);

        ApiResult suppressed = updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\","
                        + "\"suppressed\":true}]", newRequestId());
        assertThat(suppressed.status()).isEqualTo(422);

        StringBuilder tooMany = new StringBuilder("[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("{\"sourceTerm\":\"术语").append(i)
                    .append("\",\"language\":\"en\",\"requiredTranslation\":\"term").append(i).append("\"}");
        }
        tooMany.append(']');
        assertThat(updateGlobalTerms(0, tooMany.toString(), newRequestId()).status()).isEqualTo(400);

        assertThat(updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"\"}]",
                newRequestId()).status()).isEqualTo(400);

        // 全部失败不占版本：当前全局版本仍为 0
        assertThat(getJson("/api/global-terms").body().get("globalTermVersion").asInt()).isZero();
    }

    @Test
    @DisplayName("生效规则集：文档覆盖/抑制全局，逐条标注来源并按 sourceTerm+语言升序；文档外语言的全局规则被过滤")
    void effectiveRulesComposition() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\",\"ja\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习与神经网络\"}]");
        updateGlobalTerms(0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"machine learning\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"neural network\"},"
                        + "{\"sourceTerm\":\"云端\",\"language\":\"ja\",\"requiredTranslation\":\"クラウド\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"fr\",\"requiredTranslation\":\"apprentissage\"}]",
                newRequestId());
        ApiResult upgraded = upgradeGlobalTermReference(docId, 0, 0, newRequestId());
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(upgraded.body().get("draftVersion").asInt()).isEqualTo(2);

        // 文档术语版本：覆盖"机器学习"、抑制"神经网络"
        ApiResult docTerms = updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"-\","
                        + "\"suppressed\":true}]", newRequestId());
        assertThat(docTerms.status()).isEqualTo(201);

        ApiResult effective = getJson("/api/documents/" + docId + "/terms/effective");
        assertThat(effective.status()).isEqualTo(200);
        assertThat(effective.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(effective.body().get("globalTermVersion").asInt()).isEqualTo(1);
        var rules = effective.body().get("rules");
        // fr 不在文档目标语言内被过滤；按 sourceTerm 码点升序：云端 < 机器学习 < 神经网络
        assertThat(rules).hasSize(3);
        assertThat(rules.get(0).get("sourceTerm").asText()).isEqualTo("云端");
        assertThat(rules.get(0).get("source").asText()).isEqualTo("GLOBAL");
        assertThat(rules.get(1).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(rules.get(1).get("source").asText()).isEqualTo("DOCUMENT");
        assertThat(rules.get(1).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(rules.get(2).get("sourceTerm").asText()).isEqualTo("神经网络");
        assertThat(rules.get(2).get("source").asText()).isEqualTo("SUPPRESSED");
    }

    @Test
    @DisplayName("覆盖与抑制参与提交校验：文档规则整条覆盖全局，抑制的术语不再校验，违规 422 带来源")
    void overrideAndSuppressAffectValidation() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习与神经网络\"}]");
        updateGlobalTerms(0, GLOBAL_V1, newRequestId());
        upgradeGlobalTermReference(docId, 0, 0, newRequestId());
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"神经网络\",\"language\":\"en\",\"requiredTranslation\":\"-\","
                        + "\"suppressed\":true}]", newRequestId());

        // 覆盖生效：译文含全局必译文本但不含文档必译文本 → 422，违规来源 DOCUMENT
        ApiResult violated = submitTranslation(docId, "s1", "en", "alice",
                "machine learning and neural network", 1, newRequestId());
        assertThat(violated.status()).isEqualTo(422);
        assertThat(violated.body().get("violations")).hasSize(1);
        assertThat(violated.body().get("violations").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(violated.body().get("violations").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(violated.body().get("violations").get(0).get("source").asText()).isEqualTo("DOCUMENT");

        // 抑制生效：神经网络不再校验；含文档必译文本即通过
        ApiResult ok = submitTranslation(docId, "s1", "en", "alice", "ML only", 1, newRequestId());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(ok.body().get("globalTermVersion").asInt()).isEqualTo(1);

        // 未覆盖的全局规则违规来源 GLOBAL
        long docId2 = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"神经网络\"}]");
        upgradeGlobalTermReference(docId2, 0, 0, newRequestId());
        ApiResult globalViolation = submitTranslation(docId2, "s1", "en", "alice", "hello", 1, newRequestId());
        assertThat(globalViolation.status()).isEqualTo(422);
        assertThat(globalViolation.body().get("violations").get(0).get("source").asText()).isEqualTo("GLOBAL");
    }

    @Test
    @DisplayName("引用失效：全局更新后译文过期、既有批准不满足发布；显式升级携带两个期望版本后重新提交批准发布")
    void referenceStaleAndUpgrade() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateGlobalTerms(0, GLOBAL_V1, newRequestId());
        upgradeGlobalTermReference(docId, 0, 0, newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "machine learning", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        // 当前草稿版本 3、文档术语版本 0、引用全局版本 1

        // 全局更新到版本 2：文档引用落后，译文视为术语过期
        updateGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        ApiResult status = getJson("/api/documents/" + docId + "/terms/status");
        assertThat(status.body().get("globalTermVersion").asInt()).isEqualTo(1);
        var entry = status.body().get("translations").get(0);
        assertThat(entry.get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(entry.get("termStale").asBoolean()).isTrue();

        // 既有批准不再满足发布条件：发布 422
        assertThat(publish(docId, 3, 0, newRequestId()).status()).isEqualTo(422);

        // 升级须携带两个期望版本：任一不符 409
        assertThat(upgradeGlobalTermReference(docId, 9, 1, newRequestId()).status()).isEqualTo(409);
        assertThat(upgradeGlobalTermReference(docId, 0, 9, newRequestId()).status()).isEqualTo(409);

        // 显式升级：引用推进到最新全局版本 2，草稿版本加一
        ApiResult upgraded = upgradeGlobalTermReference(docId, 0, 1, newRequestId());
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("termVersion").asInt()).isZero();
        assertThat(upgraded.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(upgraded.body().get("draftVersion").asInt()).isEqualTo(4);

        // 已是最新再升级 422；升级后旧译文过期，不能批准、不能发布
        assertThat(upgradeGlobalTermReference(docId, 0, 2, newRequestId()).status()).isEqualTo(422);
        assertThat(approve(docId, "s1", "en", "bob", 1, newRequestId()).status()).isEqualTo(422);
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(422);

        // 基于新引用重新提交并批准后发布成功
        ApiResult resubmit = submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        assertThat(resubmit.status()).isEqualTo(200);
        assertThat(resubmit.body().get("globalTermVersion").asInt()).isEqualTo(2);
        approve(docId, "s1", "en", "bob", 2, newRequestId());
        ApiResult published = publish(docId, 5, 0, newRequestId());
        assertThat(published.status()).isEqualTo(201);

        // 快照固化两个术语版本与实际生效规则集
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("termVersion").asInt()).isZero();
        assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(release.body().get("terms")).hasSize(1);
        assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(release.body().get("terms").get(0).get("source").asText()).isEqualTo("GLOBAL");
        assertThat(release.body().get("segments").get(0).get("translations").get(0)
                .get("globalTermVersion").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("发布快照固化：发布后全局术语更新与文档术语更新均不改写历史发布查询")
    void releaseSnapshotFreezesBothVersions() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]",
                "[{\"segmentId\":\"s1\",\"sourceText\":\"机器学习\"}]");
        updateGlobalTerms(0, GLOBAL_V1, newRequestId());
        upgradeGlobalTermReference(docId, 0, 0, newRequestId());
        updateTerms(docId, 0,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]",
                newRequestId());
        submitTranslation(docId, "s1", "en", "alice", "ML", 1, newRequestId());
        approve(docId, "s1", "en", "bob", 1, newRequestId());
        assertThat(publish(docId, 4, 0, newRequestId()).status()).isEqualTo(201);

        // 快照：文档术语版本 1、全局版本 1；机器学习被文档覆盖为 ML，神经网络保留全局规则
        ApiResult release = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(release.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(release.body().get("terms")).hasSize(2);
        assertThat(release.body().get("terms").get(0).get("sourceTerm").asText()).isEqualTo("机器学习");
        assertThat(release.body().get("terms").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(release.body().get("terms").get(0).get("source").asText()).isEqualTo("DOCUMENT");
        assertThat(release.body().get("terms").get(1).get("sourceTerm").asText()).isEqualTo("神经网络");
        assertThat(release.body().get("terms").get(1).get("requiredTranslation").asText())
                .isEqualTo("neural network");
        assertThat(release.body().get("terms").get(1).get("source").asText()).isEqualTo("GLOBAL");

        // 后续全局与文档术语更新不改写历史快照
        updateGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"DL\"}]",
                newRequestId());
        upgradeGlobalTermReference(docId, 1, 1, newRequestId());
        updateTerms(docId, 1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"AI\"}]",
                newRequestId());

        ApiResult releaseAgain = getJson("/api/documents/" + docId + "/releases/1");
        assertThat(releaseAgain.body().get("termVersion").asInt()).isEqualTo(1);
        assertThat(releaseAgain.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(releaseAgain.body().get("terms")).hasSize(2);
        assertThat(releaseAgain.body().get("terms").get(0).get("requiredTranslation").asText()).isEqualTo("ML");
        assertThat(releaseAgain.body().get("terms").get(1).get("requiredTranslation").asText())
                .isEqualTo("neural network");

        // 当前生效集已切换
        ApiResult effective = getJson("/api/documents/" + docId + "/terms/effective");
        assertThat(effective.body().get("globalTermVersion").asInt()).isEqualTo(2);
        assertThat(effective.body().get("rules").get(0).get("requiredTranslation").asText()).isEqualTo("AI");
    }

    @Test
    @DisplayName("全局更新与引用升级幂等：同键同参重放、同键异参 409、失败不占键")
    void globalWriteIdempotency() throws Exception {
        long docId = createDocument(newRequestId(), "[\"en\"]", "[]");

        // 全局更新幂等
        String globalKey = newRequestId();
        ApiResult first = updateGlobalTerms(0, GLOBAL_V1, globalKey);
        assertThat(first.status()).isEqualTo(201);
        ApiResult replay = updateGlobalTerms(0, GLOBAL_V1, globalKey);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.body().get("globalTermVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM global_term_version", Integer.class)).isEqualTo(1);
        assertThat(updateGlobalTerms(0, "[]", globalKey).status()).isEqualTo(409);

        // 失败不占键：先触发 422，再用同键修正参数后成功
        String failKey = newRequestId();
        ApiResult failed = updateGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"},"
                        + "{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ml\"}]",
                failKey);
        assertThat(failed.status()).isEqualTo(422);
        ApiResult retried = updateGlobalTerms(1,
                "[{\"sourceTerm\":\"机器学习\",\"language\":\"en\",\"requiredTranslation\":\"ML\"}]", failKey);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body().get("globalTermVersion").asInt()).isEqualTo(2);

        // 引用升级幂等：同键同参重放原结果，同键异参 409
        String upgradeKey = newRequestId();
        ApiResult upgraded = upgradeGlobalTermReference(docId, 0, 0, upgradeKey);
        assertThat(upgraded.status()).isEqualTo(200);
        assertThat(upgraded.body().get("globalTermVersion").asInt()).isEqualTo(2);
        ApiResult upgradeReplay = upgradeGlobalTermReference(docId, 0, 0, upgradeKey);
        assertThat(upgradeReplay.status()).isEqualTo(200);
        assertThat(upgradeReplay.body().get("draftVersion").asInt())
                .isEqualTo(upgraded.body().get("draftVersion").asInt());
        assertThat(upgradeGlobalTermReference(docId, 0, 2, upgradeKey).status()).isEqualTo(409);
        Integer globalTermVersion = jdbc.queryForObject(
                "SELECT global_term_version FROM document WHERE document_id = ?", Integer.class, docId);
        assertThat(globalTermVersion).isEqualTo(2);
    }
}
