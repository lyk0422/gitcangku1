package com.example.starter.consent.migration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ApiException;
import com.example.starter.consent.ConsentRepository;
import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.IdempotencyRepository;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.migration.dto.BatchQueryRequest;
import com.example.starter.consent.migration.dto.BatchQueryResponse;
import com.example.starter.consent.migration.dto.MigrationActivateRequest;
import com.example.starter.consent.migration.dto.MigrationActivateResponse;
import com.example.starter.consent.migration.dto.MigrationEvidenceResponse;
import com.example.starter.consent.migration.dto.MigrationPreviewRequest;
import com.example.starter.consent.migration.dto.MigrationPreviewResponse;
import com.example.starter.consent.migration.dto.PurposeTargetDto;
import com.example.starter.consent.migration.dto.QueryGenerationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用途目录拆分迁移服务：预览、激活、迁移证据查询、查询代次签发与固定代次批量查询。
 *
 * <p>并发控制：激活事务先锁最新目录代次行（迁移间按提交顺序串行），再锁被拆分用途的目录
 * 条目行（与该用途的授权/写入互斥），最后锁定绑定该用途的全部授权与记录行，保证任一数据行
 * 始终只有一个活动用途归属。
 */
@Service
public class PurposeMigrationService {

    /** 记录属性未命中任何新用途范围时的映射结果。 */
    public static final String UNMAPPED = "UNMAPPED";

    static final String CODE_MIGRATION_INVALID = "MIGRATION_INVALID";
    static final String CODE_CATALOG_VERSION_CONFLICT = "CATALOG_VERSION_CONFLICT";
    static final String CODE_SOURCE_PURPOSE_NOT_ACTIVE = "SOURCE_PURPOSE_NOT_ACTIVE";
    static final String CODE_MIGRATION_KEY_CONFLICT = "MIGRATION_KEY_CONFLICT";
    static final String CODE_PREVIEW_MISMATCH = "MIGRATION_PREVIEW_MISMATCH";
    static final String CODE_VERSION_CONFLICT = "MIGRATION_VERSION_CONFLICT";
    static final String CODE_QUERY_GENERATION_STALE = "QUERY_GENERATION_STALE";
    static final String CODE_QUERY_GENERATION_NOT_FOUND = "QUERY_GENERATION_NOT_FOUND";

    private static final String OP_MIGRATION_ACTIVATE = "PURPOSE_MIGRATION_ACTIVATION";
    private static final int NEW_GRANT_EPOCH = 1;

    private final CatalogRepository catalogRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final TimeProvider timeProvider;
    private final ObjectMapper objectMapper;

