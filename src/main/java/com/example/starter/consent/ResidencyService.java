package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.ResidencyRepository.SnapshotItemRow;
import com.example.starter.consent.ResidencyRepository.SnapshotRow;
import com.example.starter.consent.dto.BatchQueryItem;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.EpochDiagnosticResponse;
import com.example.starter.consent.dto.MigrateRequest;
import com.example.starter.consent.dto.MigrateResponse;
import com.example.starter.consent.dto.RegionBindRequest;
import com.example.starter.consent.dto.RegionBindResponse;
import com.example.starter.consent.dto.RegionRegisterRequest;
import com.example.starter.consent.dto.RegionResponse;
import com.example.starter.consent.dto.SnapshotDetailResponse;
import com.example.starter.consent.dto.SnapshotSummaryResponse;

/**
 * 授权数据驻留服务：区域登记、区域绑定、驻留迁移、跨对象路由查询与合规快照。
 *
 * <p>裁决规则：登记、迁移、撤回、清除与查询快照按提交顺序裁决——迁移先提交则旧代查询 410，
 * 查询先成功则返回冻结的一致快照，后续迁移/撤回/清除不改写已生成快照。
 *
 * <p>原子性：迁移与批量查询先核对整批最终状态，再在同一事务内一次提交，任一失败整批回滚，
 * 不留下部分副本或部分结果，并通过稳定业务码区分失败原因。
 */
@Service
public class ResidencyService {

    static final String CODE_REGION_ALREADY_REGISTERED = "REGION_ALREADY_REGISTERED";
    static final String CODE_REGION_NOT_FOUND = "REGION_NOT_FOUND";
    static final String CODE_REGION_ALREADY_BOUND = "REGION_ALREADY_BOUND";
    static final String CODE_REGION_ROUTE_FORBIDDEN = "REGION_ROUTE_FORBIDDEN";
    static final String CODE_REGION_NOT_ALLOWED = "REGION_NOT_ALLOWED";
    static final String CODE_RECORD_ERASED = "RECORD_ERASED";
    static final String CODE_SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND";
    static final String CODE_MIGRATION_SOURCE_REVOKED = "MIGRATION_SOURCE_REVOKED";
    static final String CODE_MIGRATION_TARGET_NOT_REGISTERED = "MIGRATION_TARGET_NOT_REGISTERED";
    static final String CODE_MIGRATION_OBJECT_NOT_FOUND = "MIGRATION_OBJECT_NOT_FOUND";
    static final String CODE_MIGRATION_OBJECT_ERASED = "MIGRATION_OBJECT_ERASED";
    static final String CODE_MIGRATION_OBJECT_REGION_MISMATCH = "MIGRATION_OBJECT_REGION_MISMATCH";
    static final String CODE_MIGRATION_DUPLICATE_OBJECT = "MIGRATION_DUPLICATE_OBJECT";

    private static final String OP_REGION_REGISTER = "REGION_REGISTER";
    private static final String OP_REGION_BIND = "REGION_BIND";
    private static final String OP_MIGRATE = "MIGRATE";
    private static final String OP_QUERY = "QUERY";

    private final ConsentRepository consentRepository;
    private final ResidencyRepository residencyRepository;
    private final IdempotencySupport idempotency;
    private final Clock clock;

