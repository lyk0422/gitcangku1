package com.example.starter.consent.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.starter.consent.ConsentService;
import com.example.starter.consent.catalog.dto.ActivateGrantItem;
import com.example.starter.consent.catalog.dto.ActivateMigrationRequest;
import com.example.starter.consent.catalog.dto.ActivateRecordItem;
import com.example.starter.consent.catalog.dto.BatchQueryItem;
import com.example.starter.consent.catalog.dto.BatchQueryRequest;
import com.example.starter.consent.catalog.dto.MappingResult;
import com.example.starter.consent.catalog.dto.MigrationPreviewResponse;
import com.example.starter.consent.catalog.dto.MigrationProposalRequest;
import com.example.starter.consent.catalog.dto.NewPurposeDef;
import com.example.starter.consent.catalog.dto.QueryGenerationRequest;
import com.example.starter.consent.catalog.dto.QueryGenerationResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用途拆分迁移与查询代次隔离的 H2 真实数据库集成测试：
 * 覆盖范围守恒、完整迁移、撤回隔离、查询代次、幂等与并发边界。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MigrationApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ConsentService consentService;
    @Autowired
    private MigrationService migrationService;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM migration_evidence_item");
        jdbc.update("DELETE FROM migration_evidence");
        jdbc.update("DELETE FROM query_generation");
        jdbc.update("DELETE FROM purpose_replacement");
        jdbc.update("DELETE FROM consent_record");
        jdbc.update("DELETE FROM consent_grant");
        jdbc.update("DELETE FROM catalog_purpose");
        jdbc.update("DELETE FROM catalog_generation");
        jdbc.update("DELETE FROM idempotency_request");
        jdbc.update("INSERT INTO catalog_generation (generation, migration_key, source_purpose)"
                + " VALUES (1, NULL, NULL)");
        // DELETE 不重置 H2 自增序列，显式从 2 继续，保证用例内代次确定
        jdbc.execute("ALTER TABLE catalog_generation ALTER COLUMN generation RESTART WITH 2");
        jdbc.update("INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)"
                + " VALUES ('RESEARCH', 'ACTIVE', NULL, 1)");
        jdbc.update("INSERT INTO catalog_purpose (code, status, scope_canonical, introduced_generation)"
                + " VALUES ('PERSONALIZATION', 'ACTIVE', NULL, 1)");
    }

    private MigrationProposalRequest proposal(String migrationKey) {
        Instant now = Instant.now();
        return new MigrationProposalRequest(
                1, "RESEARCH", List.of("A", "B", "C"), migrationKey,
                List.of(new NewPurposeDef("RESEARCH_CLIN", List.of("A", "B")),
                        new NewPurposeDef("RESEARCH_OTHER", List.of("C"))),
                now.minus(1, ChronoUnit.HOURS), now.plus(1, ChronoUnit.HOURS));
    }

    private MigrationPreviewResponse preview(String migrationKey) {
        return migrationService.preview(proposal(migrationKey));
    }

    private ActivateMigrationRequest activationFromPreview(String requestId, MigrationPreviewResponse p) {
        List<ActivateGrantItem> grants = p.activeGrants().stream()
                .map(g -> new ActivateGrantItem(g.subjectKey(), g.epoch(), g.expectedVersion()))
                .toList();
        List<ActivateRecordItem> records = p.records().stream()
                .map(r -> new ActivateRecordItem(r.subjectKey(), r.epoch(), r.recordKey(),
                        r.expectedVersion(), r.attributeValue(), r.mappingResult(), r.targetPurpose()))
                .toList();
        return new ActivateMigrationRequest(requestId, p.catalogVersion(), p.sourcePurpose(),
                p.sourceScope(), p.migrationKey(), p.newPurposes(),
                p.effectiveFrom(), p.effectiveTo(), grants, records);
    }

    private int count(String sql, Object... args) {
        Integer c = jdbc.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    // ---------- 范围守恒与提案校验 ----------

    @Test
    void scopeUnionMustExactlyEqualSourceScope() {
        // 新范围并集 {A,B} 小于旧范围 {A,B,C}，违反拆分不扩容/不遗漏
        MigrationProposalRequest bad = new MigrationProposalRequest(
                1, "RESEARCH", List.of("A", "B", "C"), "mk-bad-union",
                List.of(new NewPurposeDef("P1", List.of("A")),
                        new NewPurposeDef("P2", List.of("B"))),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.preview(bad))
                .hasMessageContaining("并集必须恰好等于旧用途范围");
    }

    @Test
    void newScopeBeyondSourceRejected() {
        MigrationProposalRequest bad = new MigrationProposalRequest(
                1, "RESEARCH", List.of("A", "B"), "mk-bad-subset",
                List.of(new NewPurposeDef("P1", List.of("A")),
                        new NewPurposeDef("P2", List.of("Z"))),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.preview(bad))
                .hasMessageContaining("越出旧用途范围");
    }

    @Test
    void overlappingScopesRejectedAsMultipleTargets() {
        MigrationProposalRequest bad = new MigrationProposalRequest(
                1, "RESEARCH", List.of("A", "B"), "mk-overlap",
                List.of(new NewPurposeDef("P1", List.of("A", "B")),
                        new NewPurposeDef("P2", List.of("B"))),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.preview(bad))
                .hasMessageContaining("两两重叠");
    }

    @Test
    void tooFewNewPurposesRejectedAtHttpLayer() throws Exception {
        String body = """
                {"catalogVersion":1,"sourcePurpose":"RESEARCH","sourceScopeValues":["A"],
                 "migrationKey":"mk-count","newPurposes":[{"code":"P1","scopeValues":["A"]}],
                 "effectiveFrom":"%s","effectiveTo":"%s"}
                """.formatted(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        mockMvc.perform(post("/api/v1/catalog/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void staleCatalogVersionRejected() {
        // 先完成一次空绑定数据的合法迁移，把目录推进到代次 2
        migrationService.activate(activationFromPreview("act-bump", preview("mk-bump")));
        MigrationProposalRequest stale = new MigrationProposalRequest(
                1, "PERSONALIZATION", List.of("A", "B"), "mk-ver-stale",
                List.of(new NewPurposeDef("Q1", List.of("A")),
                        new NewPurposeDef("Q2", List.of("B"))),
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.preview(stale))
                .hasMessageContaining("目录版本已变化");
    }

    @Test
    void replacementCycleRejected() {
        // 预置替代边 NEW_CLIN -> RESEARCH，若再以 NEW_CLIN 拆分 RESEARCH 即形成环
        jdbc.update("INSERT INTO purpose_replacement (parent_code, child_code, child_generation, scope_canonical)"
                + " VALUES ('RESEARCH_CLIN', 'RESEARCH', 1, 'A,B')");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> preview("mk-cycle"))
                .hasMessageContaining("替代关系形成环");
    }

    // ---------- 完整迁移主流程 ----------

    @Test
    void fullMigrationSplitsGrantsAndRebindsRecordsOnce() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.grant(new GrantRequest("g2", "subj-b", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        consentService.write(new RecordWriteRequest("w2", "subj-a", "RESEARCH", "rec-b", "pb", "B"));
        consentService.write(new RecordWriteRequest("w3", "subj-b", "RESEARCH", "rec-c", "pc", "C"));
        // 属性缺失 -> UNMAPPED，保留旧用途
        consentService.write(new RecordWriteRequest("w4", "subj-b", "RESEARCH", "rec-null", "pn", null));

        MigrationPreviewResponse p = preview("mk-full");
        assertThat(p.activeGrants()).hasSize(2);
        assertThat(p.revokedGrants()).isEmpty();
        assertThat(p.records()).hasSize(4);
        // 预览不写数据
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'MIGRATED'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM catalog_generation")).isEqualTo(1);

        var resp = migrationService.activate(activationFromPreview("act-1", p));
        assertThat(resp.catalogGeneration()).isEqualTo(2);
        assertThat(resp.newPurposes()).containsExactly("RESEARCH_CLIN", "RESEARCH_OTHER");
        assertThat(resp.activeGrantCount()).isEqualTo(2);
        assertThat(resp.newGrantCount()).isEqualTo(4);
        assertThat(resp.reboundRecordCount()).isEqualTo(3);
        assertThat(resp.unmappedCount()).isEqualTo(1);

        // 旧授权 MIGRATED；每个主体在每个新用途各有一个 ACTIVE epoch=1
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE purpose = 'RESEARCH'"
                + " AND status = 'MIGRATED'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE purpose = 'RESEARCH_CLIN'"
                + " AND epoch = 1 AND status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE purpose = 'RESEARCH_OTHER'"
                + " AND epoch = 1 AND status = 'ACTIVE'")).isEqualTo(2);

        // 数据一次性改绑，任何记录只有一个活动用途归属
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH_CLIN'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH_OTHER'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(4);

        // 旧用途目录状态 SPLIT，不能再授权/写入
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> consentService.grant(new GrantRequest("g3", "subj-a", "RESEARCH")))
                .hasMessageContaining("已被拆分");
    }

    @Test
    void previewIsStableSortedAndDoesNotWrite() {
        consentService.grant(new GrantRequest("g1", "subj-b", "RESEARCH"));
        consentService.grant(new GrantRequest("g2", "subj-a", "RESEARCH"));
        MigrationPreviewResponse p1 = preview("mk-sort");
        MigrationPreviewResponse p2 = preview("mk-sort");
        assertThat(p1.activeGrants().get(0).subjectKey()).isEqualTo("subj-a");
        assertThat(p1.activeGrants()).isEqualTo(p2.activeGrants());
        assertThat(count("SELECT COUNT(*) FROM migration_evidence")).isZero();
    }

    // ---------- 撤回隔离 ----------

    @Test
    void revokedGrantsAndIsolatedDataAreRetainedAndNeverRestored() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        consentService.revoke(new RevokeRequest("r1", "subj-a", "RESEARCH", 1));
        // 撤回后再授权产生 epoch2 有效授权；epoch1 的数据成为隔离数据
        consentService.grant(new GrantRequest("g2", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w2", "subj-a", "RESEARCH", "rec-c", "pc", "C"));

        MigrationPreviewResponse p = preview("mk-revoke");
        assertThat(p.activeGrants()).hasSize(1);
        assertThat(p.activeGrants().get(0).epoch()).isEqualTo(2);
        assertThat(p.revokedGrants()).hasSize(1);
        var isolated = p.records().stream().filter(r -> r.recordKey().equals("rec-a")).findFirst().orElseThrow();
        assertThat(isolated.isolated()).isTrue();
        assertThat(isolated.mappingResult()).isEqualTo(MappingResult.RETAINED);
        assertThat(isolated.targetPurpose()).isNull();

        var resp = migrationService.activate(activationFromPreview("act-r", p));
        assertThat(resp.retainedCount()).isEqualTo(1);
        assertThat(resp.reboundRecordCount()).isEqualTo(1);

        // 隔离数据保留历史旧用途，未借迁移恢复；历史撤回链不改写
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-a'"
                + " AND purpose = 'RESEARCH'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE epoch = 1 AND status = 'MIGRATED'")).isZero();
    }

    // ---------- 激活完整性失败分支 ----------

    @Test
    void missingGrantInActivationRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        MigrationPreviewResponse p = preview("mk-miss-g");
        var activation = activationFromPreview("act-miss", p);
        var dropped = new ActivateMigrationRequest(activation.requestId(), activation.catalogVersion(),
                activation.sourcePurpose(), activation.sourceScopeValues(), activation.migrationKey(),
                activation.newPurposes(), activation.effectiveFrom(), activation.effectiveTo(),
                List.of(), activation.records());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(dropped))
                .hasMessageContaining("遗漏或多余有效授权");
        // 失败不占键、不写数据
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'act-miss'")).isZero();
        assertThat(count("SELECT COUNT(*) FROM catalog_generation")).isEqualTo(1);
    }

    @Test
    void extraRecordInActivationRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        MigrationPreviewResponse p = preview("mk-extra");
        var activation = activationFromPreview("act-extra", p);
        var extraRecords = new ArrayList<>(activation.records());
        extraRecords.add(new ActivateRecordItem("ghost", 1, "rec-x", 0L, "A",
                MappingResult.MAPPED, "RESEARCH_CLIN"));
        var tampered = new ActivateMigrationRequest(activation.requestId(), activation.catalogVersion(),
                activation.sourcePurpose(), activation.sourceScopeValues(), activation.migrationKey(),
                activation.newPurposes(), activation.effectiveFrom(), activation.effectiveTo(),
                activation.grants(), extraRecords);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(tampered))
                .hasMessageContaining("预览之外的记录");
    }

    @Test
    void attributeChangedBetweenPreviewAndActivateRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        MigrationPreviewResponse p = preview("mk-change");
        var activation = activationFromPreview("act-change", p);
        // 预览后直接改属性（模拟另一写入路径的属性变化），行版本不变也要检出
        jdbc.update("UPDATE consent_record SET attribute_value = 'C' WHERE record_key = 'rec-a'");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(activation))
                .hasMessageContaining("记录属性在预览后已变化");
    }

    @Test
    void revokeBetweenPreviewAndActivateRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        MigrationPreviewResponse p = preview("mk-revoke2");
        consentService.revoke(new RevokeRequest("r1", "subj-a", "RESEARCH", 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> migrationService.activate(activationFromPreview("act-revoke2", p)))
                .hasMessageContaining("授权状态在预览后已变化");
    }

    @Test
    void wrongTargetPurposeRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        MigrationPreviewResponse p = preview("mk-wrong-target");
        var activation = activationFromPreview("act-wrong", p);
        var tamperedRecords = activation.records().stream()
                .map(r -> r.mappingResult() == MappingResult.MAPPED
                        ? new ActivateRecordItem(r.subjectKey(), r.epoch(), r.recordKey(), r.expectedVersion(),
                                r.attributeValue(), MappingResult.MAPPED, "RESEARCH_OTHER")
                        : r)
                .toList();
        var tampered = new ActivateMigrationRequest(activation.requestId(), activation.catalogVersion(),
                activation.sourcePurpose(), activation.sourceScopeValues(), activation.migrationKey(),
                activation.newPurposes(), activation.effectiveFrom(), activation.effectiveTo(),
                activation.grants(), tamperedRecords);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(tampered))
                .hasMessageContaining("目标用途与唯一映射不一致");
    }

    @Test
    void activationOutsideWindowRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        Instant now = Instant.now();
        MigrationProposalRequest proposal = new MigrationProposalRequest(
                1, "RESEARCH", List.of("A", "B"), "mk-window",
                List.of(new NewPurposeDef("P1", List.of("A")),
                        new NewPurposeDef("P2", List.of("B"))),
                now.minusSeconds(7200), now.minusSeconds(3600));
        MigrationPreviewResponse p = migrationService.preview(proposal);
        var activation = new ActivateMigrationRequest("act-win", p.catalogVersion(), p.sourcePurpose(),
                p.sourceScope(), p.migrationKey(), p.newPurposes(),
                p.effectiveFrom(), p.effectiveTo(),
                p.activeGrants().stream()
                        .map(g -> new ActivateGrantItem(g.subjectKey(), g.epoch(), g.expectedVersion())).toList(),
                List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(activation))
                .hasMessageContaining("生效窗口");
    }

    // ---------- 幂等与 migrationKey 唯一 ----------

    @Test
    void activationReplayReturnsFirstSnapshotAndReorderIsEquivalent() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        MigrationPreviewResponse p = preview("mk-idem");
        var first = migrationService.activate(activationFromPreview("act-idem", p));
        var reordered = new ActivateMigrationRequest("act-idem", p.catalogVersion(), p.sourcePurpose(),
                p.sourceScope(), p.migrationKey(),
                p.newPurposes().reversed(), p.effectiveFrom(), p.effectiveTo(),
                p.activeGrants().reversed().stream()
                        .map(g -> new ActivateGrantItem(g.subjectKey(), g.epoch(), g.expectedVersion())).toList(),
                p.records().reversed().stream()
                        .map(r -> new ActivateRecordItem(r.subjectKey(), r.epoch(), r.recordKey(),
                                r.expectedVersion(), r.attributeValue(), r.mappingResult(), r.targetPurpose()))
                        .toList());
        var replay = migrationService.activate(reordered);
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'act-idem'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM catalog_generation")).isEqualTo(2);
    }

    @Test
    void sameRequestIdDifferentParamsRejected() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        MigrationPreviewResponse p = preview("mk-conflict");
        var first = activationFromPreview("act-x", p);
        migrationService.activate(first);
        // 异参冲突在激活入口即检出，无需第二次预览合法：仅改 migrationKey
        var different = new ActivateMigrationRequest(first.requestId(), first.catalogVersion(),
                first.sourcePurpose(), first.sourceScopeValues(), "mk-totally-different",
                first.newPurposes(), first.effectiveFrom(), first.effectiveTo(),
                first.grants(), first.records());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(different))
                .hasMessageContaining("同一 requestId 参数不一致");
    }

    @Test
    void migrationKeyMustBeUnique() {
        MigrationPreviewResponse p = preview("mk-dup");
        var activation = activationFromPreview("act-dup-1", p);
        migrationService.activate(activation);
        // 激活入口在校验目录版本前先检查 migrationKey 唯一性
        var duplicateKey = new ActivateMigrationRequest("act-dup-2", activation.catalogVersion(),
                activation.sourcePurpose(), activation.sourceScopeValues(), activation.migrationKey(),
                activation.newPurposes(), activation.effectiveFrom(), activation.effectiveTo(),
                activation.grants(), activation.records());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(duplicateKey))
                .hasMessageContaining("migrationKey 已被使用");
    }

    // ---------- 查询代次隔离 ----------

    @Test
    void queryGenerationsAreIsolatedAcrossMigration() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        QueryGenerationResponse oldToken = migrationService.issueQueryGeneration(new QueryGenerationRequest(null));
        assertThat(oldToken.catalogGeneration()).isEqualTo(1);

        MigrationPreviewResponse p = preview("mk-query");
        migrationService.activate(activationFromPreview("act-q", p));

        // 迁移前签发的 queryGeneration 在生效后拒绝
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.batchQuery(
                new BatchQueryRequest(oldToken.token(),
                        List.of(new BatchQueryItem("subj-a", "RESEARCH_CLIN", "rec-a")))))
                .hasMessageContaining("查询代次已在目录迁移生效后失效");

        // 新批量查询固定新代次，可读取改绑后的数据
        QueryGenerationResponse newToken = migrationService.issueQueryGeneration(new QueryGenerationRequest(null));
        assertThat(newToken.catalogGeneration()).isEqualTo(2);
        var resp = migrationService.batchQuery(new BatchQueryRequest(newToken.token(),
                List.of(new BatchQueryItem("subj-a", "RESEARCH_CLIN", "rec-a"))));
        assertThat(resp.catalogGeneration()).isEqualTo(2);
        assertThat(resp.results()).hasSize(1);
        assertThat(resp.results().get(0).found()).isTrue();
        assertThat(resp.results().get(0).payload()).isEqualTo("pa");

        // 不能混读旧新用途：一批中夹带旧用途整批 422
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.batchQuery(
                new BatchQueryRequest(newToken.token(), List.of(
                        new BatchQueryItem("subj-a", "RESEARCH_CLIN", "rec-a"),
                        new BatchQueryItem("subj-a", "RESEARCH", "rec-a")))))
                .hasMessageContaining("不能混读旧新用途");
    }

    @Test
    void unknownQueryTokenRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.batchQuery(
                new BatchQueryRequest("no-such-token", List.of())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void oldPurposeReadAndWriteRejectedAfterMigration() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        migrationService.activate(activationFromPreview("act-old", preview("mk-old-rw")));

        // 旧授权 MIGRATED：旧用途查询 409，写入 409
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> consentService.read("subj-a", "RESEARCH", "rec-a"))
                .isInstanceOf(com.example.starter.consent.ApiException.class)
                .hasMessageContaining("已随用途拆分迁移");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> consentService.write(new RecordWriteRequest(
                                "w-new", "subj-a", "RESEARCH", "rec-z", "pz", "A")))
                .hasMessageContaining("已被拆分");
        // 新用途可正常写入与读取
        consentService.write(new RecordWriteRequest(
                "w-new2", "subj-a", "RESEARCH_CLIN", "rec-new", "pn", "B"));
        assertThat(consentService.read("subj-a", "RESEARCH_CLIN", "rec-new").payload()).isEqualTo("pn");
    }

    @Test
    void failedActivationDoesNotConsumeRequestIdAndCanSucceedAfterwards() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        MigrationPreviewResponse p = preview("mk-retry");
        var activation = activationFromPreview("act-retry", p);
        // 篡改 expectedVersion 制造失败
        var tampered = new ActivateMigrationRequest(activation.requestId(), activation.catalogVersion(),
                activation.sourcePurpose(), activation.sourceScopeValues(), activation.migrationKey(),
                activation.newPurposes(), activation.effectiveFrom(), activation.effectiveTo(),
                activation.grants().stream()
                        .map(g -> new ActivateGrantItem(g.subjectKey(), g.epoch(), g.expectedVersion() + 1))
                        .toList(),
                activation.records());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.activate(tampered))
                .hasMessageContaining("授权版本在预览后已变化");
        // 失败不占键：同 requestId 同参随后可成功
        var ok = migrationService.activate(activation);
        assertThat(ok.catalogGeneration()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'act-retry'")).isEqualTo(1);
    }

    // ---------- 证据查询 ----------

    @Test
    void evidenceIsReadableAndStablySorted() {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.grant(new GrantRequest("g2", "subj-b", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));
        MigrationPreviewResponse p = preview("mk-evidence");
        migrationService.activate(activationFromPreview("act-e", p));

        var e1 = migrationService.evidence("mk-evidence");
        var e2 = migrationService.evidence("mk-evidence");
        assertThat(e1.catalogGeneration()).isEqualTo(2);
        assertThat(e1.items()).isEqualTo(e2.items());
        // 2 个有效授权 × 2 个新用途 + 1 条记录
        assertThat(e1.items()).hasSize(5);
        assertThat(e1.items().stream().filter(i -> i.itemType().equals("GRANT_ACTIVE"))).hasSize(4);
        assertThat(e1.items().stream().filter(i -> i.itemType().equals("RECORD")
                && i.mappingResult() == MappingResult.MAPPED
                && "RESEARCH_CLIN".equals(i.newPurpose())
                && i.newEpoch() == null && i.recordKey().equals("rec-a"))).hasSize(1);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> migrationService.evidence("nope"))
                .hasMessageContaining("迁移证据不存在");
    }

    @Test
    void evidenceEndpointReturnsJson() throws Exception {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        MigrationPreviewResponse p = preview("mk-http");
        migrationService.activate(activationFromPreview("act-http", p));
        mockMvc.perform(get("/api/v1/catalog/migrations/mk-http/evidence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogGeneration").value(2))
                .andExpect(jsonPath("$.items[0].itemType").exists());
    }

    @Test
    void httpEndToEndPreviewActivateIssueTokenAndBatchQuery() throws Exception {
        // 授权 + 写入（带属性 B）
        mockMvc.perform(post("/api/v1/consents/grants").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"g1","subjectKey":"subj-http","purpose":"RESEARCH"}"""))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/records").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"requestId":"w1","subjectKey":"subj-http","purpose":"RESEARCH",
                         "recordKey":"rec-1","payload":"hello","attributeValue":"B"}"""))
                .andExpect(status().isOk());

        String proposalJson = """
                {"catalogVersion":1,"sourcePurpose":"RESEARCH",
                 "sourceScopeValues":["A","B","C"],"migrationKey":"mk-http-flow",
                 "newPurposes":[{"code":"RESEARCH_CLIN","scopeValues":["A","B"]},
                                 {"code":"RESEARCH_OTHER","scopeValues":["C"]}],
                 "effectiveFrom":"%s","effectiveTo":"%s"}
                """.formatted(Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        MvcResult previewResult = mockMvc.perform(post("/api/v1/catalog/migrations/preview")
                        .contentType(MediaType.APPLICATION_JSON).content(proposalJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeGrants[0].subjectKey").value("subj-http"))
                .andExpect(jsonPath("$.records[0].targetPurpose").value("RESEARCH_CLIN"))
                .andReturn();

        var preview = objectMapper.readValue(previewResult.getResponse().getContentAsString(),
                MigrationPreviewResponse.class);
        var activation = activationFromPreview("act-http-flow", preview);
        mockMvc.perform(post("/api/v1/catalog/migrations/activations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(activation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogGeneration").value(2))
                .andExpect(jsonPath("$.reboundRecordCount").value(1));

        MvcResult tokenResult = mockMvc.perform(post("/api/v1/query-generations")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogGeneration").value(2))
                .andReturn();
        String token = objectMapper.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();

        mockMvc.perform(post("/api/v1/records/batch-query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"token":"%s","items":[{"subjectKey":"subj-http",
                                 "purpose":"RESEARCH_CLIN","recordKey":"rec-1"}]}""".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogGeneration").value(2))
                .andExpect(jsonPath("$.results[0].found").value(true))
                .andExpect(jsonPath("$.results[0].payload").value("hello"));
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentMigrationsExactlyOneWinsAndRowsHaveSinglePurpose() throws Exception {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        consentService.write(new RecordWriteRequest("w1", "subj-a", "RESEARCH", "rec-a", "pa", "A"));

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String migrationKey = "mk-concurrent-" + i;
            String requestId = "act-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                MigrationPreviewResponse pv = preview(migrationKey);
                migrationService.activate(activationFromPreview(requestId, pv));
                return "OK";
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> t : tasks) {
            futures.add(pool.submit(t));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int ok = 0;
        int rejected = 0;
        for (Future<String> f : futures) {
            try {
                f.get(20, TimeUnit.SECONDS);
                ok++;
            } catch (Exception e) {
                rejected++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);
        // 只有一个新代次发布；旧用途只 SPLIT 一次；记录不重复、单归属
        assertThat(count("SELECT COUNT(*) FROM catalog_generation")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM catalog_purpose WHERE code = 'RESEARCH'"
                + " AND status = 'SPLIT'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-a'"
                + " AND purpose = 'RESEARCH_CLIN'")).isEqualTo(1);
    }

    @Test
    void migrationConcurrentWithWriteIsSerialized() throws Exception {
        consentService.grant(new GrantRequest("g1", "subj-a", "RESEARCH"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<String> migrationFuture = pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                MigrationPreviewResponse pv = preview("mk-vs-write");
                migrationService.activate(activationFromPreview("act-vs-write", pv));
                return "MIGRATED";
            } catch (RuntimeException e) {
                return "REJECTED";
            }
        });
        Future<String> writeFuture = pool.submit(() -> {
            ready.countDown();
            await(start);
            try {
                consentService.write(new RecordWriteRequest(
                        "w-concurrent", "subj-a", "RESEARCH", "rec-new", "px", "C"));
                return "WRITTEN";
            } catch (RuntimeException e) {
                return "REJECTED";
            }
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        String migrationOutcome = migrationFuture.get(20, TimeUnit.SECONDS);
        String writeOutcome = writeFuture.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        // 合法提交顺序：迁移先成功→写入被拒；写入先于预览→被迁移纳入；
        // 写入落在预览与激活之间→迁移因预览陈旧失败。不会双失败。
        assertThat(migrationOutcome).isIn("MIGRATED", "REJECTED");
        assertThat(writeOutcome).isIn("WRITTEN", "REJECTED");
        assertThat(migrationOutcome.equals("REJECTED") && writeOutcome.equals("REJECTED"))
                .as("迁移与写入不会同时失败").isFalse();
        if (writeOutcome.equals("REJECTED")) {
            // 迁移先提交：新写入被拒，代次为 2，记录不存在
            assertThat(migrationOutcome).isEqualTo("MIGRATED");
            assertThat(count("SELECT COUNT(*) FROM catalog_generation")).isEqualTo(2);
            assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-new'")).isZero();
        }
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE record_key = 'rec-new'")).isLessThanOrEqualTo(1);
        Integer duplicated = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT subject_key, epoch, record_key FROM consent_record"
                        + " GROUP BY subject_key, epoch, record_key HAVING COUNT(*) > 1)", Integer.class);
        assertThat(duplicated).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
