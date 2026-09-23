package com.example.starter.consent.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.consent.ApiException;
import com.example.starter.consent.ConsentRepository;
import com.example.starter.consent.DatabaseFixture;
import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.Purpose;
import com.example.starter.consent.ConsentService;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.migration.dto.BatchQueryRequest;
import com.example.starter.consent.migration.dto.BatchQueryResponse;
import com.example.starter.consent.migration.dto.MigrationActivateRequest;
import com.example.starter.consent.migration.dto.MigrationActivateResponse;
import com.example.starter.consent.migration.dto.MigrationPreviewRequest;
import com.example.starter.consent.migration.dto.MigrationPreviewResponse;
import com.example.starter.consent.migration.dto.PurposeTargetDto;
import com.example.starter.consent.migration.dto.QueryGenerationResponse;

/**
 * 用途拆分迁移与查询代次隔离的真实 H2 数据库测试：
 * 覆盖范围守恒、完整迁移、撤回隔离、查询代次、幂等与并发边界。
 */
@SpringBootTest
class PurposeMigrationIntegrationTest extends DatabaseFixture {

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2031-01-01T00:00:00Z");

    @Autowired
    private PurposeMigrationService migrationService;

    @Autowired
    private ConsentService consentService;

    @Autowired
    private ConsentRepository consentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MigrationPreviewRequest splitRequest(String migrationKey) {
        return new MigrationPreviewRequest(1L, Purpose.RESEARCH, migrationKey, START, END, List.of(
                new PurposeTargetDto("RESEARCH_CORE", 0L, 500_000L, null),
                new PurposeTargetDto("RESEARCH_EXT", 500_000L, 1_000_000L, null)));
    }

    private MigrationActivateRequest activateFromPreview(String requestId,
                                                          MigrationPreviewRequest preview,
                                                          MigrationPreviewResponse snapshot) {
        List<MigrationActivateRequest.ActiveGrantSelection> grants = snapshot.activeGrants().stream()
                .map(g -> new MigrationActivateRequest.ActiveGrantSelection(
                        g.subjectKey(), g.epoch(), g.expectedVersion(), g.targetPurposes()))
                .toList();
        List<MigrationActivateRequest.RecordSelection> records = snapshot.records().stream()
                .map(r -> new MigrationActivateRequest.RecordSelection(
                        r.subjectKey(), r.epoch(), r.recordKey(), r.expectedVersion(), r.targetPurpose()))
                .toList();
        return new MigrationActivateRequest(requestId, preview, grants, records);
    }