    public ResidencyService(ConsentRepository consentRepository,
                            ResidencyRepository residencyRepository,
                            IdempotencySupport idempotency,
                            Clock clock) {
        this.consentRepository = consentRepository;
        this.residencyRepository = residencyRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 区域登记：区域代码必须预先登记；重复登记同一区域幂等返回首次结果，异参 409。
     */
    @Transactional
    public RegionResponse registerRegion(RegionRegisterRequest request) {
        String fingerprint = OP_REGION_REGISTER + "|" + request.regionCode();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), RegionResponse.class);
        }

        Instant createdAt = clock.instant();
        try {
            residencyRepository.insertRegion(request.regionCode(), request.requestId(), createdAt);
        } catch (DuplicateKeyException concurrent) {
            // 并发登记同一区域：以已提交登记为准，重复登记按幂等成功处理
            ResidencyRepository.RegionRow committed = residencyRepository.findRegion(request.regionCode())
                    .orElseThrow(() -> concurrent);
            RegionResponse response = new RegionResponse(committed.regionCode(), committed.createdAt());
            idempotency.storeSuccess(request.requestId(), OP_REGION_REGISTER, fingerprint, response);
            return response;
        }
        RegionResponse response = new RegionResponse(request.regionCode(), createdAt);
        idempotency.storeSuccess(request.requestId(), OP_REGION_REGISTER, fingerprint, response);
        return response;
    }

    /**
     * 区域绑定：为指定授权代次登记驻留区域；同一代次的区域不可直接修改（重复绑定相同区域幂等成功，
     * 绑定其他区域返回 409）。
     */
    @Transactional
    public RegionBindResponse bindRegion(RegionBindRequest request) {
        String fingerprint = OP_REGION_BIND + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.regionCode();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), RegionBindResponse.class);
        }

        GrantRow grant = consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (residencyRepository.findRegion(request.regionCode()).isEmpty()) {
            throw ApiException.badRequest(ConsentService.CODE_REGION_NOT_REGISTERED,
                    "驻留区域尚未登记: " + request.regionCode());
        }
        if (grant.regionCode() != null && !grant.regionCode().equals(request.regionCode())) {
            throw ApiException.conflict(CODE_REGION_ALREADY_BOUND,
                    "授权代次已绑定其他驻留区域，区域不可直接修改: " + grant.regionCode());
        }
        consentRepository.bindRegion(request.subjectKey(), request.purpose(), request.epoch(), request.regionCode());
        RegionBindResponse response = new RegionBindResponse(request.subjectKey(), request.purpose(),
                request.epoch(), request.regionCode());
        idempotency.storeSuccess(request.requestId(), OP_REGION_BIND, fingerprint, response);
        return response;
    }

    /**
     * 驻留迁移：创建新的授权代次，将明确列出的对象原子复制到目标区域，源代次同时撤回。
     * 先核对整批最终状态再一次提交：集合含已清除、无授权或不同区域对象时整次 422，不留部分副本。
     */
    @Transactional
    public MigrateResponse migrate(MigrateRequest request) {
        String fingerprint = OP_MIGRATE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.targetRegion() + "|" + request.recordKeys();
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotency.readSnapshot(replayed.get().responseBody(), MigrateResponse.class);
        }

        // 锁定当前最新代次行，按提交顺序裁决并发迁移，避免两个事务生成同一目标代次
        GrantRow source = consentRepository.findLatestGrantForUpdate(
                request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (source.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_MIGRATION_SOURCE_REVOKED, "源授权代次已撤回，请先重新授权");
        }
        if (residencyRepository.findRegion(request.targetRegion()).isEmpty()) {
            throw ApiException.unprocessableEntity(CODE_MIGRATION_TARGET_NOT_REGISTERED,
                    "目标驻留区域尚未登记: " + request.targetRegion());
        }
        if (request.targetRegion().equals(source.regionCode())) {
            throw ApiException.unprocessableEntity(CODE_MIGRATION_OBJECT_REGION_MISMATCH,
                    "目标驻留区域与源驻留区域相同，无需迁移: " + request.targetRegion());
        }

        List<String> requestedKeys = request.recordKeys();
        int distinctCount = new HashSet<>(requestedKeys).size();
        if (distinctCount != requestedKeys.size()) {
            throw ApiException.unprocessableEntity(CODE_MIGRATION_DUPLICATE_OBJECT,
                    "迁移集合包含重复记录键，要求数量: " + requestedKeys.size()
                            + "，去重后实际数量: " + distinctCount
                            + "，差额: " + (requestedKeys.size() - distinctCount));
        }

        // 整批核对：所有对象必须存在于源代次、未清除且驻留区域与源代次一致
        List<RecordRow> sourceRecords = consentRepository.findRecords(
                request.subjectKey(), request.purpose(), source.epoch(), requestedKeys);
        if (sourceRecords.size() != requestedKeys.size()) {
            Set<String> foundKeys = new HashSet<>();
            for (RecordRow row : sourceRecords) {
                foundKeys.add(row.recordKey());
            }
            List<String> missing = requestedKeys.stream().filter(key -> !foundKeys.contains(key)).toList();
            throw ApiException.unprocessableEntity(CODE_MIGRATION_OBJECT_NOT_FOUND,
                    "迁移集合含源代次无授权或不存在的对象，要求数量: " + requestedKeys.size()
                            + "，实际数量: " + sourceRecords.size() + "，缺失: " + missing);
        }
        for (RecordRow row : sourceRecords) {
            if (row.erasedAt() != null) {
                throw ApiException.unprocessableEntity(CODE_MIGRATION_OBJECT_ERASED,
                        "迁移集合含已清除对象: " + row.recordKey());
            }
            if (!regionsEqual(row.regionCode(), source.regionCode())) {
                throw ApiException.unprocessableEntity(CODE_MIGRATION_OBJECT_REGION_MISMATCH,
                        "迁移集合含不同驻留区域对象: " + row.recordKey()
                                + "，对象区域: " + row.regionCode()
                                + "，源代次区域: " + source.regionCode());
            }
        }

        int targetEpoch = source.epoch() + 1;
        int targetVersion = source.consentVersion() + 1;
        consentRepository.insertMigratedGrant(request.subjectKey(), request.purpose(), targetEpoch,
                targetVersion, request.targetRegion(), request.requestId());
        for (RecordRow row : sourceRecords) {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), targetEpoch,
                    row.recordKey(), row.payload(), request.targetRegion(), request.requestId());
        }
        boolean revoked = consentRepository.revokeGrant(
                request.subjectKey(), request.purpose(), source.epoch());
        if (!revoked) {
            // 锁内复核失败说明状态被其他事务改写，整批回滚
            throw ApiException.gone(CODE_MIGRATION_SOURCE_REVOKED, "源授权代次状态已变更");
        }

        MigrateResponse response = new MigrateResponse(request.subjectKey(), request.purpose(),
                source.epoch(), targetEpoch, source.regionCode(), request.targetRegion(),
                requestedKeys.size(), sourceRecords.size());
        idempotency.storeSuccess(request.requestId(), OP_MIGRATE, fingerprint, response);
        return response;
    }

    /**
     * 跨对象查询：授权有效、epoch 一致且全部对象驻留区域在允许集合内时返回冻结快照；
     * 任一对象区域不允许返回 403，代次撤回或对象已清除返回 410，不返回部分结果。
     */
    @Transactional
    public BatchQueryResponse query(BatchQueryRequest request) {
        String fingerprint = OP_QUERY + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + sorted(request.recordKeys()) + "|" + sorted(request.allowedRegions());
        Optional<IdempotencyRow> replayed = idempotency.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            // 同参重放返回首次完整快照，快照内容不随后续迁移/撤回/清除改写
            return idempotency.readSnapshot(replayed.get().responseBody(), BatchQueryResponse.class);
        }

        GrantRow grant = consentRepository.findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回");
        }

        List<String> requestedKeys = request.recordKeys();
        if (new HashSet<>(requestedKeys).size() != requestedKeys.size()) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "查询记录键集合包含重复项");
        }
        Set<String> allowed = new HashSet<>(request.allowedRegions());

        List<RecordRow> records = consentRepository.findRecords(
                request.subjectKey(), request.purpose(), grant.epoch(), requestedKeys);
        if (records.size() != requestedKeys.size()) {
            throw ApiException.notFound(ConsentService.CODE_RECORD_NOT_FOUND, "部分对象不存在，整次查询拒绝");
        }
        // 全部来自同一最新代次（findRecords 限定 epoch），逐对象核对区域与清除状态
        for (RecordRow row : records) {
            if (row.erasedAt() != null) {
                throw ApiException.gone(CODE_RECORD_ERASED, "对象已清除: " + row.recordKey());
            }
            if (!allowed.contains(row.regionCode())) {
                throw ApiException.forbidden(CODE_REGION_NOT_ALLOWED,
                        "对象驻留区域不在允许集合内: " + row.recordKey()
                                + "，对象区域: " + row.regionCode());
            }
        }

        List<RecordRow> ordered = records.stream()
                .sorted(java.util.Comparator.comparing(RecordRow::recordKey))
                .toList();
        List<BatchQueryItem> items = new ArrayList<>();
        for (RecordRow row : ordered) {
            items.add(new BatchQueryItem(row.recordKey(), row.epoch(), row.regionCode(),
                    grant.consentVersion(), row.payload()));
        }

        Instant createdAt = clock.instant();
        String snapshotId = UUID.randomUUID().toString().replace("-", "");
        residencyRepository.insertSnapshot(snapshotId, request.requestId(), request.subjectKey(),
                request.purpose(), grant.epoch(), createdAt);
        for (int i = 0; i < items.size(); i++) {
            BatchQueryItem item = items.get(i);
            residencyRepository.insertSnapshotItem(snapshotId, item.recordKey(), item.epoch(),
                    item.regionCode(), item.consentVersion(), item.payload(), i);
        }

        BatchQueryResponse response = new BatchQueryResponse(snapshotId, request.subjectKey(),
                request.purpose(), grant.epoch(), request.allowedRegions().stream().sorted().toList(),
                items, createdAt);
        idempotency.storeSuccess(request.requestId(), OP_QUERY, fingerprint, response);
        return response;
    }

    /**
     * 快照明细（只读）：返回冻结的一致快照，后续迁移/撤回/清除均不改写；快照不存在 404。
     */
    @Transactional(readOnly = true)
    public SnapshotDetailResponse getSnapshot(String snapshotId) {
        SnapshotRow snapshot = residencyRepository.findSnapshot(snapshotId)
                .orElseThrow(() -> ApiException.notFound(CODE_SNAPSHOT_NOT_FOUND, "查询快照不存在"));
        List<BatchQueryItem> items = residencyRepository.findSnapshotItems(snapshotId).stream()
                .map(this::toItem)
                .toList();
        return new SnapshotDetailResponse(snapshot.snapshotId(), snapshot.requestId(),
                snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch(), items, snapshot.createdAt());
    }

    /**
     * 快照历史（只读）：时间区间左闭右开 [from, to)（UTC），边界为 null 表示不限制。
     */
    @Transactional(readOnly = true)
    public List<SnapshotSummaryResponse> listSnapshots(String subjectKey, Purpose purpose,
                                                       Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_ARGUMENT", "时间区间必须满足 from < to（左闭右开）");
        }
        List<SnapshotSummaryResponse> summaries = new ArrayList<>();
        for (SnapshotRow snapshot : residencyRepository.findSnapshots(subjectKey, purpose, from, to)) {
            summaries.add(new SnapshotSummaryResponse(snapshot.snapshotId(), snapshot.requestId(),
                    snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch(),
                    residencyRepository.countSnapshotItems(snapshot.snapshotId()), snapshot.createdAt()));
        }
        return summaries;
    }

    /**
     * 代次驻留诊断（只读）：列出全部代次的状态、驻留区域、授权版本与记录数量。
     */
    @Transactional(readOnly = true)
    public List<EpochDiagnosticResponse> diagnoseEpochs(String subjectKey, Purpose purpose) {
        List<EpochDiagnosticResponse> diagnostics = new ArrayList<>();
        for (GrantRow grant : consentRepository.findGrants(subjectKey, purpose)) {
            int total = consentRepository.countRecords(subjectKey, purpose, grant.epoch());
            int erased = consentRepository.countErasedRecords(subjectKey, purpose, grant.epoch());
            Optional<Instant> latestRecordAt =
                    consentRepository.findLatestRecordCreatedAt(subjectKey, purpose, grant.epoch());
            diagnostics.add(new EpochDiagnosticResponse(subjectKey, purpose, grant.epoch(),
                    grant.status().name(), grant.regionCode(), grant.consentVersion(),
                    total, erased, latestRecordAt.orElse(null)));
        }
        return diagnostics;
    }

    /**
     * 已登记区域清单（只读），按区域代码升序。
     */
    @Transactional(readOnly = true)
    public List<RegionResponse> listRegions() {
        return residencyRepository.findAllRegions().stream()
                .map(row -> new RegionResponse(row.regionCode(), row.createdAt()))
                .toList();
    }

    private boolean regionsEqual(String left, String right) {
        if (left == null) {
            return right == null;
        }
        return left.equals(right);
    }

    private List<String> sorted(List<String> values) {
        return new ArrayList<>(new LinkedHashSet<>(values)).stream().sorted().toList();
    }

    private BatchQueryItem toItem(SnapshotItemRow row) {
        return new BatchQueryItem(row.recordKey(), row.epoch(), row.regionCode(),
                row.consentVersion(), row.payload());
    }
}
