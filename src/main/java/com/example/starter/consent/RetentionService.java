package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.RetentionHoldRepository.HoldRow;
import com.example.starter.consent.dto.CreateHoldRequest;
import com.example.starter.consent.dto.HoldResponse;
import com.example.starter.consent.dto.HoldStatusEntry;
import com.example.starter.consent.dto.LegalHoldRecordResponse;
import com.example.starter.consent.dto.PurgeRequest;
import com.example.starter.consent.dto.PurgeResponse;
import com.example.starter.consent.dto.ReleaseHistoryEntry;
import com.example.starter.consent.dto.ReleaseHoldRequest;
import com.example.starter.consent.dto.ReleaseResponse;
import com.example.starter.consent.dto.RetainedCountResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 保留冻结域服务：冻结创建、人工解除、保留权限只读查询、清除与状态查询。
 *
 * <p>冻结语义：冻结不恢复已撤回授权，也不为普通业务开放读写通道；
 * 仅允许具有保留权限的角色（请求头 X-Retention-Role: RETENTION）按记录标识只读查询，
 * 响应标记 LEGAL_HOLD 且不返回授权可用状态。
 *
 * <p>并发裁决：冻结创建与清除均先按主键锁定授权代次行（SELECT ... FOR UPDATE），
 * 按事务提交顺序生效——冻结先提交则清除保留数据，清除先提交则冻结返回 404。
 *
 * <p>幂等规则与授权域一致：成功结果与业务变更同事务保存，同 requestId 同参重放首次结果，
 * 异参 409，失败不占键。
 */