    private void grantAndWrite(String subject, long attribute, String recordKey) {
        consentService.grant(new GrantRequest("g-" + subject + "-" + recordKey, subject, Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest(
                "w-" + subject + "-" + recordKey, subject, Purpose.RESEARCH,
                recordKey, "payload-" + recordKey, attribute));
    }

    // ---------- 预览 ----------

    @Test
    void previewListsAllActiveGrantsRevokedGrantsAndRecordsWithoutWriting() {
        grantAndWrite("subj-a", 10L, "rec-core");
        grantAndWrite("subj-b", 600_000L, "rec-ext");
        consentService.grant(new GrantRequest("g-subj-c", "subj-c", Purpose.RESEARCH));

        MigrationPreviewResponse preview = migrationService.preview(splitRequest("mk-1"));

        assertThat(preview.catalogVersion()).isEqualTo(1L);
        assertThat(preview.sourcePurpose()).isEqualTo(Purpose.RESEARCH);
        assertThat(preview.activeGrants()).extracting(MigrationPreviewResponse.ActiveGrantItem::subjectKey)
                .containsExactlyInAnyOrder("subj-a", "subj-b", "subj-c");
        assertThat(preview.activeGrants()).allSatisfy(g -> assertThat(g.targetPurposes())
                .containsExactly("RESEARCH_CORE", "RESEARCH_EXT"));
        assertThat(preview.records()).hasSize(2);
        assertThat(preview.records()).anySatisfy(r -> {
            assertThat(r.recordKey()).isEqualTo("rec-core");
            assertThat(r.targetPurpose()).isEqualTo("RESEARCH_CORE");
            assertThat(r.grantActive()).isTrue();
        });
        assertThat(preview.records()).anySatisfy(r -> {
            assertThat(r.recordKey()).isEqualTo("rec-ext");
            assertThat(r.targetPurpose()).isEqualTo("RESEARCH_EXT");
        });
        // 预览只读：目录仍只有代次 1，无迁移证据
        assertThat(count("SELECT COUNT(*) FROM purpose_catalog_generation")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM purpose_migration")).isZero();
    }

    @Test
    void previewMapsGapAttributeToUnmappedAndKeepsRevokedDataOnHistoricalPurpose() {
        grantAndWrite("subj-a", 10L, "rec-a");
        consentService.grant(new GrantRequest("g-subj-b", "subj-b", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest("w-b", "subj-b", Purpose.RESEARCH,
                "rec-b", "payload-b", 20L));
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-b", "subj-b", Purpose.RESEARCH, 1));

        MigrationPreviewRequest gapSplit = new MigrationPreviewRequest(1L, Purpose.RESEARCH, "mk-gap",
                START, END, List.of(
                new PurposeTargetDto("RESEARCH_CORE", 0L, 50L, null),
                new PurposeTargetDto("RESEARCH_EXT", 60L, 100L, null)));
        // 在拆分空隙再写一条属性 55 的记录
        consentService.grant(new GrantRequest("g-subj-c", "subj-c", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest("w-c", "subj-c", Purpose.RESEARCH,
                "rec-c", "payload-c", 55L));

        MigrationPreviewResponse preview = migrationService.preview(gapSplit);

        assertThat(preview.revokedGrants()).hasSize(1);
        assertThat(preview.revokedGrants().get(0).subjectKey()).isEqualTo("subj-b");
        assertThat(preview.records()).anySatisfy(r -> {
            assertThat(r.recordKey()).isEqualTo("rec-b");
            assertThat(r.grantActive()).isFalse();
            assertThat(r.targetPurpose()).isEqualTo(Purpose.RESEARCH);
        });
        assertThat(preview.records()).anySatisfy(r -> {
            assertThat(r.recordKey()).isEqualTo("rec-c");
            assertThat(r.grantActive()).isTrue();
            assertThat(r.targetPurpose()).isEqualTo("UNMAPPED");
        });
    }

    // ---------- 范围守恒失败分支 ----------

    @Test
    void activateRejectsRangeThatExpandsBeyondSource() {
        MigrationPreviewRequest expanding = new MigrationPreviewRequest(1L, Purpose.RESEARCH, "mk-expand",
                START, END, List.of(
                new PurposeTargetDto("A", 0L, 500_000L, null),
                new PurposeTargetDto("B", 500_000L, 1_000_001L, null)));
        assertThatThrownBy(() -> migrationService.preview(expanding))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("越出旧用途范围");
    }

    @Test
    void activateRejectsOverlappingRanges() {
        MigrationPreviewRequest overlapping = new MigrationPreviewRequest(1L, Purpose.RESEARCH, "mk-overlap",
                START, END, List.of(
                new PurposeTargetDto("A", 0L, 600_000L, null),
                new PurposeTargetDto("B", 500_000L, 1_000_000L, null)));
        assertThatThrownBy(() -> migrationService.preview(overlapping))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重叠");
    }

    @Test
    void previewRejectsStaleCatalogVersion() {
        MigrationPreviewRequest stale = new MigrationPreviewRequest(99L, Purpose.RESEARCH, "mk-stale",
                START, END, List.of(
                new PurposeTargetDto("A", 0L, 50L, null),
                new PurposeTargetDto("B", 50L, 100L, null)));
        assertThatThrownBy(() -> migrationService.preview(stale))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void previewRejectsInvalidEffectiveWindow() {
        MigrationPreviewRequest badWindow = new MigrationPreviewRequest(1L, Purpose.RESEARCH, "mk-window",
                END, START, List.of(
                new PurposeTargetDto("A", 0L, 50L, null),
                new PurposeTargetDto("B", 50L, 100L, null)));
        assertThatThrownBy(() -> migrationService.preview(badWindow))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("左闭右开");
    }

    @Test
    void cannotSplitAlreadySupersededPurpose() {
        MigrationPreviewResponse snapshot = migrationService.preview(splitRequest("mk-1"));
        migrationService.activate(activateFromPreview("act-1", splitRequest("mk-1"), snapshot));

        MigrationPreviewRequest again = new MigrationPreviewRequest(2L, Purpose.RESEARCH, "mk-2",
                START, END, List.of(
                new PurposeTargetDto("RESEARCH_X", 0L, 50L, null),
                new PurposeTargetDto("RESEARCH_Y", 50L, 100L, null)));
        assertThatThrownBy(() -> migrationService.preview(again))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ACTIVE");
    }

    // ---------- 完整迁移主流程 ----------

    @Test
    void activatePublishesNewGenerationSplitsGrantsAndRebindsDataInOneTransaction() {
        grantAndWrite("subj-a", 10L, "rec-core");
        grantAndWrite("subj-b", 600_000L, "rec-ext");

        MigrationPreviewRequest previewRequest = splitRequest("mk-full");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        MigrationActivateResponse response =
                migrationService.activate(activateFromPreview("act-full", previewRequest, snapshot));

        assertThat(response.catalogVersion()).isEqualTo(1L);
        assertThat(response.catalogGeneration()).isEqualTo(2L);
        assertThat(response.targetPurposes()).containsExactly("RESEARCH_CORE", "RESEARCH_EXT");
        assertThat(response.migratedGrants()).isEqualTo(2);
        assertThat(response.newGrants()).isEqualTo(4);
        assertThat(response.reboundRecords()).isEqualTo(2);
        assertThat(response.unmappedRecords()).isZero();
        assertThat(response.isolatedRecords()).isZero();

        // 旧授权 MIGRATED，不可再用于新查询
        assertThat(consentRepository.findGrant("subj-a", Purpose.RESEARCH, 1).orElseThrow().status())
                .isEqualTo(GrantStatus.MIGRATED);
        // 每个有效授权按其原范围拆成全部新用途的新代次授权
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'ACTIVE'"
                + " AND catalog_generation = 2")).isEqualTo(4);
        // 数据一次性改绑：任一数据行只有一个活动用途归属
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH_CORE'"
                + " AND catalog_generation = 2 AND epoch = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH_EXT'"
                + " AND catalog_generation = 2 AND epoch = 1")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH'")).isZero();
        // 新代次目录中旧用途 SUPERSEDED、新用途 ACTIVE
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM purpose_catalog_entry WHERE catalog_generation = 2 AND purpose = 'RESEARCH'",
                String.class)).isEqualTo("SUPERSEDED");
        assertThat(count("SELECT COUNT(*) FROM purpose_catalog_entry WHERE catalog_generation = 2"
                + " AND status = 'ACTIVE'")).isEqualTo(3);
    }