    public PurposeMigrationService(CatalogRepository catalogRepository,
                                   ConsentRepository consentRepository,
                                   IdempotencyRepository idempotencyRepository,
                                   TimeProvider timeProvider,
                                   ObjectMapper objectMapper) {
        this.catalogRepository = catalogRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.timeProvider = timeProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 预览：只读列出绑定旧用途的全部有效授权、已撤回授权和数据记录及映射结果，不写数据。
     */
    @Transactional(readOnly = true)
    public MigrationPreviewResponse preview(MigrationPreviewRequest request) {
        PreparedMigration prepared = prepare(request, false);
        return buildPreview(prepared);
    }

    /**
     * 激活：一个事务内发布新目录代次、拆分有效授权、一次性改绑可迁移数据。
     * 同 requestId 同参重放返回首次快照；集合换序等价；异参 409；失败不占 requestId。
     */
    @Transactional
    public MigrationActivateResponse activate(MigrationActivateRequest request) {
        String fingerprint = canonicalFingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), MigrationActivateResponse.class);
        }
        if (catalogRepository.findMigrationByKey(request.preview().migrationKey()).isPresent()) {
            throw ApiException.conflict(CODE_MIGRATION_KEY_CONFLICT,
                    "migrationKey 已存在: " + request.preview().migrationKey());
        }

        long latestGeneration = catalogRepository.latestGeneration();
        if (request.preview().catalogVersion() == null
                || request.preview().catalogVersion() != latestGeneration) {
            throw ApiException.conflict(CODE_CATALOG_VERSION_CONFLICT,
                    "catalogVersion 不是当前最新目录代次");
        }
        // 先锁最新目录代次行：并发目录迁移在此按提交顺序串行
        catalogRepository.lockGeneration(latestGeneration)
                .orElseThrow(() -> ApiException.conflict(CODE_CATALOG_VERSION_CONFLICT, "目录代次不存在"));
        if (catalogRepository.latestGeneration() != latestGeneration) {
            throw ApiException.conflict(CODE_CATALOG_VERSION_CONFLICT, "目录版本已变化，请重新预览");
        }

        // 再锁旧用途条目并做静态校验：与该用途的授权/写入/撤回互斥
        PreparedMigration prepared = prepare(request.preview(), true);
        long newGeneration = latestGeneration + 1;

        // 最后锁定绑定旧用途的全部授权与记录，以当前数据为准校验激活提交
        List<ConsentRepository.GrantRow> grants =
                consentRepository.lockAllGrantsByPurpose(prepared.sourcePurpose());
        List<ConsentRepository.RecordRow> records =
                consentRepository.lockAllRecordsByPurpose(prepared.sourcePurpose());

        Map<GrantKey, ConsentRepository.GrantRow> activeGrants = new HashMap<>();
        for (ConsentRepository.GrantRow grant : grants) {
            if (grant.status() == GrantStatus.ACTIVE) {
                activeGrants.put(new GrantKey(grant.subjectKey(), grant.epoch()), grant);
            }
        }

        validateActiveGrantSelections(request, prepared, activeGrants);
        Map<RecordKey, String> recordMappings = validateRecordSelections(request, prepared, records, activeGrants);

        // ---- 一个事务内发布新目录代次 ----
        // 旧代次的被拆分用途置为 SUPERSEDED，使等待其条目行锁的授权/写入事务立即失败
        catalogRepository.updateEntryStatus(latestGeneration, prepared.sourcePurpose(), "SUPERSEDED");
        catalogRepository.insertGeneration(new CatalogRepository.GenerationRow(
                newGeneration, request.preview().effectiveStart(), request.preview().effectiveEnd(),
                request.preview().migrationKey()));
        for (CatalogRepository.EntryRow entry : catalogRepository.findEntries(latestGeneration)) {
            String status = entry.purpose().equals(prepared.sourcePurpose()) ? "SUPERSEDED" : entry.status();
            catalogRepository.insertEntry(new CatalogRepository.EntryRow(
                    newGeneration, entry.purpose(), entry.range(), entry.supersedes(), status));
        }
        List<String> targetCodes = new ArrayList<>();
        for (PurposeTarget target : prepared.targets()) {
            catalogRepository.insertEntry(new CatalogRepository.EntryRow(
                    newGeneration, target.purpose(), target.range(), target.supersedes(), "ACTIVE"));
            targetCodes.add(target.purpose());
        }
        targetCodes.sort(String::compareTo);

        // ---- 有效授权置 MIGRATED，并按其原范围拆分为全部新用途的下一代授权 ----
        int migratedGrants = 0;
        int newGrants = 0;
        for (ConsentRepository.GrantRow grant : activeGrants.values()) {
            if (!consentRepository.markGrantMigrated(
                    grant.subjectKey(), grant.purpose(), grant.epoch(), grant.version())) {
                throw ApiException.conflict(CODE_VERSION_CONFLICT,
                        "授权在预览后发生变化: " + grant.subjectKey() + "#" + grant.epoch());
            }
            migratedGrants++;
            for (PurposeTarget target : prepared.targets()) {
                consentRepository.insertGrant(grant.subjectKey(), target.purpose(), NEW_GRANT_EPOCH,
                        request.requestId(), newGeneration);
                newGrants++;
            }
        }

        // ---- 一次性改绑可迁移数据；UNMAPPED 与已撤回隔离数据保留历史旧用途 ----
        int reboundRecords = 0;
        int unmappedRecords = 0;
        int isolatedRecords = 0;
        for (ConsentRepository.RecordRow record : records) {
            RecordKey key = new RecordKey(record.subjectKey(), record.epoch(), record.recordKey());
            boolean grantActive = activeGrants.containsKey(new GrantKey(record.subjectKey(), record.epoch()));
            if (!grantActive) {
                isolatedRecords++;
                continue;
            }
            String mapping = recordMappings.get(key);
            if (UNMAPPED.equals(mapping)) {
                unmappedRecords++;
                continue;
            }
            if (!consentRepository.rebindRecord(record.subjectKey(), record.purpose(), record.epoch(),
                    record.recordKey(), record.version(), mapping, NEW_GRANT_EPOCH, newGeneration)) {
                throw ApiException.conflict(CODE_VERSION_CONFLICT,
                        "记录在预览后发生变化: " + record.subjectKey() + "/" + record.recordKey());
            }
            reboundRecords++;
        }

        // ---- 迁移证据（不可变，migrationKey 唯一）----
        try {
            catalogRepository.insertMigration(new CatalogRepository.MigrationRow(
                    request.preview().migrationKey(), latestGeneration, newGeneration,
                    prepared.sourcePurpose(), prepared.sourceRange(),
                    request.preview().effectiveStart(), request.preview().effectiveEnd(),
                    "APPLIED", request.requestId(), timeProvider.now()));
        } catch (DuplicateKeyException duplicateKey) {
            throw ApiException.conflict(CODE_MIGRATION_KEY_CONFLICT,
                    "migrationKey 已存在: " + request.preview().migrationKey());
        }
        int ordinal = 0;
        for (PurposeTargetDto target : request.preview().targets()) {
            String supersedes = (target.supersedes() == null || target.supersedes().isBlank())
                    ? prepared.sourcePurpose() : target.supersedes();
            catalogRepository.insertTarget(new CatalogRepository.TargetRow(
                    request.preview().migrationKey(), target.purpose(),
                    new LongRange(target.rangeStart(), target.rangeEnd()), supersedes, ordinal++));
        }

        MigrationActivateResponse response = new MigrationActivateResponse(
                request.preview().migrationKey(), latestGeneration, newGeneration,
                prepared.sourcePurpose(), request.preview().effectiveStart(),
                request.preview().effectiveEnd(), targetCodes, migratedGrants, newGrants,
                reboundRecords, unmappedRecords, isolatedRecords);
        storeSuccess(request.requestId(), fingerprint, response);
        return response;
    }

    /**
     * 迁移证据只读查询，按 catalogGeneration 稳定排序。
     */
    @Transactional(readOnly = true)
    public MigrationEvidenceResponse evidence() {
        List<MigrationEvidenceResponse.MigrationEvidence> items = new ArrayList<>();
        for (CatalogRepository.MigrationRow row : catalogRepository.findAllMigrations()) {
            List<MigrationEvidenceResponse.TargetView> targets = catalogRepository.findTargets(row.migrationKey())
                    .stream()
                    .map(t -> new MigrationEvidenceResponse.TargetView(
                            t.purpose(), t.range().start(), t.range().end(), t.supersedes(), t.ordinal()))
                    .toList();
            items.add(new MigrationEvidenceResponse.MigrationEvidence(
                    row.migrationKey(), row.catalogVersion(), row.catalogGeneration(),
                    row.sourcePurpose(), row.sourceRange().start(), row.sourceRange().end(),
                    row.effectiveStart(), row.effectiveEnd(), row.status(), row.requestId(),
                    row.appliedAt(), targets));
        }
        return new MigrationEvidenceResponse(items);
    }

    /**
     * 签发查询代次：固定当前最新目录代次。目录迁移生效后，迁移前签发的查询代次将被拒绝。
     */
    @Transactional
    public QueryGenerationResponse issueQueryGeneration() {
        long catalogGeneration = catalogRepository.latestGeneration();
        long queryGeneration = catalogRepository.insertQueryGeneration(catalogGeneration);
        return new QueryGenerationResponse(queryGeneration, catalogGeneration, "ACTIVE");
    }

    /**
     * 固定查询代次的批量查询：查询代次固定的目录代次必须仍为最新，否则整批拒绝；
     * 整批只读取该目录代次内的数据，不能混读旧新用途。
     */
    @Transactional(readOnly = true)
    public BatchQueryResponse batchQuery(BatchQueryRequest request) {
        Long pinned = catalogRepository.findQueryGenerationCatalog(request.queryGeneration());
        if (pinned == null) {
            throw ApiException.notFound(CODE_QUERY_GENERATION_NOT_FOUND, "查询代次不存在");
        }
        long latest = catalogRepository.latestGeneration();
        if (pinned.longValue() != latest) {
            throw ApiException.conflict(CODE_QUERY_GENERATION_STALE,
                    "查询代次固定的目录代次已失效，请重新签发查询代次");
        }
        List<BatchQueryResponse.BatchQueryResult> results = new ArrayList<>();
        for (BatchQueryRequest.BatchQueryItem item : request.items()) {
            results.add(queryOne(pinned, item.subjectKey(), item.purpose(), item.recordKey()));
        }
        return new BatchQueryResponse(request.queryGeneration(), pinned, results);
    }

    private BatchQueryResponse.BatchQueryResult queryOne(long catalogGeneration, String subjectKey,
                                                         String purpose, String recordKey) {
        // 该用途在固定目录代次仍为 ACTIVE 才可见：未参与拆分的沿用用途保留其历史数据可见性，
        // 被拆分（SUPERSEDED）的旧用途查不到，防止混读旧新用途
        boolean purposeActive = catalogRepository.findEntry(catalogGeneration, purpose)
                .map(entry -> "ACTIVE".equals(entry.status()))
                .orElse(false);
        if (!purposeActive) {
            return notFoundResult(subjectKey, purpose, recordKey);
        }
        Optional<ConsentRepository.GrantRow> grant = consentRepository.findLatestGrant(subjectKey, purpose)
                .filter(g -> g.status() == GrantStatus.ACTIVE);
        if (grant.isEmpty()) {
            return notFoundResult(subjectKey, purpose, recordKey);
        }
        return consentRepository.findRecord(subjectKey, purpose, grant.get().epoch(), recordKey)
                .map(r -> new BatchQueryResponse.BatchQueryResult(
                        r.subjectKey(), r.purpose(), r.epoch(), r.recordKey(),
                        r.recordAttribute(), r.payload(), true))
                .orElseGet(() -> notFoundResult(subjectKey, purpose, recordKey));
    }

    private BatchQueryResponse.BatchQueryResult notFoundResult(String subjectKey, String purpose, String recordKey) {
        return new BatchQueryResponse.BatchQueryResult(subjectKey, purpose, 0, recordKey, 0L, null, false);
    }

    // ---------- 预览准备与静态校验 ----------

    private PreparedMigration prepare(MigrationPreviewRequest request, boolean lockSourceEntry) {
        if (!request.effectiveStart().isBefore(request.effectiveEnd())) {
            throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "生效窗口必须满足左闭右开（起点早于终点）");
        }
        long latest = catalogRepository.latestGeneration();
        if (request.catalogVersion() == null || request.catalogVersion() != latest) {
            throw ApiException.conflict(CODE_CATALOG_VERSION_CONFLICT, "catalogVersion 不是当前最新目录代次");
        }
        CatalogRepository.EntryRow source = (lockSourceEntry
                ? catalogRepository.lockEntry(latest, request.sourcePurpose())
                : catalogRepository.findEntry(latest, request.sourcePurpose()))
                .orElseThrow(() -> ApiException.notFound(CODE_SOURCE_PURPOSE_NOT_ACTIVE,
                        "旧用途在当前目录中不存在: " + request.sourcePurpose()));
        if (!"ACTIVE".equals(source.status())) {
            throw ApiException.unprocessable(CODE_SOURCE_PURPOSE_NOT_ACTIVE,
                    "只有 ACTIVE 用途可以拆分: " + request.sourcePurpose());
        }

        List<PurposeTarget> targets = new ArrayList<>();
        for (PurposeTargetDto dto : request.targets()) {
            LongRange range = new LongRange(dto.rangeStart(), dto.rangeEnd());
            String supersedes = (dto.supersedes() == null || dto.supersedes().isBlank())
                    ? request.sourcePurpose() : dto.supersedes();
            targets.add(new PurposeTarget(dto.purpose(), range, supersedes));
        }
        Set<String> existingPurposes = catalogRepository.findEntries(latest).stream()
                .map(CatalogRepository.EntryRow::purpose)
                .collect(Collectors.toSet());
        RangeConservationValidator.validate(source.range(), targets, existingPurposes,
                catalogRepository.findAllSupersedes());

        List<PurposeTarget> sorted = targets.stream()
                .sorted((a, b) -> a.purpose().compareTo(b.purpose()))
                .toList();
        return new PreparedMigration(latest, request.sourcePurpose(), source.range(), sorted);
    }

    private MigrationPreviewResponse buildPreview(PreparedMigration prepared) {
        List<ConsentRepository.GrantRow> grants =
                consentRepository.findAllGrantsByPurpose(prepared.sourcePurpose());
        List<ConsentRepository.RecordRow> records =
                consentRepository.findAllRecordsByPurpose(prepared.sourcePurpose());

        Map<GrantKey, ConsentRepository.GrantRow> grantByKey = grants.stream()
                .collect(Collectors.toMap(g -> new GrantKey(g.subjectKey(), g.epoch()), g -> g));

        List<MigrationPreviewResponse.ActiveGrantItem> activeItems = new ArrayList<>();
        List<MigrationPreviewResponse.RevokedGrantItem> revokedItems = new ArrayList<>();
        List<String> allTargetCodes = prepared.targets().stream()
                .map(PurposeTarget::purpose).sorted().toList();
        for (ConsentRepository.GrantRow grant : grants) {
            if (grant.status() == GrantStatus.ACTIVE) {
                activeItems.add(new MigrationPreviewResponse.ActiveGrantItem(
                        grant.subjectKey(), grant.epoch(), grant.version(), allTargetCodes));
            } else if (grant.status() == GrantStatus.REVOKED) {
                revokedItems.add(new MigrationPreviewResponse.RevokedGrantItem(
                        grant.subjectKey(), grant.epoch(), GrantStatus.REVOKED.name()));
            }
        }

        List<MigrationPreviewResponse.RecordItem> recordItems = new ArrayList<>();
        for (ConsentRepository.RecordRow record : records) {
            ConsentRepository.GrantRow owner = grantByKey.get(
                    new GrantKey(record.subjectKey(), record.epoch()));
            boolean grantActive = owner != null && owner.status() == GrantStatus.ACTIVE;
            String target;
            if (grantActive) {
                String hit = RangeConservationValidator.uniqueTarget(prepared.targets(), record.recordAttribute());
                target = hit == null ? UNMAPPED : hit;
            } else {
                // 已撤回授权的隔离数据保留历史旧用途，不能借迁移恢复
                target = record.purpose();
            }
            recordItems.add(new MigrationPreviewResponse.RecordItem(
                    record.subjectKey(), record.epoch(), record.recordKey(), record.recordAttribute(),
                    record.version(), grantActive, target));
        }
        return new MigrationPreviewResponse(prepared.catalogVersion(), prepared.sourcePurpose(),
                prepared.sourceRange().start(), prepared.sourceRange().end(),
                activeItems, revokedItems, recordItems);
    }

    // ---------- 激活提交校验 ----------

    private void validateActiveGrantSelections(MigrationActivateRequest request,
                                               PreparedMigration prepared,
                                               Map<GrantKey, ConsentRepository.GrantRow> activeGrants) {
        Set<GrantKey> submitted = new HashSet<>();
        List<String> expectedTargets = prepared.targets().stream()
                .map(PurposeTarget::purpose).sorted().toList();
        for (MigrationActivateRequest.ActiveGrantSelection selection : request.activeGrants()) {
            GrantKey key = new GrantKey(selection.subjectKey(), selection.epoch());
            if (!submitted.add(key)) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "有效授权确认项重复: " + key);
            }
            ConsentRepository.GrantRow current = activeGrants.get(key);
            if (current == null) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "存在多余的有效授权确认项: " + key);
            }
            if (selection.expectedVersion() == null || selection.expectedVersion() != current.version()) {
                throw ApiException.conflict(CODE_VERSION_CONFLICT, "授权版本不匹配: " + key);
            }
            List<String> submittedTargets = selection.targetPurposes() == null
                    ? List.of() : selection.targetPurposes().stream().sorted().distinct().toList();
            if (!submittedTargets.equals(expectedTargets)) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "授权拆分目标用途与预览不一致: " + key);
            }
        }
        if (!submitted.equals(activeGrants.keySet())) {
            throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "有效授权确认项存在遗漏或多余");
        }
    }

    private Map<RecordKey, String> validateRecordSelections(MigrationActivateRequest request,
                                                            PreparedMigration prepared,
                                                            List<ConsentRepository.RecordRow> records,
                                                            Map<GrantKey, ConsentRepository.GrantRow> activeGrants) {
        Map<RecordKey, MigrationActivateRequest.RecordSelection> submitted = new HashMap<>();
        for (MigrationActivateRequest.RecordSelection selection : request.records()) {
            RecordKey key = new RecordKey(selection.subjectKey(), selection.epoch(), selection.recordKey());
            if (submitted.put(key, selection) != null) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "数据记录确认项重复: " + key);
            }
        }
        String sourcePurpose = prepared.sourcePurpose();
        Set<String> targetCodes = prepared.targets().stream()
                .map(PurposeTarget::purpose).collect(Collectors.toSet());
        Map<RecordKey, String> mappings = new HashMap<>();

        for (ConsentRepository.RecordRow current : records) {
            RecordKey key = new RecordKey(current.subjectKey(), current.epoch(), current.recordKey());
            MigrationActivateRequest.RecordSelection selection = submitted.get(key);
            if (selection == null) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "数据记录确认项存在遗漏: " + key);
            }
            if (selection.expectedVersion() == null || selection.expectedVersion() != current.version()) {
                throw ApiException.conflict(CODE_VERSION_CONFLICT, "记录版本不匹配或属性已变化: " + key);
            }
            boolean grantActive = activeGrants.containsKey(new GrantKey(current.subjectKey(), current.epoch()));
            String expected;
            if (grantActive) {
                String hit = RangeConservationValidator.uniqueTarget(prepared.targets(), current.recordAttribute());
                expected = hit == null ? UNMAPPED : hit;
            } else {
                expected = sourcePurpose;
            }
            if (!selection.targetPurpose().equals(expected)) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH,
                        "记录映射结果与按当前属性计算的唯一目标不一致: " + key
                                + "，提交=" + selection.targetPurpose() + "，应为=" + expected);
            }
            if (grantActive && !UNMAPPED.equals(expected) && !targetCodes.contains(expected)) {
                throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "映射目标不是本次新用途: " + expected);
            }
            mappings.put(key, expected);
        }
        if (submitted.size() != records.size()) {
            throw ApiException.unprocessable(CODE_PREVIEW_MISMATCH, "数据记录确认项存在多余或遗漏");
        }
        return mappings;
    }

    // ---------- 幂等 ----------

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("REQUEST_ID_CONFLICT", "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String fingerprint, MigrationActivateResponse response) {
        try {
            idempotencyRepository.insert(requestId, OP_MIGRATION_ACTIVATE, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = idempotencyRepository.find(requestId).orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict("REQUEST_ID_CONFLICT", "同一 requestId 参数不一致");
            }
        }
    }

    /**
     * 规范化参数指纹：所有集合排序后拼接，保证集合换序等价。
     */
    private String canonicalFingerprint(MigrationActivateRequest request) {
        MigrationPreviewRequest preview = request.preview();
        String targets = preview.targets().stream()
                .sorted((a, b) -> a.purpose().compareTo(b.purpose()))
                .map(t -> String.join(":", t.purpose(), String.valueOf(t.rangeStart()),
                        String.valueOf(t.rangeEnd()), String.valueOf(t.supersedes())))
                .collect(Collectors.joining(","));
        String grants = request.activeGrants().stream()
                .sorted((a, b) -> {
                    int bySubject = a.subjectKey().compareTo(b.subjectKey());
                    return bySubject != 0 ? bySubject : Integer.compare(a.epoch(), b.epoch());
                })
                .map(g -> String.join(":", g.subjectKey(), String.valueOf(g.epoch()),
                        String.valueOf(g.expectedVersion()),
                        g.targetPurposes().stream().sorted().collect(Collectors.joining(">"))))
                .collect(Collectors.joining(";"));
        String records = request.records().stream()
                .sorted((a, b) -> {
                    int bySubject = a.subjectKey().compareTo(b.subjectKey());
                    if (bySubject != 0) {
                        return bySubject;
                    }
                    int byEpoch = Integer.compare(a.epoch(), b.epoch());
                    return byEpoch != 0 ? byEpoch : a.recordKey().compareTo(b.recordKey());
                })
                .map(r -> String.join(":", r.subjectKey(), String.valueOf(r.epoch()), r.recordKey(),
                        String.valueOf(r.expectedVersion()), r.targetPurpose()))
                .collect(Collectors.joining(";"));
        return String.join("|",
                OP_MIGRATION_ACTIVATE,
                preview.migrationKey(),
                String.valueOf(preview.catalogVersion()),
                preview.sourcePurpose(),
                preview.effectiveStart().toString(),
                preview.effectiveEnd().toString(),
                targets,
                grants,
                records);
    }

    private String writeSnapshot(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("迁移响应快照序列化失败", e);
        }
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("迁移响应快照反序列化失败", e);
        }
    }

    private record GrantKey(String subjectKey, int epoch) {
        @Override
        public String toString() {
            return subjectKey + "#" + epoch;
        }
    }

    private record RecordKey(String subjectKey, int epoch, String recordKey) {
        @Override
        public String toString() {
            return subjectKey + "#" + epoch + "/" + recordKey;
        }
    }

    private record PreparedMigration(long catalogVersion, String sourcePurpose, LongRange sourceRange,
                                     List<PurposeTarget> targets) {
    }
}