@Service
public class RetentionService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_HOLD_NOT_FOUND = "HOLD_NOT_FOUND";
    static final String CODE_HOLD_KEY_CONFLICT = "HOLD_KEY_CONFLICT";
    static final String CODE_HOLD_ALREADY_ACTIVE = "HOLD_ALREADY_ACTIVE";
    static final String CODE_HOLD_ALREADY_RELEASED = "HOLD_ALREADY_RELEASED";
    static final String CODE_HOLD_EXPIRED = "HOLD_EXPIRED";
    static final String CODE_HOLD_NOT_ACTIVE = "HOLD_NOT_ACTIVE";
    static final String CODE_HOLD_EXPIRES_AT_INVALID = "HOLD_EXPIRES_AT_INVALID";
    static final String CODE_RETENTION_ROLE_REQUIRED = "RETENTION_ROLE_REQUIRED";
    static final String CODE_RELEASE_ACTOR_FORBIDDEN = "HOLD_RELEASE_ACTOR_FORBIDDEN";
    static final String CODE_EPOCH_NOT_REVOKED = "EPOCH_NOT_REVOKED";
    static final String CODE_EPOCH_DATA_PURGED = "EPOCH_DATA_PURGED";
    static final String CODE_HOLD_ACTIVE = "HOLD_ACTIVE";

    /** 保留权限角色标识：请求头 X-Retention-Role 必须为该值 */
    public static final String RETENTION_ROLE = "RETENTION";
    /** 保留权限角色请求头名称 */
    public static final String RETENTION_ROLE_HEADER = "X-Retention-Role";
    /** 保留查询响应的访问依据标记 */
    public static final String ACCESS_BASIS_LEGAL_HOLD = "LEGAL_HOLD";

    private static final String OP_HOLD_CREATE = "HOLD_CREATE";
    private static final String OP_HOLD_RELEASE = "HOLD_RELEASE";
    private static final String OP_PURGE = "PURGE";

    private final ConsentRepository consentRepository;
    private final RetentionHoldRepository retentionHoldRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RetentionService(ConsentRepository consentRepository,
                            RetentionHoldRepository retentionHoldRepository,
                            IdempotencyRepository idempotencyRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.consentRepository = consentRepository;
        this.retentionHoldRepository = retentionHoldRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建保留冻结：对有效或已撤回代次均可创建；同一 epoch 同一事由只允许一个生效冻结。
     * 已物理清除的代次返回 404，不恢复已清除数据。
     */
    @Transactional
    public HoldResponse createHold(CreateHoldRequest request) {
        String fingerprint = OP_HOLD_CREATE + "|" + request.holdKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.epoch() + "|" + request.legalReason()
                + "|" + request.createdBy() + "|" + request.expiresAt();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), HoldResponse.class);
        }

        // 锁定授权代次行，与清除操作按事务提交顺序互斥裁决
        ConsentRepository.GrantRow grant = consentRepository
                .findGrantForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.purgedAt() != null) {
            throw ApiException.notFound(CODE_EPOCH_DATA_PURGED, "该代次数据已物理清除，无法创建保留冻结");
        }
        Instant now = Instant.now(clock);
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest(CODE_HOLD_EXPIRES_AT_INVALID, "到期时刻必须晚于当前 UTC 时刻");
        }

        Optional<HoldRow> existing = retentionHoldRepository.findByHoldKey(request.holdKey());
        if (existing.isPresent()) {
            HoldResponse response = dedupeSameHold(existing.get(), request);
            storeSuccess(request.requestId(), OP_HOLD_CREATE, fingerprint, response);
            return response;
        }
        if (retentionHoldRepository.hasActiveHoldWithReason(request.subjectKey(), request.purpose(),
                request.epoch(), request.legalReason(), now)) {
            throw ApiException.conflict(CODE_HOLD_ALREADY_ACTIVE, "同一 epoch 同一事由已存在生效冻结");
        }

        try {
            retentionHoldRepository.insert(request.holdKey(), request.subjectKey(), request.purpose(),
                    request.epoch(), request.legalReason(), request.createdBy(),
                    request.requestId(), request.expiresAt());
        } catch (DuplicateKeyException concurrent) {
            // 并发创建同一 holdKey：以已提交的冻结为准
            HoldRow committed = retentionHoldRepository.findByHoldKey(request.holdKey())
                    .orElseThrow(() -> concurrent);
            HoldResponse response = dedupeSameHold(committed, request);
            storeSuccess(request.requestId(), OP_HOLD_CREATE, fingerprint, response);
            return response;
        }

        HoldResponse response = new HoldResponse(request.holdKey(), request.subjectKey(), request.purpose(),
                request.epoch(), request.legalReason(), HoldStatus.ACTIVE,
                request.createdBy(), request.expiresAt());
        storeSuccess(request.requestId(), OP_HOLD_CREATE, fingerprint, response);
        return response;
    }

    /**
     * 人工解除：须由具有保留权限且不同于创建人的操作人提交说明；解除记录不可变。
     * 已解除或已到期的冻结再次解除返回 409。
     */
    @Transactional
    public ReleaseResponse release(ReleaseHoldRequest request, String retentionRole) {
        requireRetentionRole(retentionRole);
        String fingerprint = OP_HOLD_RELEASE + "|" + request.holdKey() + "|"
                + request.releasedBy() + "|" + request.note();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), ReleaseResponse.class);
        }

        HoldRow hold = retentionHoldRepository.findByHoldKeyForUpdate(request.holdKey())
                .orElseThrow(() -> ApiException.notFound(CODE_HOLD_NOT_FOUND, "保留冻结不存在"));
        if (hold.createdBy().equals(request.releasedBy())) {
            throw ApiException.forbidden(CODE_RELEASE_ACTOR_FORBIDDEN, "解除人必须不同于创建人");
        }
        if (hold.status() == HoldStatus.RELEASED) {
            throw ApiException.conflict(CODE_HOLD_ALREADY_RELEASED, "保留冻结已解除");
        }
        Instant now = Instant.now(clock);
        if (!hold.expiresAt().isAfter(now)) {
            throw ApiException.conflict(CODE_HOLD_EXPIRED, "保留冻结已到期，不可再解除");
        }

        retentionHoldRepository.release(request.holdKey(), request.releasedBy(), request.note(), now);
        ReleaseResponse response = new ReleaseResponse(request.holdKey(), HoldStatus.RELEASED,
                request.releasedBy(), now, request.note());
        storeSuccess(request.requestId(), OP_HOLD_RELEASE, fingerprint, response);
        return response;
    }

    /**
     * 清除：物理删除指定已撤回代次的全部记录。存在生效冻结时拒绝并保留数据；
     * 冻结到期或解除后可清除；重复清除同一代次返回 0。
     */
    @Transactional
    public PurgeResponse purge(PurgeRequest request) {
        String fingerprint = OP_PURGE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), PurgeResponse.class);
        }

        // 锁定授权代次行，与冻结创建按事务提交顺序互斥裁决
        ConsentRepository.GrantRow grant = consentRepository
                .findGrantForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.ACTIVE) {
            throw ApiException.conflict(CODE_EPOCH_NOT_REVOKED, "授权代次仍有效，不可清除");
        }
        PurgeResponse response;
        if (grant.purgedAt() != null) {
            // 已清除：清除是幂等操作，重复清除无效果
            response = new PurgeResponse(request.subjectKey(), request.purpose(), request.epoch(), 0);
        } else {
            if (retentionHoldRepository.hasActiveHold(request.subjectKey(), request.purpose(),
                    request.epoch(), Instant.now(clock))) {
                throw ApiException.conflict(CODE_HOLD_ACTIVE, "存在生效保留冻结，该代次数据不得物理清除");
            }
            int deleted = consentRepository.deleteRecords(request.subjectKey(), request.purpose(), request.epoch());
            consentRepository.markPurged(request.subjectKey(), request.purpose(), request.epoch(),
                    Instant.now(clock));
            response = new PurgeResponse(request.subjectKey(), request.purpose(), request.epoch(), deleted);
        }
        storeSuccess(request.requestId(), OP_PURGE, fingerprint, response);
        return response;
    }

    /**
     * 保留权限只读查询：仅当该代次存在生效冻结时允许；响应标记 LEGAL_HOLD，
     * 不返回授权可用状态。冻结到期返回 410，无生效冻结返回 403。
     */
    @Transactional(readOnly = true)
    public LegalHoldRecordResponse readRecordUnderHold(String subjectKey, Purpose purpose, int epoch,
                                                       String recordKey, String retentionRole) {
        requireRetentionRole(retentionRole);
        consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        Instant now = Instant.now(clock);
        List<HoldRow> holds = retentionHoldRepository.findByEpoch(subjectKey, purpose, epoch);
        boolean anyActive = holds.stream().anyMatch(h -> isEffective(h, now));
        if (!anyActive) {
            boolean anyExpired = holds.stream().anyMatch(
                    h -> h.status() == HoldStatus.ACTIVE && !h.expiresAt().isAfter(now));
            if (anyExpired) {
                throw ApiException.gone(CODE_HOLD_EXPIRED, "保留冻结已到期");
            }
            throw ApiException.forbidden(CODE_HOLD_NOT_ACTIVE, "该代次不存在生效保留冻结");
        }
        ConsentRepository.RecordRow record = consentRepository.findRecord(subjectKey, purpose, epoch, recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        return new LegalHoldRecordResponse(ACCESS_BASIS_LEGAL_HOLD, record.subjectKey(), record.purpose(),
                record.epoch(), record.recordKey(), record.payload());
    }

    /**
     * 代次冻结状态查询：返回该代次全部冻结及按当前 UTC 时刻派生的状态。
     */
    @Transactional(readOnly = true)
    public List<HoldStatusEntry> listHolds(String subjectKey, Purpose purpose, int epoch) {
        consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        Instant now = Instant.now(clock);
        return retentionHoldRepository.findByEpoch(subjectKey, purpose, epoch).stream()
                .map(h -> new HoldStatusEntry(h.holdKey(), h.legalReason(), deriveStatus(h, now),
                        h.createdBy(), h.expiresAt()))
                .toList();
    }

    /**
     * 保留记录计数查询：该代次现存（未物理清除）记录数。
     */
    @Transactional(readOnly = true)
    public RetainedCountResponse retainedCount(String subjectKey, Purpose purpose, int epoch) {
        consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        return new RetainedCountResponse(subjectKey, purpose, epoch,
                consentRepository.countRecords(subjectKey, purpose, epoch));
    }

    /**
     * 解除历史查询：返回该代次已解除冻结的不可变解除记录。
     */
    @Transactional(readOnly = true)
    public List<ReleaseHistoryEntry> releaseHistory(String subjectKey, Purpose purpose, int epoch) {
        consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        return retentionHoldRepository.findByEpoch(subjectKey, purpose, epoch).stream()
                .filter(h -> h.status() == HoldStatus.RELEASED)
                .map(h -> new ReleaseHistoryEntry(h.holdKey(), h.legalReason(), h.createdBy(),
                        h.releasedBy(), h.releasedAt(), h.releaseNote()))
                .toList();
    }

    /**
     * 同一 holdKey 重复创建：参数完全一致返回原冻结，否则 409。
     */
    private HoldResponse dedupeSameHold(HoldRow existing, CreateHoldRequest request) {
        boolean sameParams = existing.subjectKey().equals(request.subjectKey())
                && existing.purpose() == request.purpose()
                && existing.epoch() == request.epoch()
                && existing.legalReason().equals(request.legalReason())
                && existing.createdBy().equals(request.createdBy())
                && existing.expiresAt().equals(request.expiresAt());
        if (!sameParams) {
            throw ApiException.conflict(CODE_HOLD_KEY_CONFLICT, "同一 holdKey 已存在不同参数的冻结");
        }
        return new HoldResponse(existing.holdKey(), existing.subjectKey(), existing.purpose(),
                existing.epoch(), existing.legalReason(), existing.status(),
                existing.createdBy(), existing.expiresAt());
    }

    private boolean isEffective(HoldRow hold, Instant now) {
        return hold.status() == HoldStatus.ACTIVE && hold.expiresAt().isAfter(now);
    }

    private String deriveStatus(HoldRow hold, Instant now) {
        if (hold.status() == HoldStatus.RELEASED) {
            return "RELEASED";
        }
        return hold.expiresAt().isAfter(now) ? "ACTIVE" : "EXPIRED";
    }

    private void requireRetentionRole(String retentionRole) {
        if (!RETENTION_ROLE.equals(retentionRole)) {
            throw ApiException.forbidden(CODE_RETENTION_ROLE_REQUIRED, "需要保留权限角色");
        }
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
