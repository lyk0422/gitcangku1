package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.EpochHoldStatusResponse;
import com.example.starter.consent.dto.HoldCreateRequest;
import com.example.starter.consent.dto.HoldReleaseHistoryItem;
import com.example.starter.consent.dto.HoldReleaseRequest;
import com.example.starter.consent.dto.HoldResponse;
import com.example.starter.consent.dto.LegalHoldRecordResponse;
import com.example.starter.consent.dto.PurgeRequest;
import com.example.starter.consent.dto.PurgeResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 保留冻结域服务：冻结创建、人工解除、保留角色只读查询、撤回后清除与冻结状态查询。
 *
 * <p>冻结不恢复已撤回授权，也不开放普通业务读写；仅保留角色按记录标识只读访问，
 * 响应标记 LEGAL_HOLD 且不返回业务可用的授权状态。冻结与撤回、清除等操作在同一
 * 事务内先锁定 epoch 授权行（SELECT ... FOR UPDATE），再按事务提交顺序裁决：
 * 冻结先提交则清除保留数据；清除先提交则冻结返回 404，不恢复已清除数据。
 */
@Service
public class RetentionHoldService {

    static final String CODE_FORBIDDEN = "FORBIDDEN";
    static final String CODE_HOLD_NOT_FOUND = "HOLD_NOT_FOUND";
    static final String CODE_EPOCH_NOT_FOUND = "EPOCH_NOT_FOUND";
    static final String CODE_EPOCH_PURGED = "EPOCH_PURGED";
    static final String CODE_HOLD_ALREADY_ACTIVE = "HOLD_ALREADY_ACTIVE";
    static final String CODE_HOLD_KEY_CONFLICT = "HOLD_KEY_CONFLICT";
    static final String CODE_HOLD_EXPIRES_IN_PAST = "HOLD_EXPIRES_IN_PAST";
    static final String CODE_HOLD_ALREADY_RELEASED = "HOLD_ALREADY_RELEASED";
    static final String CODE_HOLD_EXPIRED = "HOLD_EXPIRED";
    static final String CODE_HOLD_RELEASE_SAME_ACTOR = "HOLD_RELEASE_SAME_ACTOR";
    static final String CODE_RECORD_NOT_FOUND = "LEGAL_HOLD_RECORD_NOT_FOUND";
    static final String CODE_GRANT_ACTIVE = "GRANT_ACTIVE";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    private static final String OP_HOLD_CREATE = "HOLD_CREATE";
    private static final String OP_HOLD_RELEASE = "HOLD_RELEASE";
    private static final String OP_PURGE = "PURGE";

    private final HoldRepository holdRepository;
    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final BusinessClock clock;
    private final ObjectMapper objectMapper;