    @Test
    void unmappedActiveRecordsRemainOnOldPurposeAndAreInvisibleInNewGeneration() {
        grantAndWrite("subj-a", 10L, "rec-in");
        grantAndWrite("subj-b", 55L, "rec-gap");

        MigrationPreviewRequest gapSplit = new MigrationPreviewRequest(1L, Purpose.RESEARCH, "mk-gap",
                START, END, List.of(
                new PurposeTargetDto("RESEARCH_CORE", 0L, 50L, null),
                new PurposeTargetDto("RESEARCH_EXT", 60L, 100L, null)));
        MigrationPreviewResponse snapshot = migrationService.preview(gapSplit);
        MigrationActivateResponse response =
                migrationService.activate(activateFromPreview("act-gap", gapSplit, snapshot));

        assertThat(response.reboundRecords()).isEqualTo(1);
        assertThat(response.unmappedRecords()).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH'"
                + " AND record_key = 'rec-gap'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE purpose = 'RESEARCH_CORE'")).isEqualTo(1);
    }

    // ---------- 撤回隔离 ----------

    @Test
    void revokedGrantsAndIsolatedDataKeepHistoricalPurposeAndCannotRecoverViaMigration() {
        consentService.grant(new GrantRequest("g-a", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest("w-a", "subj-a", Purpose.RESEARCH,
                "rec-a", "payload-a", 10L));
        consentService.grant(new GrantRequest("g-b", "subj-b", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest("w-b", "subj-b", Purpose.RESEARCH,
                "rec-b", "payload-b", 10L));
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-b", "subj-b", Purpose.RESEARCH, 1));

        MigrationPreviewRequest previewRequest = splitRequest("mk-revoked");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        assertThat(snapshot.activeGrants()).hasSize(1);
        assertThat(snapshot.revokedGrants()).hasSize(1);

        MigrationActivateResponse response =
                migrationService.activate(activateFromPreview("act-rev", previewRequest, snapshot));
        assertThat(response.isolatedRecords()).isEqualTo(1);
        assertThat(response.migratedGrants()).isEqualTo(1);
        assertThat(response.newGrants()).isEqualTo(2);

        // 已撤回授权及其隔离数据保留历史旧用途，未改绑、未在新用途下签发授权
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-b'"
                + " AND purpose = 'RESEARCH' AND status = 'REVOKED'")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE subject_key = 'subj-b'"
                + " AND purpose IN ('RESEARCH_CORE','RESEARCH_EXT')")).isZero();
        assertThat(count("SELECT COUNT(*) FROM consent_record WHERE subject_key = 'subj-b'"
                + " AND purpose = 'RESEARCH'")).isEqualTo(1);
        // 历史撤回链不改写：撤回行仍是 REVOKED 而非 MIGRATED
        assertThat(consentRepository.findGrant("subj-b", Purpose.RESEARCH, 1).orElseThrow().status())
                .isEqualTo(GrantStatus.REVOKED);
    }

    @Test
    void revokedIsolatedRecordSubmittedWithNewPurposeIsRejected() {
        consentService.grant(new GrantRequest("g-b", "subj-b", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest("w-b", "subj-b", Purpose.RESEARCH,
                "rec-b", "payload-b", 10L));
        consentService.revoke(new com.example.starter.consent.dto.RevokeRequest(
                "r-b", "subj-b", Purpose.RESEARCH, 1));

        MigrationPreviewRequest previewRequest = splitRequest("mk-badiso");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        // 篡改：把隔离数据的目标用途提交成新用途，意图借迁移恢复
        List<MigrationActivateRequest.RecordSelection> tampered = snapshot.records().stream()
                .map(r -> new MigrationActivateRequest.RecordSelection(r.subjectKey(), r.epoch(),
                        r.recordKey(), r.expectedVersion(),
                        r.grantActive() ? r.targetPurpose() : "RESEARCH_CORE"))
                .toList();
        MigrationActivateRequest badRequest = new MigrationActivateRequest("act-badiso", previewRequest,
                List.of(), tampered);
        assertThatThrownBy(() -> migrationService.activate(badRequest))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不一致");
        assertThat(count("SELECT COUNT(*) FROM purpose_migration")).isZero();
    }

    // ---------- 激活提交校验：遗漏/多余/版本/映射 ----------

    @Test
    void activateMissingActiveGrantSelectionIsRejected() {
        grantAndWrite("subj-a", 10L, "rec-a");
        grantAndWrite("subj-b", 10L, "rec-b");

        MigrationPreviewRequest previewRequest = splitRequest("mk-miss");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        MigrationActivateRequest request = activateFromPreview("act-miss", previewRequest, snapshot);
        List<MigrationActivateRequest.ActiveGrantSelection> dropped = request.activeGrants().stream()
                .limit(1).toList();
        MigrationActivateRequest missing = new MigrationActivateRequest(
                request.requestId(), request.preview(), dropped, request.records());
        assertThatThrownBy(() -> migrationService.activate(missing))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("遗漏");
        assertThat(count("SELECT COUNT(*) FROM purpose_migration")).isZero();
    }

    @Test
    void activateWithStaleExpectedVersionIsRejectedAfterAttributeChange() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest previewRequest = splitRequest("mk-stale-ver");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);

        // 模拟预览后记录属性发生变化（版本前进）
        jdbcTemplate.update("UPDATE consent_record SET version = version + 1, record_attribute = 20"
                + " WHERE record_key = 'rec-a'");

        assertThatThrownBy(() ->
                migrationService.activate(activateFromPreview("act-stale", previewRequest, snapshot)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("版本");
        assertThat(count("SELECT COUNT(*) FROM purpose_catalog_generation")).isEqualTo(1);
    }

    @Test
    void activateWithWrongMappingTargetIsRejected() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest previewRequest = splitRequest("mk-wrongmap");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);

        List<MigrationActivateRequest.RecordSelection> wrong = snapshot.records().stream()
                .map(r -> new MigrationActivateRequest.RecordSelection(r.subjectKey(), r.epoch(),
                        r.recordKey(), r.expectedVersion(), "RESEARCH_EXT"))
                .toList();
        MigrationActivateRequest request = new MigrationActivateRequest("act-wrong", previewRequest,
                snapshot.activeGrants().stream()
                        .map(g -> new MigrationActivateRequest.ActiveGrantSelection(
                                g.subjectKey(), g.epoch(), g.expectedVersion(), g.targetPurposes()))
                        .toList(),
                wrong);
        assertThatThrownBy(() -> migrationService.activate(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void failedActivationDoesNotConsumeRequestIdOrMigrationKey() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest previewRequest = splitRequest("mk-retry");
        MigrationPreviewResponse badSnapshot = migrationService.preview(previewRequest);
        MigrationActivateRequest bad = activateFromPreview("act-retry", previewRequest, badSnapshot);
        MigrationActivateRequest droppedGrant = new MigrationActivateRequest(bad.requestId(),
                bad.preview(), List.of(), bad.records());
        assertThatThrownBy(() -> migrationService.activate(droppedGrant))
                .isInstanceOf(ApiException.class);

        // 失败不占键：同一 requestId 与 migrationKey 可用完整提交重试成功
        MigrationPreviewResponse freshSnapshot = migrationService.preview(previewRequest);
        MigrationActivateResponse response =
                migrationService.activate(activateFromPreview("act-retry", previewRequest, freshSnapshot));
        assertThat(response.catalogGeneration()).isEqualTo(2L);
    }

    // ---------- 查询代次隔离 ----------

    @Test
    void queryGenerationPinsCatalogGenerationAndRejectsMixedAndStaleReads() {
        grantAndWrite("subj-a", 10L, "rec-a");

        QueryGenerationResponse before = migrationService.issueQueryGeneration();
        assertThat(before.catalogGeneration()).isEqualTo(1L);
        BatchQueryResponse batchBefore = migrationService.batchQuery(new BatchQueryRequest(
                before.queryGeneration(), List.of(
                new BatchQueryRequest.BatchQueryItem("subj-a", Purpose.RESEARCH, "rec-a"))));
        assertThat(batchBefore.results()).hasSize(1);
        assertThat(batchBefore.results().get(0).found()).isTrue();
        assertThat(batchBefore.results().get(0).payload()).isEqualTo("payload-rec-a");

        MigrationPreviewRequest previewRequest = splitRequest("mk-query");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        migrationService.activate(activateFromPreview("act-query", previewRequest, snapshot));

        // 迁移前签发的查询代次在生效后拒绝
        assertThatThrownBy(() -> migrationService.batchQuery(new BatchQueryRequest(
                before.queryGeneration(), List.of(
                new BatchQueryRequest.BatchQueryItem("subj-a", "RESEARCH_CORE", "rec-a")))))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.CONFLICT)
                .hasMessageContaining("目录代次已失效");

        // 新查询代次固定新目录，可读到改绑后的数据；同一批不允许混读旧用途（旧用途在新代次查不到）
        QueryGenerationResponse after = migrationService.issueQueryGeneration();
        assertThat(after.catalogGeneration()).isEqualTo(2L);
        BatchQueryResponse mixed = migrationService.batchQuery(new BatchQueryRequest(
                after.queryGeneration(), List.of(
                new BatchQueryRequest.BatchQueryItem("subj-a", "RESEARCH_CORE", "rec-a"),
                new BatchQueryRequest.BatchQueryItem("subj-a", Purpose.RESEARCH, "rec-a"))));
        assertThat(mixed.results().get(0).found()).isTrue();
        assertThat(mixed.results().get(0).purpose()).isEqualTo("RESEARCH_CORE");
        assertThat(mixed.results().get(1).found()).isFalse();

        // 不存在的查询代次 404
        assertThatThrownBy(() -> migrationService.batchQuery(new BatchQueryRequest(
                999_999L, List.of())))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    // ---------- 沿用用途不被误隔离 ----------

    @Test
    void unaffectedPurposeKeepsWorkingAcrossGenerationsWithoutReGrant() {
        // RESEARCH 与 PERSONALIZATION 各自授权写数据
        consentService.grant(new GrantRequest("g-r", "subj-a", Purpose.RESEARCH));
        consentService.write(new RecordWriteRequest("w-r", "subj-a", Purpose.RESEARCH,
                "rec-r", "research", 10L));
        consentService.grant(new GrantRequest("g-p", "subj-a", Purpose.PERSONALIZATION));
        consentService.write(new RecordWriteRequest("w-p", "subj-a", Purpose.PERSONALIZATION,
                "rec-p", "personal", 10L));

        MigrationPreviewRequest previewRequest = splitRequest("mk-carry");
        migrationService.activate(activateFromPreview(
                "act-carry", previewRequest, migrationService.preview(previewRequest)));

        // PERSONALIZATION 未参与拆分：迁移后无需重新授权即可继续写入
        consentService.write(new RecordWriteRequest("w-p-2", "subj-a", Purpose.PERSONALIZATION,
                "rec-p-2", "personal-2", 20L));
        // 新查询代次下 PERSONALIZATION 历史数据与新数据均可读，RESEARCH 旧用途读不到（不混读）
        QueryGenerationResponse qg = migrationService.issueQueryGeneration();
        BatchQueryResponse batch = migrationService.batchQuery(new BatchQueryRequest(
                qg.queryGeneration(), List.of(
                new BatchQueryRequest.BatchQueryItem("subj-a", Purpose.PERSONALIZATION, "rec-p"),
                new BatchQueryRequest.BatchQueryItem("subj-a", Purpose.PERSONALIZATION, "rec-p-2"),
                new BatchQueryRequest.BatchQueryItem("subj-a", Purpose.RESEARCH, "rec-r"))));
        assertThat(batch.results().get(0).found()).isTrue();
        assertThat(batch.results().get(1).found()).isTrue();
        assertThat(batch.results().get(2).found()).isFalse();
    }

    // ---------- 幂等 ----------
    @Test
    void activateReplaySameRequestIdReturnsFirstSnapshotAndDoesNotDoubleApply() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest previewRequest = splitRequest("mk-idem");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        MigrationActivateRequest request = activateFromPreview("act-idem", previewRequest, snapshot);

        MigrationActivateResponse first = migrationService.activate(request);
        MigrationActivateResponse replay = migrationService.activate(request);
        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM purpose_migration")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM purpose_catalog_generation")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM consent_grant WHERE status = 'ACTIVE'")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM idempotency_request WHERE request_id = 'act-idem'")).isEqualTo(1);
    }

    @Test
    void activateWithReorderedCollectionsIsEquivalent() {
        grantAndWrite("subj-a", 10L, "rec-a");
        grantAndWrite("subj-b", 10L, "rec-b");
        MigrationPreviewRequest previewRequest = splitRequest("mk-order");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        MigrationActivateRequest request = activateFromPreview("act-order", previewRequest, snapshot);

        // 颠倒授权与记录确认集合顺序，并交换目标用途列表顺序
        List<MigrationActivateRequest.ActiveGrantSelection> reversedGrants = new ArrayList<>(request.activeGrants());
        java.util.Collections.reverse(reversedGrants);
        List<MigrationActivateRequest.RecordSelection> reversedRecords = new ArrayList<>(request.records());
        java.util.Collections.reverse(reversedRecords);
        List<PurposeTargetDto> reversedTargets = new ArrayList<>(previewRequest.targets());
        java.util.Collections.reverse(reversedTargets);
        MigrationPreviewRequest reversedPreview = new MigrationPreviewRequest(
                previewRequest.catalogVersion(), previewRequest.sourcePurpose(),
                previewRequest.migrationKey(), previewRequest.effectiveStart(),
                previewRequest.effectiveEnd(), reversedTargets);

        MigrationActivateResponse first = migrationService.activate(request);
        MigrationActivateResponse replay = migrationService.activate(
                new MigrationActivateRequest("act-order", reversedPreview, reversedGrants, reversedRecords));
        assertThat(replay).isEqualTo(first);
    }

    @Test
    void activateSameRequestIdWithDifferentParamsReturnsConflict() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest previewRequest = splitRequest("mk-diff");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        migrationService.activate(activateFromPreview("act-diff", previewRequest, snapshot));

        MigrationPreviewRequest other = new MigrationPreviewRequest(2L, Purpose.RESEARCH, "mk-other",
                START, END, List.of(
                new PurposeTargetDto("X", 0L, 50L, null),
                new PurposeTargetDto("Y", 50L, 100L, null)));
        MigrationActivateRequest changed = new MigrationActivateRequest("act-diff", other, List.of(), List.of());
        assertThatThrownBy(() -> migrationService.activate(changed))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.CONFLICT);
    }

    @Test
    void migrationKeyMustBeGloballyUnique() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest previewRequest = splitRequest("mk-unique");
        MigrationPreviewResponse snapshot = migrationService.preview(previewRequest);
        migrationService.activate(activateFromPreview("act-1", previewRequest, snapshot));

        // 目录再拆分 RESEARCH_EXT 后复用旧 migrationKey
        MigrationPreviewRequest second = new MigrationPreviewRequest(2L, "RESEARCH_EXT", "mk-unique",
                START, END, List.of(
                new PurposeTargetDto("EXT_A", 500_000L, 750_000L, null),
                new PurposeTargetDto("EXT_B", 750_000L, 1_000_000L, null)));
        MigrationPreviewResponse snapshot2 = migrationService.preview(second);
        assertThatThrownBy(() -> migrationService.activate(activateFromPreview("act-2", second, snapshot2)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("migrationKey");
    }

    // ---------- 证据查询 ----------

    @Test
    void evidenceIsReadOnlyAndStablySorted() {
        grantAndWrite("subj-a", 10L, "rec-a");
        MigrationPreviewRequest first = splitRequest("mk-ev-1");
        migrationService.activate(activateFromPreview("act-ev-1", first, migrationService.preview(first)));
        MigrationPreviewRequest second = new MigrationPreviewRequest(2L, "RESEARCH_EXT", "mk-ev-2",
                START, END, List.of(
                new PurposeTargetDto("EXT_A", 500_000L, 750_000L, null),
                new PurposeTargetDto("EXT_B", 750_000L, 1_000_000L, null)));
        migrationService.activate(activateFromPreview("act-ev-2", second, migrationService.preview(second)));

        var evidence = migrationService.evidence();
        assertThat(evidence.migrations()).hasSize(2);
        assertThat(evidence.migrations()).extracting(e -> e.catalogGeneration())
                .containsExactly(2L, 3L);
        assertThat(evidence.migrations().get(0).targets()).hasSize(2);
        assertThat(evidence.migrations().get(0).targets().get(0).ordinal()).isZero();
        // 只读：连续两次查询结果一致
        assertThat(migrationService.evidence()).isEqualTo(evidence);
    }

    // ---------- 并发边界 ----------

    @Test
    void concurrentMigrationsCommitInOrderWithExactlyOneWinner() throws Exception {
        grantAndWrite("subj-a", 10L, "rec-a");
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "mk-concurrent-" + i;
            String requestId = "act-concurrent-" + i;
            tasks.add(() -> {
                ready.countDown();
                start.await();
                MigrationPreviewRequest req = splitRequest(key);
                MigrationPreviewResponse snap = migrationService.preview(req);
                try {
                    MigrationActivateResponse resp =
                            migrationService.activate(activateFromPreview(requestId, req, snap));
                    return "OK:" + resp.catalogGeneration();
                } catch (ApiException ex) {
                    return "FAIL:" + ex.getCode();
                }
            });
        }
        List<Future<String>> futures = new ArrayList<>();
        for (Callable<String> task : tasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        long okCount = outcomes.stream().filter(o -> o.startsWith("OK:")).count();
        assertThat(okCount).isEqualTo(1);
        assertThat(outcomes).contains("OK:2");
        assertThat(outcomes.stream().filter(o -> !o.startsWith("OK:")))
                .allSatisfy(o -> assertThat(o).contains("CATALOG_VERSION_CONFLICT"));
        assertThat(count("SELECT COUNT(*) FROM purpose_migration")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM purpose_catalog_generation WHERE catalog_generation = 2"))
                .isEqualTo(1);
    }

    @Test
    void concurrentMigrationAndGrantNeverLeaveActiveGrantOnSupersededPurpose() throws Exception {
        consentService.grant(new GrantRequest("g-init", "subj-init", Purpose.RESEARCH));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads * 2);
        CountDownLatch ready = new CountDownLatch(threads + 1);
        CountDownLatch start = new CountDownLatch(1);

        Callable<String> migrationTask = () -> {
            ready.countDown();
            start.await();
            MigrationPreviewRequest req = splitRequest("mk-vs-grant");
            try {
                MigrationPreviewResponse snap = migrationService.preview(req);
                migrationService.activate(activateFromPreview("act-vs-grant", req, snap));
                return "MIGRATED";
            } catch (ApiException ex) {
                return "MIG_FAIL:" + ex.getCode();
            }
        };
        List<Callable<String>> grantTasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String subject = "subj-new-" + i;
            grantTasks.add(() -> {
                ready.countDown();
                start.await();
                try {
                    consentService.grant(new GrantRequest("g-new-" + subject, subject, Purpose.RESEARCH));
                    return "GRANTED";
                } catch (ApiException ex) {
                    return "GRANT_FAIL:" + ex.getCode();
                }
            });
        }

        List<Future<String>> futures = new ArrayList<>();
        futures.add(pool.submit(migrationTask));
        for (Callable<String> task : grantTasks) {
            futures.add(pool.submit(task));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> future : futures) {
            outcomes.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // 迁移必然成功（或授权全部先于迁移落地，则迁移应因预览遗漏失败——二者必居其一且数据自洽）
        long migrated = outcomes.stream().filter("MIGRATED"::equals).count();
        long migrationFailures = outcomes.stream().filter(o -> o.startsWith("MIG_FAIL")).count();
        assertThat(migrated + migrationFailures).isEqualTo(1);

        // 核心不变量 1：任一 ACTIVE 授权在其所属目录代次中的用途条目必须仍为 ACTIVE，
        // 即不会有新授权落在已被拆分（SUPERSEDED）的用途上
        Integer staleActive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM consent_grant g"
                        + " JOIN purpose_catalog_entry e"
                        + " ON e.catalog_generation = g.catalog_generation AND e.purpose = g.purpose"
                        + " WHERE g.status = 'ACTIVE' AND e.status <> 'ACTIVE'",
                Integer.class);
        assertThat(staleActive).isZero();
        // 核心不变量 2：同一主体/代次/记录键在同一目录代次至多一个活动用途归属
        Integer duplicated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT subject_key, epoch, record_key FROM consent_record"
                        + " GROUP BY subject_key, epoch, record_key, catalog_generation"
                        + " HAVING COUNT(*) > 1)",
                Integer.class);
        assertThat(duplicated).isZero();
    }
}
