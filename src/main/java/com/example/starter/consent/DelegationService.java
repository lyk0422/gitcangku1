package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.DelegationRepository.DelegationRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ChainEdgeResponse;
import com.example.starter.consent.dto.ChainResponse;
import com.example.starter.consent.dto.DelegationRequest;
import com.example.starter.consent.dto.DelegationResponse;
import com.example.starter.consent.dto.DelegationRevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 限时委托域服务：委托建立、撤销与当前有效链只读查询。
 *
 * <p>委托与授权写入共用同一加锁顺序（先锁授权代次行、再锁委托边），保证授权撤回、
 * 委托撤销/续建与写入按提交顺序串行化，过期快照无法穿透。委托到期不得晚于委托方
 * （上级授权或上级委托）的到期时刻；禁止环、重复有效边、超过 5 层及跨
 * subject/purpose/epoch 的委托。
 */
@Service
public class DelegationService {

    static final String CODE_DELEGATION_NOT_FOUND = "DELEGATION_NOT_FOUND";
    static final String CODE_DELEGATION_ALREADY_REVOKED = "DELEGATION_ALREADY_REVOKED";
    static final String CODE_DELEGATION_KEY_CONFLICT = "DELEGATION_KEY_CONFLICT";
    static final String CODE_DELEGATION_DUPLICATE = "DELEGATION_DUPLICATE_EDGE";
    static final String CODE_DELEGATION_FORBIDDEN = "DELEGATION_FORBIDDEN";
    static final String CODE_DELEGATION_EXPIRES_INVALID = "DELEGATION_EXPIRES_INVALID";
    static final String CODE_CHAIN_NOT_FOUND = "DELEGATION_CHAIN_NOT_FOUND";

    private static final String OP_DELEGATE = "DELEGATE";
    private static final String OP_DELEGATE_REVOKE = "DELEGATE_REVOKE";

    private final ConsentRepository consentRepository;
    private final DelegationRepository delegationRepository;
    private final DelegationGraph delegationGraph;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;

    public DelegationService(ConsentRepository consentRepository,
                             DelegationRepository delegationRepository,
                             DelegationGraph delegationGraph,
                             IdempotencyRepository idempotencyRepository,
                             ObjectMapper objectMapper,
                             TimeSource timeSource) {
        this.consentRepository = consentRepository;
        this.delegationRepository = delegationRepository;
        this.delegationGraph = delegationGraph;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.timeSource = timeSource;
    }

