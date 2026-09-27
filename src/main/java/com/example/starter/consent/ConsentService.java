package com.example.starter.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.ConsentRepository.GrantRow;
import com.example.starter.consent.ConsentRepository.RecordRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.EraseRequest;
import com.example.starter.consent.dto.EraseResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.LegalHoldRequest;
import com.example.starter.consent.dto.MigrationRequest;
import com.example.starter.consent.dto.MigrationResponse;
import com.example.starter.consent.dto.QueryRequest;
import com.example.starter.consent.dto.QueryResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RegionRegisterRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：在既有授权、撤回、写入与查询幂等语义之上，实现授权数据驻留：
 * epoch 级驻留区域登记与同区域写入路由、跨对象区域集合查询与不可变合规快照、
 * 跨区域原子迁移（新 epoch＋复制＋源 epoch 撤回）、数据清除以及撤回后的合法保留受限读取。
 *
 * <p>所有时间按 UTC 处理；保留/统计区间左闭右开。所有写操作满足：先核对整批最终状态，
 * 再在同一事务内一次提交，任一预检失败不留下半成品；同一 requestId 同参重放返回首次完整
 * 快照，异参 409，业务失败不占用 requestId。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_RECORD_ERASED = "RECORD_ERASED";
    static final String CODE_RECORD_ALREADY_ERASED = "RECORD_ALREADY_ERASED";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_REGION_NOT_FOUND = "REGION_NOT_FOUND";
    static final String CODE_REGION_ALREADY_REGISTERED = "REGION_ALREADY_REGISTERED";
    static final String CODE_REGION_MISMATCH = "REGION_MISMATCH";
    static final String CODE_REGION_NOT_ALLOWED = "REGION_NOT_ALLOWED";
    static final String CODE_EPOCH_MISMATCH = "EPOCH_MISMATCH";
    static final String CODE_MIGRATION_REJECTED = "MIGRATION_REJECTED";
    static final String CODE_INVALID_LEGAL_HOLD_WINDOW = "INVALID_LEGAL_HOLD_WINDOW";
    static final String CODE_LEGAL_HOLD_ALREADY_EXISTS = "LEGAL_HOLD_ALREADY_EXISTS";
    static final String CODE_SNAPSHOT_NOT_FOUND = "SNAPSHOT_NOT_FOUND";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_REGISTER_REGION = "REGION_REGISTER";
    private static final String OP_MIGRATE = "MIGRATE";
    private static final String OP_ERASE = "ERASE";
    private static final String OP_QUERY = "QUERY";

    /**
     * 预置驻留区域：未显式携带区域路由的历史调用方缺省落在此区域，该区域在启动时幂等登记。
     */
    public static final String DEFAULT_REGION = "DEFAULT";

    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final RegionRepository regionRepository;
    private final MigrationRepository migrationRepository;
    private final ErasureRepository erasureRepository;
    private final LegalHoldRepository legalHoldRepository;
    private final QuerySnapshotRepository querySnapshotRepository;
    private final ObjectMapper objectMapper;
    private final ServiceClock clock;

    public ConsentService(ConsentRepository consentRepository,
                          IdempotencyRepository idempotencyRepository,
                          RegionRepository regionRepository,
                          MigrationRepository migrationRepository,
                          ErasureRepository erasureRepository,
                          LegalHoldRepository legalHoldRepository,
                          QuerySnapshotRepository querySnapshotRepository,
                          ObjectMapper objectMapper,
                          ServiceClock clock) {
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.regionRepository = regionRepository;
        this.migrationRepository = migrationRepository;
        this.erasureRepository = erasureRepository;
        this.legalHoldRepository = legalHoldRepository;
        this.querySnapshotRepository = querySnapshotRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 区域登记 ----------

    /**
     * 区域登记：区域代码必须先登记才能被 epoch 绑定；重复登记同代码同名称视为成功，不同名称 409。
     */
    @Transactional
    public RegionRegisterRequest.Response registerRegion(RegionRegisterRequest request) {
        String fingerprint = OP_REGISTER_REGION + "|" + request.regionCode() + "|" + request.regionName();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RegionRegisterRequest.Response.class);
        }

        RegionRepository.RegionRow existing = regionRepository.find(request.regionCode()).orElse(null);
        RegionRegisterRequest.Response response;
        if (existing != null) {
            if (!existing.regionName().equals(request.regionName())) {
                throw ApiException.conflict(CODE_REGION_ALREADY_REGISTERED,
                        "区域代码已登记且名称不一致: " + request.regionCode());
            }
            response = new RegionRegisterRequest.Response(existing.regionCode(), existing.regionName(),
                    existing.createdAt());
        } else {
            try {
                regionRepository.insert(request.regionCode(), request.regionName(), request.requestId());
            } catch (DuplicateKeyException concurrent) {
                RegionRepository.RegionRow committed = regionRepository.find(request.regionCode())
                        .orElseThrow(() -> concurrent);
                if (!committed.regionName().equals(request.regionName())) {
                    throw ApiException.conflict(CODE_REGION_ALREADY_REGISTERED,
                            "区域代码已登记且名称不一致: " + request.regionCode());
                }
            }
            Instant registeredAt = regionRepository.find(request.regionCode()).orElseThrow().createdAt();
            response = new RegionRegisterRequest.Response(request.regionCode(), request.regionName(), registeredAt);
        }
        storeSuccess(request.requestId(), OP_REGISTER_REGION, fingerprint, response);
        return response;
    }

    // ---------- 授权 ----------

    /**
     * 授权：当前授权仍有效时返回原代次（区域不可变更，携带不同区域返回 403）；
     * 撤回后或首次授权生成下一代（从 1 开始递增），并为该 epoch 绑定驻留区域。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String requestedRegion = request.region() == null ? DEFAULT_REGION : request.region();
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + requestedRegion;
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        // 锁定最新代次，串行化并发授权与跨区域迁移对 epoch 序列的推进
        GrantRow latest = consentRepository.lockLatestGrant(request.subjectKey(), request.purpose())
                .orElse(null);
        // 持锁后二次检查：并发同 requestId 的线程以已提交快照为准
        replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }
        GrantResponse response;
        if (latest != null && latest.status() == GrantStatus.ACTIVE) {
            if (!latest.regionCode().equals(requestedRegion)) {
                throw ApiException.forbidden(CODE_REGION_MISMATCH,
                        "当前有效授权驻留区域为 " + latest.regionCode() + "，同 epoch 区域不可直接修改");
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    latest.epoch(), latest.regionCode(), GrantStatus.ACTIVE);
        } else {
            requireRegionRegistered(requestedRegion);
            int nextEpoch = latest == null ? 1 : latest.epoch() + 1;
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch,
                        requestedRegion, request.requestId());
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                GrantRow committed = consentRepository
                        .findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                        .orElseThrow(() -> concurrent);
                response = new GrantResponse(committed.subjectKey(), committed.purpose(),
                        committed.epoch(), committed.regionCode(), committed.status());
                storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    nextEpoch, requestedRegion, GrantStatus.ACTIVE);
        }
        storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    // ---------- 写入（同区域路由） ----------

    /**
     * 写入：仅当前有效代次可写；请求必须携带与 epoch 相同的驻留区域（同区域路由），区域不符整次 403。
     * 同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        // 先核对授权状态与同区域路由：撤回后即使是同 requestId 重放也必须 410，错区域即使重放也必须 403，
        // 业务失败不占用 requestId
        GrantRow latest = consentRepository.findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        String routedRegion = request.region() == null ? latest.regionCode() : request.region();
        if (!latest.regionCode().equals(routedRegion)) {
            throw ApiException.forbidden(CODE_REGION_MISMATCH,
                    "写入驻留区域 " + routedRegion + " 与授权 epoch 区域 " + latest.regionCode() + " 不一致");
        }

        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload() + "|" + routedRegion;
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RecordResponse.class);
        }

        Optional<RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get(), false);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.payload(), latest.regionCode(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(committed, false);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), request.payload(), latest.regionCode(), 1, false);
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    // ---------- 撤回 ----------

    /**
     * 撤回：指定代次只允许从有效变为已撤回（用户撤回，UTC 时间）；撤回提交后旧代查询立即 410，写入被拒绝。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        GrantRow grant = consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(),
                request.epoch(), RevokeReason.USER_REVOKE, clock.now());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), grant.regionCode(), GrantStatus.REVOKED);
        storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    // ---------- 跨区域迁移 ----------

    /**
     * 迁移：创建新授权 epoch（目标区域），将明确列出的对象原子复制到目标区域（版本 +1），
     * 同时撤回源 epoch。任一对象已清除、无授权（不存在）、属于其他 epoch/区域，或源 epoch
     * 不是当前有效代次、目标区域未登记/与源相同，整次 422，源和目标均不留下部分副本。
     */
    @Transactional
    public MigrationResponse migrate(MigrationRequest request) {
        List<String> requestedKeys = normalize(request.recordKeys());
        String fingerprint = OP_MIGRATE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.sourceEpoch() + "|" + request.targetRegion() + "|" + String.join(",", requestedKeys);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), MigrationResponse.class);
        }

        List<ErrorResponse.FailureDetail> failures = new ArrayList<>();

        // 锁定最新代次：与并发授权/迁移按提交顺序裁决，先提交者使后来者预检失败
        GrantRow latest = consentRepository.lockLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        // 持锁后二次检查：并发同 requestId 的迁移以已提交快照为准
        replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), MigrationResponse.class);
        }
        GrantRow source = consentRepository.findGrant(request.subjectKey(), request.purpose(),
                request.sourceEpoch()).orElse(null);
        if (source == null) {
            throw ApiException.notFound(CODE_GRANT_NOT_FOUND, "源授权代次不存在");
        }
        if (latest.epoch() != source.epoch()) {
            failures.add(new ErrorResponse.FailureDetail(null, "SOURCE_NOT_CURRENT",
                    "源代次 " + source.epoch() + " 不是当前代次 " + latest.epoch() + "，只能迁移当前有效代次"));
        }
        if (source.status() == GrantStatus.REVOKED) {
            failures.add(new ErrorResponse.FailureDetail(null, "SOURCE_EPOCH_REVOKED",
                    "源代次 " + source.epoch() + " 已撤回，不能迁移"));
        }
        if (regionRepository.find(request.targetRegion()).isEmpty()) {
            failures.add(new ErrorResponse.FailureDetail(null, CODE_REGION_NOT_FOUND,
                    "目标驻留区域未登记: " + request.targetRegion()));
        }
        if (request.targetRegion().equals(source.regionCode())) {
            failures.add(new ErrorResponse.FailureDetail(null, "MIGRATION_TARGET_SAME_AS_SOURCE",
                    "目标区域与源区域相同: " + request.targetRegion()));
        }
        if (request.recordKeys().size() != requestedKeys.size()) {
            failures.add(new ErrorResponse.FailureDetail(null, "MIGRATION_DUPLICATE_OBJECT",
                    "迁移对象集合存在重复键，要求 " + request.recordKeys().size()
                            + " 个、去重后 " + requestedKeys.size() + " 个"));
        }

        // 逐对象预检：任何一项失败都收集原因，最终整次 422，不执行任何写入
        List<RecordRow> sources = new ArrayList<>();
        for (String key : requestedKeys) {
            Optional<RecordRow> row = consentRepository.findRecord(
                    request.subjectKey(), request.purpose(), source.epoch(), key);
            if (row.isEmpty()) {
                Optional<RecordRow> otherEpoch = consentRepository.findRecordAnyEpoch(
                        request.subjectKey(), request.purpose(), key);
                if (otherEpoch.isPresent()) {
                    failures.add(new ErrorResponse.FailureDetail(key, CODE_EPOCH_MISMATCH,
                            "对象属于代次 " + otherEpoch.get().epoch() + "，不属于源代次 " + source.epoch()));
                } else {
                    failures.add(new ErrorResponse.FailureDetail(key, "OBJECT_NOT_FOUND",
                            "对象在源代次不存在，无授权记录"));
                }
                continue;
            }
            RecordRow record = row.get();
            if (record.erased()) {
                failures.add(new ErrorResponse.FailureDetail(key, CODE_RECORD_ERASED, "对象已清除，不能迁移"));
                continue;
            }
            if (!record.regionCode().equals(source.regionCode())) {
                failures.add(new ErrorResponse.FailureDetail(key, CODE_REGION_MISMATCH,
                        "对象驻留区域 " + record.regionCode() + " 与源 epoch 区域 "
                                + source.regionCode() + " 不一致"));
                continue;
            }
            sources.add(record);
        }

        if (!failures.isEmpty()) {
            throw ApiException.unprocessable(CODE_MIGRATION_REJECTED,
                    "迁移预检失败：要求 " + requestedKeys.size() + " 个、可迁移 0 个、差额 "
                            + requestedKeys.size(),
                    failures);
        }

        Instant now = clock.now();
        int targetEpoch = source.epoch() + 1;
        consentRepository.insertGrant(request.subjectKey(), request.purpose(), targetEpoch,
                request.targetRegion(), request.requestId());
        consentRepository.revokeGrant(request.subjectKey(), request.purpose(), source.epoch(),
                RevokeReason.MIGRATION, now);

        for (RecordRow record : sources) {
            consentRepository.copyRecord(record, targetEpoch, request.targetRegion(), request.requestId());
        }
        migrationRepository.insert(new MigrationRepository.MigrationRow(
                request.requestId(), request.subjectKey(), request.purpose(), source.epoch(),
                source.regionCode(), targetEpoch, request.targetRegion(), sources.size(),
                "COMMITTED", now));
        for (RecordRow record : sources) {
            migrationRepository.insertItem(new MigrationRepository.MigrationItemRow(
                    request.requestId(), request.subjectKey(), request.purpose(), source.epoch(),
                    targetEpoch, record.recordKey(), source.regionCode(), request.targetRegion()));
        }

        MigrationResponse response = new MigrationResponse(
                request.requestId(), request.subjectKey(), request.purpose(),
                source.epoch(), source.regionCode(), targetEpoch, request.targetRegion(),
                requestedKeys.size(), sources.size(), sources.size() - requestedKeys.size(),
                requestedKeys, "COMMITTED", now);
        storeSuccess(request.requestId(), OP_MIGRATE, fingerprint, response);
        return response;
    }

    // ---------- 数据清除 ----------

    /**
     * 清除：置空指定对象的 payload 并写入清除审计；历史列、驻留区域与已生成快照均不改写。
     * 同一 requestId 同参重放返回首次结果；对象已被其他请求清除时返回 409。
     */
    @Transactional
    public EraseResponse erase(EraseRequest request) {
        String fingerprint = OP_ERASE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch() + "|" + request.recordKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), EraseResponse.class);
        }

        GrantRow grant = consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        RecordRow record = consentRepository.findRecord(request.subjectKey(), request.purpose(),
                request.epoch(), request.recordKey())
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        if (record.erased()) {
            throw ApiException.conflict(CODE_RECORD_ALREADY_ERASED,
                    "对象已由清除请求 " + record.eraseRequestId() + " 清除");
        }

        Instant now = clock.now();
        consentRepository.eraseRecord(request.subjectKey(), request.purpose(), request.epoch(),
                request.recordKey(), request.requestId(), now);
        erasureRepository.insert(new ErasureRepository.ErasureRow(
                request.requestId(), request.subjectKey(), request.purpose(), request.epoch(),
                request.recordKey(), "ERASED", now));

        EraseResponse response = new EraseResponse(request.requestId(), request.subjectKey(),
                request.purpose(), request.epoch(), request.recordKey(), grant.regionCode(), "ERASED", now);
        storeSuccess(request.requestId(), OP_ERASE, fingerprint, response);
        return response;
    }

    // ---------- 跨对象查询（合规快照） ----------

    /**
     * 跨对象查询：仅当授权有效、所有对象属于当前 epoch、未清除且驻留区域均在允许集合内时返回数据；
     * 任一条件不满足整次 403/404/410，不返回部分结果。成功响应固化为不可变合规快照，
     * 同一 requestId 同参重放始终返回首次完整快照（即使随后发生迁移、撤回或清除），异参 409。
     */
    @Transactional
    public QueryResponse queryRecords(QueryRequest request) {
        List<String> regions = normalize(request.regions());
        List<String> keys = normalize(request.recordKeys());
        String fingerprint = OP_QUERY + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + String.join(",", regions) + "|" + String.join(",", keys);

        Optional<QuerySnapshotRepository.SnapshotRow> existingSnapshot =
                querySnapshotRepository.find(request.requestId());
        if (existingSnapshot.isPresent()) {
            QuerySnapshotRepository.SnapshotRow snapshot = existingSnapshot.get();
            if (!snapshot.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 查询参数不一致");
            }
            return readSnapshot(snapshot.responseBody(), QueryResponse.class);
        }

        // 先锁最新代次判授权状态：epoch 已撤回时整次 410，优先于区域登记与对象校验，
        // 且与迁移/撤回的提交顺序互斥裁决，查询先提交则拿到一致快照
        GrantRow latest = consentRepository.lockLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (latest.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回，跨对象查询被拒绝");
        }

        for (String region : regions) {
            if (regionRepository.find(region).isEmpty()) {
                throw ApiException.notFound(CODE_REGION_NOT_FOUND, "允许区域包含未登记区域: " + region);
            }
        }

        List<RecordRow> records = consentRepository.findRecords(
                request.subjectKey(), request.purpose(), latest.epoch(), keys);
        for (String key : keys) {
            RecordRow matched = records.stream().filter(row -> row.recordKey().equals(key)).findFirst()
                    .orElse(null);
            if (matched == null) {
                Optional<RecordRow> otherEpoch = consentRepository.findRecordAnyEpoch(
                        request.subjectKey(), request.purpose(), key);
                if (otherEpoch.isPresent()) {
                    throw new ApiException(org.springframework.http.HttpStatus.GONE, CODE_EPOCH_MISMATCH,
                            "对象属于已失效代次 " + otherEpoch.get().epoch()
                                    + "，与当前代次 " + latest.epoch() + " 不一致");
                }
                throw ApiException.notFound(CODE_RECORD_NOT_FOUND, "对象不存在: " + key);
            }
            if (matched.erased()) {
                throw ApiException.notFound(CODE_RECORD_ERASED, "对象已清除: " + key);
            }
            if (!regions.contains(matched.regionCode())) {
                throw ApiException.forbidden(CODE_REGION_NOT_ALLOWED,
                        "对象 " + key + " 驻留区域 " + matched.regionCode() + " 不在允许区域集合内");
            }
        }

        Instant now = clock.now();
        List<QueryResponse.SnapshotObject> objects = records.stream()
                .map(row -> new QueryResponse.SnapshotObject(row.recordKey(), row.epoch(),
                        row.regionCode(), row.consentVersion(), row.payload()))
                .toList();
        QueryResponse response = new QueryResponse(request.requestId(), request.subjectKey(),
                request.purpose(), regions, objects, objects.size(), now);

        String body = writeSnapshot(response);
        try {
            querySnapshotRepository.insert(new QuerySnapshotRepository.SnapshotRow(
                    request.requestId(), request.subjectKey(), request.purpose(),
                    String.join(",", regions), fingerprint, objects.size(), body, now));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId 查询：以先提交者的冻结快照为准（参数必须一致，否则前面已判 409）
            QuerySnapshotRepository.SnapshotRow committed = querySnapshotRepository.find(request.requestId())
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 查询参数不一致");
            }
            return readSnapshot(committed.responseBody(), QueryResponse.class);
        }
        int ordinal = 0;
        for (QueryResponse.SnapshotObject object : objects) {
            querySnapshotRepository.insertItem(new QuerySnapshotRepository.SnapshotItemRow(
                    request.requestId(), request.subjectKey(), request.purpose(), object.recordKey(),
                    object.epoch(), object.region(), object.consentVersion(), object.payload(), ordinal++));
        }
        return response;
    }

    // ---------- 单对象读取（含合法保留受限读取） ----------

    /**
     * 单对象查询：当前有效代次正常读取；撤回后仅当对象处于有效合法保留窗口（UTC 左闭右开）时
     * 允许受限读取（restricted=true），否则 410；已清除对象不可通过任何读取通道恢复内容；
     * 合法保留不得用于跨对象跨域查询。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        RecordRow record = consentRepository.findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        if (record.erased()) {
            throw ApiException.notFound(CODE_RECORD_ERASED, "记录已清除");
        }
        if (latest.status() == GrantStatus.ACTIVE) {
            return toResponse(record, false);
        }
        Instant now = clock.now();
        boolean retained = legalHoldRepository.listForRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .stream().anyMatch(hold -> hold.covers(now));
        if (!retained) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        return toResponse(record, true);
    }

    /**
     * 登记合法保留窗口：窗口按 UTC 左闭右开解释，validFrom &lt;= validTo（相等为零长度窗口）；
     * 必须指向存在的授权代次与记录。返回窗口实际时长（秒，实际值）。
     */
    @Transactional
    public com.example.starter.consent.dto.LegalHoldResponse registerLegalHold(LegalHoldRequest request) {
        if (request.validTo().isBefore(request.validFrom())) {
            throw ApiException.badRequest(CODE_INVALID_LEGAL_HOLD_WINDOW,
                    "保留窗口终点早于起点：要求 validFrom <= validTo（UTC，左闭右开）");
        }
        consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        consentRepository.findRecord(request.subjectKey(), request.purpose(), request.epoch(),
                request.recordKey()).orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        try {
            legalHoldRepository.insert(request.subjectKey(), request.purpose(), request.epoch(),
                    request.recordKey(), request.holdReason(), request.validFrom(), request.validTo());
        } catch (DuplicateKeyException concurrent) {
            throw ApiException.conflict(CODE_LEGAL_HOLD_ALREADY_EXISTS, "相同起点的合法保留记录已存在");
        }
        long durationSeconds = request.validTo().getEpochSecond() - request.validFrom().getEpochSecond();
        return new com.example.starter.consent.dto.LegalHoldResponse(request.subjectKey(), request.purpose(),
                request.epoch(), request.recordKey(), request.holdReason(),
                request.validFrom(), request.validTo(), durationSeconds);
    }

    /**
     * 列出指定对象的合法保留记录（只读诊断），按窗口起点升序。
     */
    @Transactional(readOnly = true)
    public List<com.example.starter.consent.dto.LegalHoldResponse> listLegalHolds(
            String subjectKey, Purpose purpose, int epoch, String recordKey) {
        return legalHoldRepository.listForRecord(subjectKey, purpose, epoch, recordKey).stream()
                .map(hold -> new com.example.starter.consent.dto.LegalHoldResponse(
                        hold.subjectKey(), hold.purpose(), hold.epoch(), hold.recordKey(), hold.holdReason(),
                        hold.validFrom(), hold.validTo(),
                        hold.validTo().getEpochSecond() - hold.validFrom().getEpochSecond()))
                .toList();
    }

    // ---------- 只读明细 / 历史 / 诊断 ----------

    @Transactional(readOnly = true)
    public List<RegionRepository.RegionRow> listRegions() {
        return regionRepository.listAll();
    }

    @Transactional(readOnly = true)
    public List<GrantRow> listGrantHistory(String subjectKey, Purpose purpose) {
        return consentRepository.listGrants(subjectKey, purpose);
    }

    @Transactional(readOnly = true)
    public List<RecordRow> listRecordHistory(String subjectKey, Purpose purpose) {
        return consentRepository.listRecords(subjectKey, purpose);
    }

    @Transactional(readOnly = true)
    public List<MigrationRepository.MigrationRow> listMigrations(String subjectKey, Purpose purpose) {
        return migrationRepository.listBySubject(subjectKey, purpose);
    }

    @Transactional(readOnly = true)
    public List<MigrationRepository.MigrationItemRow> listMigrationItems(String migrationId) {
        migrationRepository.find(migrationId)
                .orElseThrow(() -> ApiException.notFound("MIGRATION_NOT_FOUND", "迁移单不存在: " + migrationId));
        return migrationRepository.listItems(migrationId);
    }

    @Transactional(readOnly = true)
    public List<ErasureRepository.ErasureRow> listErasures(String subjectKey, Purpose purpose) {
        return erasureRepository.listBySubject(subjectKey, purpose);
    }

    /**
     * 读取已固化的查询快照：直接返回首次成功响应，不重新校验授权或驻留状态（只读诊断通道）。
     */
    @Transactional(readOnly = true)
    public QueryResponse getSnapshot(String snapshotId) {
        QuerySnapshotRepository.SnapshotRow row = querySnapshotRepository.find(snapshotId)
                .orElseThrow(() -> ApiException.notFound(CODE_SNAPSHOT_NOT_FOUND, "查询快照不存在: " + snapshotId));
        return readSnapshot(row.responseBody(), QueryResponse.class);
    }

    // ---------- 内部辅助 ----------

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    private void requireRegionRegistered(String regionCode) {
        if (regionRepository.find(regionCode).isEmpty()) {
            throw ApiException.notFound(CODE_REGION_NOT_FOUND, "驻留区域未登记: " + regionCode);
        }
    }

    private List<String> normalize(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>(values);
        return distinct.stream().sorted().toList();
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
        }
        return row;
    }

    private void storeSuccess(String requestId, String operation, String fingerprint, Object response) {
        try {
            idempotencyRepository.insert(requestId, operation, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            // 并发同 requestId：校验已提交快照参数一致，否则视为冲突
            IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    private RecordResponse toResponse(RecordRow row, boolean restricted) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(), row.recordKey(),
                row.payload(), row.regionCode(), row.consentVersion(), restricted);
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
}
