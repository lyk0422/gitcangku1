package com.example.starter.consent.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ApiException;
import com.example.starter.consent.ConsentRepository;
import com.example.starter.consent.GrantStatus;
import com.example.starter.consent.IdempotencyRepository;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.catalog.dto.ActivateGrantItem;
import com.example.starter.consent.catalog.dto.ActivateMigrationRequest;
import com.example.starter.consent.catalog.dto.ActivateMigrationResponse;
import com.example.starter.consent.catalog.dto.ActivateRecordItem;
import com.example.starter.consent.catalog.dto.BatchQueryItem;
import com.example.starter.consent.catalog.dto.BatchQueryRequest;
import com.example.starter.consent.catalog.dto.BatchQueryResponse;
import com.example.starter.consent.catalog.dto.BatchQueryResult;
import com.example.starter.consent.catalog.dto.MappingResult;
import com.example.starter.consent.catalog.dto.MigrationEvidenceResponse;
import com.example.starter.consent.catalog.dto.MigrationPreviewResponse;
import com.example.starter.consent.catalog.dto.MigrationProposalRequest;
import com.example.starter.consent.catalog.dto.NewPurposeDef;
import com.example.starter.consent.catalog.dto.PreviewActiveGrant;
import com.example.starter.consent.catalog.dto.PreviewRecord;
import com.example.starter.consent.catalog.dto.PreviewRevokedGrant;
import com.example.starter.consent.catalog.dto.QueryGenerationRequest;
import com.example.starter.consent.catalog.dto.QueryGenerationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 用途目录拆分迁移域服务。
 *
 * <p>两阶段协议：{@link #preview} 只读列出绑定旧用途的全部授权与数据记录并给出映射；
 * {@link #activate} 在一个事务内加锁重读、校验 expectedVersion 与映射后发布新
 * catalogGeneration、拆分授权、一次性改绑数据并写入迁移证据。
 *
 * <p>串行化：激活首先对旧用途目录行加写锁（FOR UPDATE），授权/写入路径也锁定
 * 用途行，从而与授权撤回、数据写入、查询及另一目录迁移按提交顺序执行；
 * 数据行改绑带旧用途名与行版本条件，保证任何数据行始终只有一个活动用途归属。
 */
@Service
public class MigrationService {

    static final String CODE_PURPOSE_NOT_FOUND = "PURPOSE_NOT_FOUND";
    static final String CODE_PURPOSE_NOT_ACTIVE = "PURPOSE_NOT_ACTIVE";
    static final String CODE_PURPOSE_SPLIT = "PURPOSE_SPLIT";
    static final String CODE_CATALOG_VERSION_CONFLICT = "CATALOG_VERSION_CONFLICT";
    static final String CODE_MIGRATION_INVALID = "MIGRATION_INVALID";
    static final String CODE_MULTIPLE_TARGETS = "MULTIPLE_TARGETS";
    static final String CODE_SCOPE_VIOLATION = "SCOPE_VIOLATION";
    static final String CODE_PREVIEW_STALE = "PREVIEW_STALE";
    static final String CODE_MIGRATION_KEY_DUPLICATED = "MIGRATION_KEY_DUPLICATED";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_MIGRATION_NOT_FOUND = "MIGRATION_NOT_FOUND";
    static final String CODE_EFFECTIVE_WINDOW = "EFFECTIVE_WINDOW";
    static final String CODE_QUERY_GENERATION_NOT_FOUND = "QUERY_GENERATION_NOT_FOUND";
    static final String CODE_QUERY_GENERATION_STALE = "QUERY_GENERATION_STALE";
    static final String CODE_PURPOSE_NOT_IN_GENERATION = "PURPOSE_NOT_IN_GENERATION";

    private static final String OP_MIGRATE = "MIGRATE";
    private static final Comparator<NewPurposeDef> BY_CODE = Comparator.comparing(NewPurposeDef::code);
    private static final Comparator<ConsentRepository.GrantRow> GRANT_ORDER =
            Comparator.comparing(ConsentRepository.GrantRow::subjectKey)
                    .thenComparingInt(ConsentRepository.GrantRow::epoch);
    private static final Comparator<ConsentRepository.RecordRow> RECORD_ORDER =
            Comparator.comparing(ConsentRepository.RecordRow::subjectKey)
                    .thenComparingInt(ConsentRepository.RecordRow::epoch)
                    .thenComparing(ConsentRepository.RecordRow::recordKey);

    private final CatalogRepository catalogRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MigrationService(CatalogRepository catalogRepository,
                            ConsentRepository consentRepository,
                            IdempotencyRepository idempotencyRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.catalogRepository = catalogRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 预览：只读列出绑定旧用途的全部有效授权、已撤回授权与数据记录，并按记录属性给出
     * 唯一目标用途或 UNMAPPED；隔离数据标记 RETAINED。不写任何数据。
     */
    @Transactional(readOnly = true)
    public MigrationPreviewResponse preview(MigrationProposalRequest request) {
        int currentGeneration = catalogRepository.currentGeneration();
        if (request.catalogVersion() != currentGeneration) {
            throw ApiException.conflict(CODE_CATALOG_VERSION_CONFLICT,
                    "目录版本已变化，请刷新后重新预览");
        }
        CatalogRepository.PurposeRow source = catalogRepository.findPurpose(request.sourcePurpose())
                .orElseThrow(() -> ApiException.notFound(CODE_PURPOSE_NOT_FOUND, "旧用途不存在"));
        if (!source.active()) {
            throw ApiException.conflict(CODE_PURPOSE_NOT_ACTIVE, "旧用途不是 ACTIVE 状态，不能拆分");
        }
        if (catalogRepository.evidenceExists(request.migrationKey())) {
            throw ApiException.conflict(CODE_MIGRATION_KEY_DUPLICATED, "migrationKey 已被使用");
        }

        ParsedProposal proposal = parseAndValidate(request.newPurposes(), request.sourceScopeValues(),
                source, request.effectiveFrom(), request.effectiveTo());

        List<ConsentRepository.GrantRow> grants =
                consentRepository.findAllGrantsByPurpose(request.sourcePurpose());
        List<ConsentRepository.RecordRow> records =
                consentRepository.findAllRecordsByPurpose(request.sourcePurpose());
        Map<GrantKey, ConsentRepository.GrantRow> grantByKey = new TreeMap<>();
        for (ConsentRepository.GrantRow grant : grants) {
            grantByKey.put(new GrantKey(grant.subjectKey(), grant.epoch()), grant);
        }

        List<String> newCodesSorted = new ArrayList<>(proposal.scopes.keySet());
        List<PreviewActiveGrant> activeGrants = new ArrayList<>();
        List<PreviewRevokedGrant> revokedGrants = new ArrayList<>();
        for (ConsentRepository.GrantRow grant : grants.stream().sorted(GRANT_ORDER).toList()) {
            if (grant.status() == GrantStatus.ACTIVE) {
                activeGrants.add(new PreviewActiveGrant(grant.subjectKey(), grant.epoch(),
                        grant.rowVersion(), newCodesSorted));
            } else {
                revokedGrants.add(new PreviewRevokedGrant(grant.subjectKey(), grant.epoch()));
            }
        }

        List<PreviewRecord> previewRecords = new ArrayList<>();
        for (ConsentRepository.RecordRow record : records.stream().sorted(RECORD_ORDER).toList()) {
            ConsentRepository.GrantRow owner =
                    grantByKey.get(new GrantKey(record.subjectKey(), record.epoch()));
            boolean isolated = owner != null && owner.status() != GrantStatus.ACTIVE;
            MappingResult mapping;
            String target;
            if (isolated) {
                mapping = MappingResult.RETAINED;
                target = null;
            } else {
                TargetMatch match = matchTarget(record.attributeValue(), proposal);
                mapping = match.result();
                target = match.target();
            }
            previewRecords.add(new PreviewRecord(record.subjectKey(), record.epoch(), record.recordKey(),
                    record.attributeValue(), record.rowVersion(), isolated, mapping, target));
        }

        return new MigrationPreviewResponse(request.migrationKey(), currentGeneration,
                request.sourcePurpose(), proposal.sourceScopeSorted, proposal.newPurposeDefs,
                request.effectiveFrom(), request.effectiveTo(), activeGrants, revokedGrants, previewRecords);
    }

    /**
     * 激活：整单在一个事务内完成；任一遗漏、多余、属性变化、范围越界或多目标均整体失败。
     */
    @Transactional
    public ActivateMigrationResponse activate(ActivateMigrationRequest request) {
        String fingerprint = fingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), ActivateMigrationResponse.class);
        }
        if (catalogRepository.evidenceExists(request.migrationKey())) {
            throw ApiException.conflict(CODE_MIGRATION_KEY_DUPLICATED, "migrationKey 已被使用");
        }

        // 首先锁定旧用途目录行：与授权、写入、撤回及另一迁移按提交顺序串行化
        CatalogRepository.PurposeRow source =
                catalogRepository.lockPurpose(request.sourcePurpose())
                        .orElseThrow(() -> ApiException.notFound(CODE_PURPOSE_NOT_FOUND, "旧用途不存在"));
        // 并发同 requestId 请求可能在锁上等待；持锁后前一事务必已提交，复查以重放首次快照
        Optional<IdempotencyRow> committed = idempotencyRepository.find(request.requestId());
        if (committed.isPresent()) {
            if (!committed.get().paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
            return readSnapshot(committed.get().responseBody(), ActivateMigrationResponse.class);
        }
        if (!source.active()) {
            throw ApiException.conflict(CODE_PURPOSE_SPLIT, "旧用途已被拆分，不能重复迁移");
        }
        int currentGeneration = catalogRepository.currentGeneration();
        if (request.catalogVersion() != currentGeneration) {
            throw ApiException.conflict(CODE_CATALOG_VERSION_CONFLICT,
                    "目录版本已变化，请重新预览后激活");
        }

        ParsedProposal proposal = parseAndValidate(request.newPurposes(), request.sourceScopeValues(),
                source, request.effectiveFrom(), request.effectiveTo());
        validateWithinWindow(request.effectiveFrom(), request.effectiveTo());

        // 加锁重读：授权与数据行全部锁定，扫描预览后的任何提交都会改变行版本或集合构成
        List<ConsentRepository.GrantRow> grants =
                consentRepository.findAllGrantsByPurposeForUpdate(request.sourcePurpose());
        List<ConsentRepository.RecordRow> records =
                consentRepository.findAllRecordsByPurposeForUpdate(request.sourcePurpose());
        Map<GrantKey, ConsentRepository.GrantRow> grantByKey = new TreeMap<>();
        Set<GrantKey> activeKeys = new TreeSet<>();
        for (ConsentRepository.GrantRow grant : grants) {
            GrantKey key = new GrantKey(grant.subjectKey(), grant.epoch());
            grantByKey.put(key, grant);
            if (grant.status() == GrantStatus.ACTIVE) {
                activeKeys.add(key);
            }
        }
        Map<RecordKey, ConsentRepository.RecordRow> recordByKey = new TreeMap<>();
        for (ConsentRepository.RecordRow record : records) {
            recordByKey.put(new RecordKey(record.subjectKey(), record.epoch(), record.recordKey()), record);
        }

        validateGrants(request, grantByKey, activeKeys);
        validateRecords(request, grantByKey, recordByKey, proposal);

        int newGeneration;
        try {
            newGeneration = catalogRepository.insertGeneration(request.migrationKey(),
                    request.sourcePurpose(), request.effectiveFrom(), request.effectiveTo());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict(CODE_MIGRATION_KEY_DUPLICATED, "migrationKey 已被使用");
        }
        for (NewPurposeDef def : proposal.newPurposeDefs) {
            try {
                catalogRepository.insertPurpose(def.code(),
                        proposal.scopes.get(def.code()).canonical(), newGeneration);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict(CODE_MIGRATION_INVALID, "新用途代码已存在: " + def.code());
            }
            catalogRepository.insertReplacement(request.sourcePurpose(), def.code(), newGeneration,
                    proposal.scopes.get(def.code()).canonical());
        }
        catalogRepository.markPurposeSplit(request.sourcePurpose(),
                proposal.sourceScope.canonical(), newGeneration);

        int newGrantCount = 0;
        for (ConsentRepository.GrantRow grant : grants.stream().sorted(GRANT_ORDER).toList()) {
            if (grant.status() != GrantStatus.ACTIVE) {
                continue;
            }
            if (!consentRepository.markGrantMigratedIfVersion(grant.subjectKey(), grant.purpose(),
                    grant.epoch(), grant.rowVersion())) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "授权在预览后发生变化，请重新预览");
            }
            for (String newCode : proposal.scopes.keySet()) {
                consentRepository.insertMigratedGrant(grant.subjectKey(), newCode, 1,
                        "MIGRATE:" + request.migrationKey(), newGeneration);
                newGrantCount++;
            }
        }

        int rebound = 0;
        int unmapped = 0;
        int retained = 0;
        for (ActivateRecordItem item : request.records()) {
            if (item.mappingResult() == MappingResult.MAPPED) {
                boolean reboundOk = consentRepository.rebindRecordIfVersion(item.subjectKey(),
                        item.epoch(), item.recordKey(), request.sourcePurpose(),
                        item.targetPurpose(), item.expectedVersion());
                if (!reboundOk) {
                    throw ApiException.conflict(CODE_PREVIEW_STALE, "数据记录在预览后发生变化，请重新预览");
                }
                rebound++;
            } else if (item.mappingResult() == MappingResult.RETAINED) {
                retained++;
            } else {
                unmapped++;
            }
        }

        int activeGrantCount = activeKeys.size();
        ActivateMigrationResponse response = new ActivateMigrationResponse(
                request.migrationKey(), newGeneration, request.sourcePurpose(),
                new ArrayList<>(proposal.scopes.keySet()), activeGrantCount, newGrantCount,
                rebound, unmapped, retained, request.effectiveFrom(), request.effectiveTo());

        writeEvidence(request, newGeneration, grants, records.stream().sorted(RECORD_ORDER).toList(),
                proposal);
        idempotencyRepository.insert(request.requestId(), OP_MIGRATE, fingerprint, writeSnapshot(response));
        return response;
    }

    /**
     * 迁移证据查询：只读，明细稳定排序。
     */
    @Transactional(readOnly = true)
    public MigrationEvidenceResponse evidence(String migrationKey) {
        return catalogRepository.findEvidence(migrationKey)
                .orElseThrow(() -> ApiException.notFound(CODE_MIGRATION_NOT_FOUND, "迁移证据不存在"));
    }

    /**
     * 签发查询代次令牌：不传目录代次时固定到当前最新目录代次。
     */
    @Transactional
    public QueryGenerationResponse issueQueryGeneration(QueryGenerationRequest request) {
        int generation = request.catalogGeneration() == null
                ? catalogRepository.currentGeneration()
                : request.catalogGeneration();
        if (catalogRepository.findGeneration(generation).isEmpty()) {
            throw ApiException.notFound(CODE_CATALOG_VERSION_CONFLICT, "目录代次不存在");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        catalogRepository.insertQueryToken(token, generation);
        return new QueryGenerationResponse(token, generation, Instant.now(clock));
    }

    /**
     * 固定目录代次的批量查询：令牌过期（代次落后）整批拒绝；用途不属于该代次整批拒绝，
     * 从而保证一批查询不能混读旧新用途。
     *
     * <p>声明为读写事务以使用 SELECT ... FOR UPDATE（不执行任何写入）：
     * 对用途行加共享写锁与迁移串行化，杜绝 READ_COMMITTED 下迁移在批次读取中途提交。
     */
    @Transactional
    public BatchQueryResponse batchQuery(BatchQueryRequest request) {
        int pinned = catalogRepository.findQueryGeneration(request.token())
                .orElseThrow(() -> ApiException.gone(CODE_QUERY_GENERATION_NOT_FOUND, "查询代次令牌不存在"));
        int current = catalogRepository.currentGeneration();
        if (pinned < current) {
            throw ApiException.gone(CODE_QUERY_GENERATION_STALE,
                    "查询代次已在目录迁移生效后失效，请重新签发查询代次");
        }
        // 整批前置校验：所有用途必须在令牌固定的目录代次中处于 ACTIVE，禁止混读。
        // 按代码排序依次锁定用途行，与迁移事务串行化，避免 READ_COMMITTED 下迁移在批次中途提交。
        List<String> distinctPurposes = request.items().stream()
                .map(BatchQueryItem::purpose).distinct().sorted().toList();
        for (String code : distinctPurposes) {
            CatalogRepository.PurposeRow locked = catalogRepository.lockPurpose(code)
                    .orElseThrow(() -> ApiException.unprocessable(CODE_PURPOSE_NOT_IN_GENERATION,
                            "用途不属于当前查询代次: " + code));
            if (!purposeActiveInGeneration(locked, pinned)) {
                throw ApiException.unprocessable(CODE_PURPOSE_NOT_IN_GENERATION,
                        "用途在当前查询代次已失效，不能混读旧新用途: " + code);
            }
        }
        // 持锁后复检：若迁移已在等待这些用途行，则它尚未提交；若已提交，代次必然推进
        if (catalogRepository.currentGeneration() != pinned) {
            throw ApiException.gone(CODE_QUERY_GENERATION_STALE,
                    "查询代次已在目录迁移生效后失效，请重新签发查询代次");
        }
        List<BatchQueryResult> results = new ArrayList<>();
        for (BatchQueryItem item : request.items()) {
            results.add(querySingle(pinned, item));
        }
        return new BatchQueryResponse(pinned, results);
    }

    private BatchQueryResult querySingle(int generation, BatchQueryItem item) {
        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(item.subjectKey(), item.purpose());
        if (latest.isEmpty()) {
            return BatchQueryResult.missing(item, "GRANT_MISSING");
        }
        ConsentRepository.GrantRow grant = latest.get();
        if (grant.status() == GrantStatus.REVOKED) {
            return BatchQueryResult.missing(item, "CONSENT_REVOKED");
        }
        if (grant.status() == GrantStatus.MIGRATED) {
            return BatchQueryResult.missing(item, "GRANT_MIGRATED");
        }
        return consentRepository.findRecord(item.subjectKey(), item.purpose(), grant.epoch(), item.recordKey())
                .map(row -> new BatchQueryResult(item.subjectKey(), item.purpose(), item.recordKey(),
                        true, row.epoch(), row.payload(), row.attributeValue(), null))
                .orElseGet(() -> BatchQueryResult.missing(item, "NOT_FOUND"));
    }

    private boolean purposeActiveInGeneration(CatalogRepository.PurposeRow purpose, int generation) {
        if (purpose.introducedGeneration() > generation) {
            return false;
        }
        return purpose.splitGeneration() == null || purpose.splitGeneration() > generation;
    }

    // ---------- 校验 ----------

    private ParsedProposal parseAndValidate(List<NewPurposeDef> rawNewPurposes, List<String> rawSourceScope,
                                            CatalogRepository.PurposeRow source,
                                            Instant effectiveFrom, Instant effectiveTo) {
        if (!effectiveFrom.isBefore(effectiveTo)) {
            throw ApiException.unprocessable(CODE_EFFECTIVE_WINDOW, "生效窗口必须满足左闭右开（起点早于终点）");
        }

        List<NewPurposeDef> defs = rawNewPurposes.stream().sorted(BY_CODE).toList();
        Set<String> codes = new LinkedHashSet<>();
        for (NewPurposeDef def : defs) {
            if (!codes.add(def.code())) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "新用途代码重复: " + def.code());
            }
            if (def.code().equals(source.code())) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "新用途代码不得与旧用途相同");
            }
            if (catalogRepository.findPurpose(def.code()).isPresent()) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "新用途代码已存在: " + def.code());
            }
            if (new TreeSet<>(def.scopeValues()).size() != def.scopeValues().size()) {
                throw ApiException.unprocessable(CODE_SCOPE_VIOLATION,
                        "新用途范围存在重复属性取值: " + def.code());
            }
        }

        ScopeSpec declaredSource = ScopeSpec.of(new TreeSet<>(rawSourceScope));
        ScopeSpec sourceScope;
        if (source.scopeCanonical() == null || source.scopeCanonical().isEmpty()) {
            // 初始目录用途首次拆分：以提案声明范围钉住旧用途处理范围
            sourceScope = declaredSource;
        } else {
            sourceScope = ScopeSpec.fromCanonical(source.scopeCanonical());
            if (!sourceScope.equals(declaredSource)) {
                throw ApiException.unprocessable(CODE_SCOPE_VIOLATION, "旧用途处理范围与目录登记不一致");
            }
        }
        if (sourceScope.values().isEmpty()) {
            throw ApiException.unprocessable(CODE_SCOPE_VIOLATION, "旧用途处理范围不能为空");
        }

        Map<String, ScopeSpec> scopes = new LinkedHashMap<>();
        for (NewPurposeDef def : defs) {
            ScopeSpec scope = ScopeSpec.of(new TreeSet<>(def.scopeValues()));
            if (!scope.isSubsetOf(sourceScope)) {
                throw ApiException.unprocessable(CODE_SCOPE_VIOLATION,
                        "新用途范围越出旧用途范围: " + def.code());
            }
            scopes.put(def.code(), scope);
        }
        List<ScopeSpec> scopeList = new ArrayList<>(scopes.values());
        for (int i = 0; i < scopeList.size(); i++) {
            for (int j = i + 1; j < scopeList.size(); j++) {
                if (scopeList.get(i).overlaps(scopeList.get(j))) {
                    throw ApiException.unprocessable(CODE_MULTIPLE_TARGETS,
                            "新用途范围两两重叠，记录可能出现多个目标");
                }
            }
        }
        // 拆分不扩容：全部新范围并集必须恰好等于旧范围
        if (!ScopeSpec.union(scopeList).equals(sourceScope)) {
            throw ApiException.unprocessable(CODE_SCOPE_VIOLATION,
                    "全部新用途范围并集必须恰好等于旧用途范围，不得扩大或遗漏");
        }
        assertReplacementAcyclic(source.code(), scopes.keySet());

        return new ParsedProposal(defs, scopes, sourceScope,
                new ArrayList<>(new TreeSet<>(sourceScope.values())));
    }

    /**
     * 替代环检测：若从任一新用途沿“父→子”替代关系能到达旧用途，则形成环。
     */
    private void assertReplacementAcyclic(String sourceCode, Set<String> newCodes) {
        for (String newCode : newCodes) {
            Set<String> visited = new LinkedHashSet<>();
            if (reaches(newCode, sourceCode, visited)) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                        "替代关系形成环: " + newCode + " -> ... -> " + sourceCode);
            }
        }
    }

    private boolean reaches(String from, String target, Set<String> visited) {
        if (from.equals(target)) {
            return true;
        }
        if (!visited.add(from)) {
            return false;
        }
        for (CatalogRepository.ReplacementRow row : catalogRepository.findReplacementsByParent(from)) {
            if (reaches(row.childCode(), target, visited)) {
                return true;
            }
        }
        return false;
    }

    private void validateWithinWindow(Instant effectiveFrom, Instant effectiveTo) {
        Instant now = Instant.now(clock);
        if (now.isBefore(effectiveFrom) || !now.isBefore(effectiveTo)) {
            throw ApiException.unprocessable(CODE_EFFECTIVE_WINDOW,
                    "激活时刻不在左闭右开生效窗口内（UTC）");
        }
    }

    private void validateGrants(ActivateMigrationRequest request,
                                Map<GrantKey, ConsentRepository.GrantRow> grantByKey,
                                Set<GrantKey> activeKeys) {
        Set<GrantKey> submitted = new TreeSet<>();
        for (ActivateGrantItem item : request.grants()) {
            GrantKey key = new GrantKey(item.subjectKey(), item.epoch());
            if (!submitted.add(key)) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "激活请求包含重复授权项");
            }
            ConsentRepository.GrantRow current = grantByKey.get(key);
            if (current == null) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "激活请求包含预览之外的授权: " + key);
            }
            if (current.status() != GrantStatus.ACTIVE) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "授权状态在预览后已变化: " + key);
            }
            if (current.rowVersion() != item.expectedVersion()) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "授权版本在预览后已变化: " + key);
            }
        }
        if (!submitted.equals(activeKeys)) {
            throw ApiException.conflict(CODE_PREVIEW_STALE,
                    "激活请求遗漏或多余有效授权，必须与预览完全一致");
        }
    }

    private void validateRecords(ActivateMigrationRequest request,
                                 Map<GrantKey, ConsentRepository.GrantRow> grantByKey,
                                 Map<RecordKey, ConsentRepository.RecordRow> recordByKey,
                                 ParsedProposal proposal) {
        Set<RecordKey> submitted = new TreeSet<>();
        for (ActivateRecordItem item : request.records()) {
            RecordKey key = new RecordKey(item.subjectKey(), item.epoch(), item.recordKey());
            if (!submitted.add(key)) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID, "激活请求包含重复记录项");
            }
            ConsentRepository.RecordRow current = recordByKey.get(key);
            if (current == null) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "激活请求包含预览之外的记录: " + key);
            }
            if (current.rowVersion() != item.expectedVersion()) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "记录版本在预览后已变化: " + key);
            }
            if (!java.util.Objects.equals(current.attributeValue(), item.attributeValueForCheck())) {
                throw ApiException.conflict(CODE_PREVIEW_STALE, "记录属性在预览后已变化: " + key);
            }
            ConsentRepository.GrantRow owner =
                    grantByKey.get(new GrantKey(item.subjectKey(), item.epoch()));
            boolean isolated = owner == null || owner.status() != GrantStatus.ACTIVE;
            TargetMatch recomputed = isolated
                    ? new TargetMatch(MappingResult.RETAINED, null)
                    : matchTarget(current.attributeValue(), proposal);
            if (item.mappingResult() != recomputed.result()) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                        "记录映射结果与预览不一致: " + key);
            }
            if (item.mappingResult() == MappingResult.MAPPED) {
                if (item.targetPurpose() == null
                        || !item.targetPurpose().equals(recomputed.target())) {
                    throw ApiException.unprocessable(CODE_MULTIPLE_TARGETS,
                            "记录目标用途与唯一映射不一致: " + key);
                }
            } else if (item.targetPurpose() != null) {
                throw ApiException.unprocessable(CODE_MIGRATION_INVALID,
                        "非 MAPPED 记录不得携带目标用途: " + key);
            }
        }
        if (!submitted.equals(recordByKey.keySet())) {
            throw ApiException.conflict(CODE_PREVIEW_STALE,
                    "激活请求遗漏或多余数据记录，必须与预览完全一致");
        }
    }

    private TargetMatch matchTarget(String attributeValue, ParsedProposal proposal) {
        if (attributeValue == null) {
            return new TargetMatch(MappingResult.UNMAPPED, null);
        }
        String hit = null;
        int count = 0;
        for (Map.Entry<String, ScopeSpec> entry : proposal.scopes.entrySet()) {
            if (entry.getValue().covers(attributeValue)) {
                hit = entry.getKey();
                count++;
            }
        }
        if (count == 0) {
            return new TargetMatch(MappingResult.UNMAPPED, null);
        }
        if (count > 1) {
            // 范围两两不重叠校验已先行拦截，此处为防御性分支
            throw ApiException.unprocessable(CODE_MULTIPLE_TARGETS,
                    "记录属性命中多个新用途: " + attributeValue);
        }
        return new TargetMatch(MappingResult.MAPPED, hit);
    }

    // ---------- 证据与幂等 ----------

    private void writeEvidence(ActivateMigrationRequest request, int newGeneration,
                               List<ConsentRepository.GrantRow> grants,
                               List<ConsentRepository.RecordRow> records,
                               ParsedProposal proposal) {
        catalogRepository.insertEvidence(request.migrationKey(), newGeneration,
                request.sourcePurpose(), request.requestId(),
                writeSnapshot(java.util.Map.of(
                        "catalogVersion", request.catalogVersion(),
                        "effectiveFrom", request.effectiveFrom().toString(),
                        "effectiveTo", request.effectiveTo().toString())));
        int ordinal = 0;
        for (ConsentRepository.GrantRow grant : grants.stream().sorted(GRANT_ORDER).toList()) {
            if (grant.status() == GrantStatus.ACTIVE) {
                for (String newCode : proposal.scopes.keySet()) {
                    catalogRepository.insertEvidenceItem(request.migrationKey(), ordinal++,
                            "GRANT_ACTIVE", grant.subjectKey(), grant.purpose(), grant.epoch(),
                            newCode, 1, null, null, MappingResult.MAPPED);
                }
            } else {
                catalogRepository.insertEvidenceItem(request.migrationKey(), ordinal++,
                        "GRANT_REVOKED", grant.subjectKey(), grant.purpose(), grant.epoch(),
                        null, null, null, null, MappingResult.RETAINED);
            }
        }
        Map<GrantKey, ConsentRepository.GrantRow> grantByKey = new TreeMap<>();
        for (ConsentRepository.GrantRow grant : grants) {
            grantByKey.put(new GrantKey(grant.subjectKey(), grant.epoch()), grant);
        }
        for (ConsentRepository.RecordRow record : records) {
            ConsentRepository.GrantRow owner =
                    grantByKey.get(new GrantKey(record.subjectKey(), record.epoch()));
            boolean isolated = owner == null || owner.status() != GrantStatus.ACTIVE;
            TargetMatch match = isolated
                    ? new TargetMatch(MappingResult.RETAINED, null)
                    : matchTarget(record.attributeValue(), proposal);
            catalogRepository.insertEvidenceItem(request.migrationKey(), ordinal++,
                    "RECORD", record.subjectKey(), record.purpose(), record.epoch(),
                    match.result() == MappingResult.MAPPED ? match.target() : null, null,
                    record.recordKey(), record.attributeValue(), match.result());
        }
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    /**
     * 规范化指纹：所有集合排序后拼接，集合换序等价。
     */
    private String fingerprint(ActivateMigrationRequest request) {
        List<String> newPurposes = request.newPurposes().stream()
                .sorted(BY_CODE)
                .map(def -> def.code() + "=" + ScopeSpec.of(new TreeSet<>(def.scopeValues())).canonical())
                .toList();
        List<String> grantItems = request.grants().stream()
                .sorted(Comparator.comparing(ActivateGrantItem::subjectKey)
                        .thenComparingInt(ActivateGrantItem::epoch))
                .map(item -> item.subjectKey() + "#" + item.epoch() + "#" + item.expectedVersion())
                .toList();
        List<String> recordItems = request.records().stream()
                .sorted(Comparator.comparing(ActivateRecordItem::subjectKey)
                        .thenComparingInt(ActivateRecordItem::epoch)
                        .thenComparing(ActivateRecordItem::recordKey))
                .map(item -> item.subjectKey() + "#" + item.epoch() + "#" + item.recordKey()
                        + "#" + item.expectedVersion() + "#" + item.mappingResult()
                        + "#" + Optional.ofNullable(item.targetPurpose()).orElse(""))
                .toList();
        return String.join("|",
                OP_MIGRATE,
                String.valueOf(request.catalogVersion()),
                request.sourcePurpose(),
                request.migrationKey(),
                ScopeSpec.of(new TreeSet<>(request.sourceScopeValues())).canonical(),
                request.effectiveFrom().toString(),
                request.effectiveTo().toString(),
                String.join(";", newPurposes),
                String.join(";", grantItems),
                String.join(";", recordItems));
    }

    private String writeSnapshot(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照序列化失败", e);
        }
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }

    private record GrantKey(String subjectKey, int epoch) implements Comparable<GrantKey> {
        @Override
        public int compareTo(GrantKey other) {
            int bySubject = subjectKey.compareTo(other.subjectKey);
            return bySubject != 0 ? bySubject : Integer.compare(epoch, other.epoch);
        }
    }

    private record RecordKey(String subjectKey, int epoch, String recordKey)
            implements Comparable<RecordKey> {
        @Override
        public int compareTo(RecordKey other) {
            int bySubject = subjectKey.compareTo(other.subjectKey);
            if (bySubject != 0) {
                return bySubject;
            }
            int byEpoch = Integer.compare(epoch, other.epoch);
            return byEpoch != 0 ? byEpoch : recordKey.compareTo(other.recordKey);
        }
    }

    private record TargetMatch(MappingResult result, String target) {
    }

    private static final class ParsedProposal {
        private final List<NewPurposeDef> newPurposeDefs;
        private final Map<String, ScopeSpec> scopes;
        private final ScopeSpec sourceScope;
        private final List<String> sourceScopeSorted;

        private ParsedProposal(List<NewPurposeDef> newPurposeDefs, Map<String, ScopeSpec> scopes,
                               ScopeSpec sourceScope, List<String> sourceScopeSorted) {
            this.newPurposeDefs = newPurposeDefs;
            this.scopes = scopes;
            this.sourceScope = sourceScope;
            this.sourceScopeSorted = sourceScopeSorted;
        }
    }
}