    public RetentionHoldService(HoldRepository holdRepository,
                                ConsentRepository consentRepository,
                                IdempotencyRepository idempotencyRepository,
                                BusinessClock clock,
                                ObjectMapper objectMapper) {
        this.holdRepository = holdRepository;
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建保留冻结：对有效或已撤回 epoch 生效；已清除 epoch 返回 404；
     * 同一 epoch 已存在生效冻结返回 409；到期时刻必须晚于当前 UTC 时刻。
     */
    @Transactional
    public HoldResponse createHold(HoldCreateRequest request, Actor actor) {
        requireRetentionOfficer(actor);
        String fingerprint = OP_HOLD_CREATE + "|" + request.holdKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.epoch() + "|" + request.reason()
                + "|" + request.expiresAt();
        var replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), HoldResponse.class);
        }

        Instant now = clock.now();
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest(CODE_HOLD_EXPIRES_IN_PAST, "冻结到期时刻必须晚于当前时刻");
        }

        // 锁定 epoch 授权行：与撤回、清除及并发冻结创建按提交顺序串行裁决
        ConsentRepository.GrantRow grant = consentRepository
                .lockGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_EPOCH_NOT_FOUND, "授权代次不存在"));
        if (grant.purgedAt() != null) {
            // 清除先提交：数据已物理删除，冻结不得恢复
            throw ApiException.notFound(CODE_EPOCH_PURGED, "授权代次数据已清除，无法创建冻结");
        }
        // 同一 epoch 同一事由只能有一个生效冻结；不同事由可并存
        List<HoldRepository.HoldRow> effective = holdRepository.findEffectiveByEpoch(
                request.subjectKey(), request.purpose(), request.epoch(), now);
        if (effective.stream().anyMatch(hold -> hold.reason().equals(request.reason()))) {
            throw ApiException.conflict(CODE_HOLD_ALREADY_ACTIVE, "该代次同一事由已存在生效冻结");
        }

        try {
            holdRepository.insertHold(request.holdKey(), request.subjectKey(), request.purpose(),
                    request.epoch(), request.reason(), actor.id(), request.expiresAt());
        } catch (DuplicateKeyException concurrent) {
            // 并发使用同一 holdKey 或同 epoch 同键：以已提交冻结为准并拒绝重复创建
            throw ApiException.conflict(CODE_HOLD_KEY_CONFLICT, "冻结键已存在或同代次冻结键冲突");
        }

        HoldResponse response = new HoldResponse(request.holdKey(), request.subjectKey(), request.purpose(),
                request.epoch(), request.reason(), actor.id(), request.expiresAt(),
                HoldStatus.ACTIVE, true, now);
        storeSuccess(request.requestId(), OP_HOLD_CREATE, fingerprint, response);
        return response;
    }

    /**
     * 人工解除：解除人必须是不同于创建人的保留角色；已到期或已解除再次解除返回 409；
     * 解除记录写入后不可变。
     */
    @Transactional
    public HoldReleaseHistoryItem releaseHold(String holdKey, HoldReleaseRequest request, Actor actor) {
        requireRetentionOfficer(actor);
        String fingerprint = OP_HOLD_RELEASE + "|" + holdKey + "|" + request.note();
        var replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), HoldReleaseHistoryItem.class);
        }

        HoldRepository.HoldRow hold = holdRepository.findByKey(holdKey)
                .orElseThrow(() -> ApiException.notFound(CODE_HOLD_NOT_FOUND, "保留冻结不存在"));
        if (hold.status() == HoldStatus.RELEASED) {
            throw ApiException.conflict(CODE_HOLD_ALREADY_RELEASED, "冻结已解除，解除记录不可变");
        }
        Instant now = clock.now();
        if (!hold.expiresAt().isAfter(now)) {
            throw ApiException.conflict(CODE_HOLD_EXPIRED, "冻结已到期，不能人工解除");
        }
        if (hold.createdBy().equals(actor.id())) {
            throw ApiException.conflict(CODE_HOLD_RELEASE_SAME_ACTOR, "解除人必须不同于创建人");
        }

        boolean released = holdRepository.markReleased(hold.id(), now);
        if (!released) {
            // 并发解除：以已提交的不可变解除记录为准，再次解除为 409
            throw ApiException.conflict(CODE_HOLD_ALREADY_RELEASED, "冻结已解除，解除记录不可变");
        }
        holdRepository.insertRelease(hold.id(), hold.holdKey(), hold.subjectKey(), hold.purpose(),
                hold.epoch(), actor.id(), request.note(), now);

        HoldReleaseHistoryItem response = new HoldReleaseHistoryItem(
                hold.holdKey(), actor.id(), request.note(), now);
        storeSuccess(request.requestId(), OP_HOLD_RELEASE, fingerprint, response);
        return response;
    }

    /**
     * 保留权限只读查询：仅保留角色可按记录标识查询被生效冻结保留的数据；
     * 响应标记 LEGAL_HOLD 且不返回业务可用授权状态；冻结到期/解除/清除后返回 410。
     */
    @Transactional(readOnly = true)
    public LegalHoldRecordResponse legalHoldRead(String subjectKey, Purpose purpose, int epoch,
                                                 String recordKey, Actor actor) {
        requireRetentionOfficer(actor);
        Instant now = clock.now();

        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_EPOCH_NOT_FOUND, "授权代次不存在"));
        if (grant.purgedAt() != null) {
            throw ApiException.gone(CODE_EPOCH_PURGED, "授权代次数据已物理清除");
        }

        List<HoldRepository.HoldRow> holds = holdRepository.findByEpoch(subjectKey, purpose, epoch);
        boolean anyHoldEver = !holds.isEmpty();
        boolean effective = holds.stream().anyMatch(hold -> hold.effectiveAt(now));
        if (!effective) {
            if (!anyHoldEver) {
                throw ApiException.notFound(CODE_HOLD_NOT_FOUND, "该代次不存在保留冻结");
            }
            boolean released = holds.stream().anyMatch(hold -> hold.status() == HoldStatus.RELEASED);
            if (released) {
                throw ApiException.gone(CODE_HOLD_ALREADY_RELEASED, "冻结已人工解除，保留读取不再可用");
            }
            throw ApiException.gone(CODE_HOLD_EXPIRED, "冻结已到期，保留读取不再可用");
        }

        return consentRepository.findRecord(subjectKey, purpose, epoch, recordKey)
                .map(row -> LegalHoldRecordResponse.of(
                        row.subjectKey(), row.purpose(), row.epoch(), row.recordKey(), row.payload()))
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "保留记录不存在"));
    }

    /**
     * 撤回后物理清除：仅已撤回 epoch 可清除；存在生效冻结时跳过并报告保留记录数，
     * 到期或解除后的下一次清除可物理删除该 epoch 数据。清除按 requestId 幂等。
     */
    @Transactional
    public PurgeResponse purge(PurgeRequest request) {
        String fingerprint = OP_PURGE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch();
        var replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), PurgeResponse.class);
        }

        Instant now = clock.now();
        ConsentRepository.GrantRow grant = consentRepository
                .lockGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_EPOCH_NOT_FOUND, "授权代次不存在"));
        if (grant.purgedAt() != null) {
            throw ApiException.notFound(CODE_EPOCH_PURGED, "授权代次数据已清除");
        }
        if (grant.status() == GrantStatus.ACTIVE) {
            throw ApiException.conflict(CODE_GRANT_ACTIVE, "授权仍有效，不能清除");
        }

        int retained = consentRepository.countRecords(request.subjectKey(), request.purpose(), request.epoch());
        boolean held = !holdRepository.findEffectiveByEpoch(
                request.subjectKey(), request.purpose(), request.epoch(), now).isEmpty();
        int purged;
        int remaining;
        if (held) {
            // 冻结先提交：撤回后的清除保留全部数据
            purged = 0;
            remaining = retained;
        } else {
            purged = consentRepository.deleteRecordsForEpoch(
                    request.subjectKey(), request.purpose(), request.epoch());
            retained = 0;
            remaining = 0;
            consentRepository.markPurged(request.subjectKey(), request.purpose(), request.epoch(), now);
        }

        PurgeResponse response = new PurgeResponse(request.subjectKey(), request.purpose(),
                request.epoch(), purged, retained, remaining);
        storeSuccess(request.requestId(), OP_PURGE, fingerprint, response);
        return response;
    }

    /**
     * 查询 epoch 冻结状态：授权状态、全部冻结及生效判定、保留记录计数与不可变解除历史。
     */
    @Transactional(readOnly = true)
    public EpochHoldStatusResponse epochStatus(String subjectKey, Purpose purpose, int epoch) {
        Instant now = clock.now();
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_EPOCH_NOT_FOUND, "授权代次不存在"));

        List<HoldRepository.HoldRow> holds = holdRepository.findByEpoch(subjectKey, purpose, epoch);
        List<HoldResponse> holdResponses = holds.stream()
                .map(hold -> new HoldResponse(hold.holdKey(), hold.subjectKey(), hold.purpose(),
                        hold.epoch(), hold.reason(), hold.createdBy(), hold.expiresAt(),
                        hold.status(), hold.effectiveAt(now), hold.createdAt()))
                .toList();
        boolean effective = holds.stream().anyMatch(hold -> hold.effectiveAt(now));
        int retainedCount = effective
                ? consentRepository.countRecords(subjectKey, purpose, epoch)
                : 0;
        List<HoldReleaseHistoryItem> history = holdRepository
                .findReleasesByEpoch(subjectKey, purpose, epoch).stream()
                .map(row -> new HoldReleaseHistoryItem(row.holdKey(), row.releasedBy(), row.note(), row.releasedAt()))
                .toList();

        return new EpochHoldStatusResponse(subjectKey, purpose, epoch, grant.status(),
                holdResponses, retainedCount, history);
    }

    private void requireRetentionOfficer(Actor actor) {
        if (actor == null || actor.id() == null || actor.id().isBlank() || !actor.retentionRole()) {
            throw new ApiException(HttpStatus.FORBIDDEN, CODE_FORBIDDEN, "需要保留权限角色");
        }
    }

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