    /**
     * 建立委托边：校验授权代次有效、委托方在链内且上级覆盖到期时刻、无环、无重复有效边、
     * 深度不超过 5 层后，按同一有向边的下一版本写入。
     */
    @Transactional
    public DelegationResponse delegate(DelegationRequest request) {
        String fingerprint = OP_DELEGATE + "|" + request.delegationKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.epoch() + "|" + request.delegatorKey()
                + "|" + request.processorKey() + "|" + request.expiresAt();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), DelegationResponse.class);
        }

        Instant now = timeSource.now();
        ConsentRepository.GrantRow grant = consentRepository
                .findGrantForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回，不能新增委托");
        }
        if (grant.expiresAt().isBefore(now)) {
            throw ApiException.forbidden(ConsentService.CODE_GRANT_EXPIRED, "授权已到期，不能新增委托");
        }
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest(CODE_DELEGATION_EXPIRES_INVALID, "委托到期时刻必须晚于当前时刻");
        }

        // 授权行锁之后按主键顺序锁定该代次全部 ACTIVE 边：与写入的加锁顺序一致
        List<DelegationRow> activeEdges = delegationRepository.findActiveEdgesForUpdate(
                request.subjectKey(), request.purpose(), request.epoch());

        // 委托方上级到期时刻：主体委托时为授权到期时刻；处理方委托时为其入边到期时刻
        Instant parentExpiresAt = grant.expiresAt();
        if (!request.delegatorKey().equals(request.subjectKey())) {
            List<DelegationRow> pathToDelegator = delegationGraph.shortestPath(
                    activeEdges, request.subjectKey(), request.delegatorKey(), now);
            if (pathToDelegator == null || pathToDelegator.isEmpty()) {
                throw ApiException.forbidden(CODE_DELEGATION_FORBIDDEN,
                        "委托方不在主体当前有效委托链内，禁止跨 subject/purpose/epoch 委托");
            }
            parentExpiresAt = pathToDelegator.get(pathToDelegator.size() - 1).expiresAt();
        }
        if (request.expiresAt().isAfter(parentExpiresAt)) {
            throw ApiException.badRequest(CODE_DELEGATION_EXPIRES_INVALID,
                    "委托到期时刻不得晚于上级授权/委托到期时刻");
        }

        // 重复有效边：锁定快照中同一有向边仍 ACTIVE 且未到期时禁止再建（续建请先撤销或待到期）
        boolean duplicateActive = activeEdges.stream().anyMatch(edge ->
                edge.delegatorKey().equals(request.delegatorKey())
                        && edge.processorKey().equals(request.processorKey())
                        && !edge.expiresAt().isBefore(now));
        if (duplicateActive) {
            throw ApiException.conflict(CODE_DELEGATION_DUPLICATE,
                    "委托方到处理方的有效边已存在: " + request.delegatorKey() + "->" + request.processorKey());
        }

        int version = delegationRepository.nextVersion(request.subjectKey(), request.purpose(),
                request.epoch(), request.delegatorKey(), request.processorKey());
        DelegationRow newEdge = new DelegationRow(request.delegationKey(), request.subjectKey(),
                request.purpose(), request.epoch(), request.delegatorKey(), request.processorKey(),
                version, DelegationStatus.ACTIVE, request.expiresAt(), null);
        delegationGraph.validateNewEdge(activeEdges, request.subjectKey(), newEdge)
                .ifPresent(reason -> {
                    throw ApiException.forbidden(CODE_DELEGATION_FORBIDDEN, reason);
                });

        try {
            delegationRepository.insert(newEdge, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // delegationKey 冲突或并发创建同一条边的新版本：均按提交顺序以先提交者为准
            throw ApiException.conflict(CODE_DELEGATION_DUPLICATE,
                    "委托边并发冲突或 delegationKey 已被占用: " + request.delegationKey());
        }

        DelegationResponse response = new DelegationResponse(request.delegationKey(), request.subjectKey(),
                request.purpose(), request.epoch(), request.delegatorKey(), request.processorKey(),
                version, DelegationStatus.ACTIVE, request.expiresAt());
        storeSuccess(request.requestId(), OP_DELEGATE, fingerprint, response);
        return response;
    }

    /**
     * 撤销委托边：只影响后续写入（下游处理方即刻不再处于有效链内），历史记录保留不变。
     */
    @Transactional
    public DelegationResponse revoke(DelegationRevokeRequest request) {
        String fingerprint = OP_DELEGATE_REVOKE + "|" + request.delegationKey();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), DelegationResponse.class);
        }

        DelegationRow row = delegationRepository.findByKey(request.delegationKey())
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATION_NOT_FOUND, "委托边不存在"));
        // 与写入相同的加锁顺序：先授权代次行、再委托边
        consentRepository.findGrantForUpdate(row.subjectKey(), row.purpose(), row.epoch())
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        row = delegationRepository.findByKeyForUpdate(request.delegationKey())
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATION_NOT_FOUND, "委托边不存在"));
        if (!delegationRepository.revokeByKey(request.delegationKey())) {
            throw ApiException.conflict(CODE_DELEGATION_ALREADY_REVOKED, "委托边已撤销");
        }
        DelegationResponse response = new DelegationResponse(row.delegationKey(), row.subjectKey(),
                row.purpose(), row.epoch(), row.delegatorKey(), row.processorKey(), row.version(),
                DelegationStatus.REVOKED, row.expiresAt());
        storeSuccess(request.requestId(), OP_DELEGATE_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 查询主体到目标处理方的当前最短有效委托链（只读）。目标为主体自身时返回空链。
     */
    @Transactional(readOnly = true)
    public ChainResponse getChain(String subjectKey, Purpose purpose, int epoch, String processorKey) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回");
        }
        Instant now = timeSource.now();
        if (grant.expiresAt().isBefore(now)) {
            throw ApiException.forbidden(ConsentService.CODE_GRANT_EXPIRED, "授权已到期");
        }
        List<ChainEdgeResponse> edges;
        if (processorKey.equals(subjectKey)) {
            edges = List.of();
        } else {
            List<DelegationRow> activeEdges = delegationRepository.findActiveEdges(subjectKey, purpose, epoch);
            List<DelegationRow> path = delegationGraph.shortestPath(activeEdges, subjectKey, processorKey, now);
            if (path == null) {
                throw ApiException.notFound(CODE_CHAIN_NOT_FOUND,
                        "主体到处理方当前不存在有效委托链: " + processorKey);
            }
            edges = path.stream()
                    .map(edge -> new ChainEdgeResponse(edge.delegatorKey(), edge.processorKey(),
                            edge.version(), edge.expiresAt()))
                    .toList();
        }
        return new ChainResponse(subjectKey, purpose, epoch, processorKey, edges);
    }

    private Optional<IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRow> row = idempotencyRepository.find(requestId);
        if (row.isPresent() && !row.get().paramsFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
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
                throw ApiException.conflict(ConsentService.CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
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
