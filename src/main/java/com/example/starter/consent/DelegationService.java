package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.ChainEdgeResponse;
import com.example.starter.consent.dto.ChainResponse;
import com.example.starter.consent.dto.DelegateRequest;
import com.example.starter.consent.dto.DelegationResponse;
import com.example.starter.consent.dto.DelegationRevokeRequest;

/**
 * 限时委托域服务：委托边的创建、撤销与当前有效链只读查询。
 *
 * <p>规则：委托只能在同一 subject/purpose/epoch 内进行；首跳由主体发起，之后每跳起点必须
 * 当前仍在主体到该处理方的最短有效链上；禁止环、重复有效边与超过 5 层的链；新边到期时刻
 * 不得晚于上级边及主体授权。所有变更与写入按“先锁授权行、再锁委托边”的统一顺序串行化。
 */
@Service
public class DelegationService {

    static final String OP_DELEGATE = "DELEGATE";
    static final String OP_DELEGATE_REVOKE = "DELEGATE_REVOKE";

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_CONSENT_EXPIRED = "CONSENT_EXPIRED";
    static final String CODE_GRANT_EXPIRES_INVALID = "GRANT_EXPIRES_INVALID";
    static final String CODE_DELEGATION_NOT_FOUND = "DELEGATION_NOT_FOUND";
    static final String CODE_DELEGATION_KEY_CONFLICT = "DELEGATION_KEY_CONFLICT";
    static final String CODE_DELEGATION_EDGE_EXISTS = "DELEGATION_EDGE_EXISTS";
    static final String CODE_DELEGATION_NOT_AUTHORIZED = "DELEGATION_NOT_AUTHORIZED";
    static final String CODE_DELEGATION_CYCLE = "DELEGATION_CYCLE";
    static final String CODE_DELEGATION_DEPTH_EXCEEDED = "DELEGATION_DEPTH_EXCEEDED";
    static final String CODE_DELEGATION_EXPIRES_AFTER_PARENT = "DELEGATION_EXPIRES_AFTER_PARENT";
    static final String CODE_DELEGATION_ALREADY_REVOKED = "DELEGATION_ALREADY_REVOKED";
    static final String CODE_DELEGATION_CHAIN_NOT_FOUND = "DELEGATION_CHAIN_NOT_FOUND";

    private final ConsentRepository consentRepository;
    private final DelegationRepository delegationRepository;
    private final DelegationGraph delegationGraph;
    private final IdempotencySupport idempotencySupport;
    private final TimeSource timeSource;

    public DelegationService(ConsentRepository consentRepository,
                             DelegationRepository delegationRepository,
                             DelegationGraph delegationGraph,
                             IdempotencySupport idempotencySupport,
                             TimeSource timeSource) {
        this.consentRepository = consentRepository;
        this.delegationRepository = delegationRepository;
        this.delegationGraph = delegationGraph;
        this.idempotencySupport = idempotencySupport;
        this.timeSource = timeSource;
    }

    /**
     * 创建委托边：主体或已获委托的处理方在当前有效代次内向处理方委托，返回带版本的委托边。
     */
    @Transactional
    public DelegationResponse delegate(DelegateRequest request) {
        String fingerprint = OP_DELEGATE + "|" + request.delegationKey() + "|" + request.subjectKey()
                + "|" + request.purpose() + "|" + request.fromKey() + "|" + request.toKey()
                + "|" + request.expiresAt();
        Optional<IdempotencyRow> replayed = idempotencySupport.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencySupport.readSnapshot(replayed.get().responseBody(), DelegationResponse.class);
        }

        Instant now = timeSource.now();
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest(CODE_GRANT_EXPIRES_INVALID, "委托到期时刻必须晚于当前时刻");
        }

        // 统一加锁顺序：先锁授权代次行，再锁当代有效边
        ConsentRepository.GrantRow grant = lockActiveGrant(request.subjectKey(), request.purpose(), now);
        List<DelegationRepository.DelegationRow> edges =
                delegationRepository.findActiveEdgesForUpdate(request.subjectKey(), request.purpose(), grant.epoch());

        if (delegationRepository.findByKey(request.delegationKey()).isPresent()) {
            throw ApiException.conflict(CODE_DELEGATION_KEY_CONFLICT, "delegationKey 已存在");
        }

        // 起点授权与上级到期约束：首跳起点为主体，其后起点必须在当前最短有效链上
        Instant parentExpiry;
        int parentDepth;
        if (request.fromKey().equals(request.subjectKey())) {
            parentExpiry = grant.expiresAt();
            parentDepth = 0;
        } else {
            List<DelegationRepository.DelegationRow> parentPath = delegationGraph.shortestPath(
                    edges, request.subjectKey(), request.fromKey(), now);
            if (parentPath == null || parentPath.isEmpty()) {
                throw ApiException.forbidden(CODE_DELEGATION_NOT_AUTHORIZED,
                        "委托方不在主体的有效委托链上");
            }
            parentExpiry = parentPath.get(parentPath.size() - 1).expiresAt();
            parentDepth = parentPath.size();
        }

        if (delegationGraph.wouldCreateCycle(edges, request.subjectKey(), request.fromKey(), request.toKey(), now)) {
            throw ApiException.forbidden(CODE_DELEGATION_CYCLE, "委托形成环或指向主体/链上已有节点");
        }
        if (parentDepth + 1 > DelegationGraph.MAX_DEPTH) {
            throw ApiException.forbidden(CODE_DELEGATION_DEPTH_EXCEEDED,
                    "委托链最长 " + DelegationGraph.MAX_DEPTH + " 层");
        }
        boolean duplicateActive = edges.stream().anyMatch(edge ->
                edge.fromKey().equals(request.fromKey()) && edge.toKey().equals(request.toKey())
                        && edge.expiresAt().isAfter(now));
        if (duplicateActive) {
            throw ApiException.conflict(CODE_DELEGATION_EDGE_EXISTS, "同一对起止点已存在有效委托边");
        }
        if (request.expiresAt().isAfter(parentExpiry)) {
            throw ApiException.forbidden(CODE_DELEGATION_EXPIRES_AFTER_PARENT,
                    "委托到期时刻不得晚于上级委托或主体授权");
        }

        int nextVersion = delegationRepository.findLatestByEndpoints(
                request.subjectKey(), request.purpose(), grant.epoch(), request.fromKey(), request.toKey())
                .map(row -> row.version() + 1)
                .orElse(1);
        DelegationRepository.DelegationRow row = new DelegationRepository.DelegationRow(
                request.delegationKey(), request.subjectKey(), request.purpose(), grant.epoch(),
                request.fromKey(), request.toKey(), nextVersion, DelegationStatus.ACTIVE,
                request.expiresAt(), request.requestId());
        try {
            delegationRepository.insert(row);
        } catch (DuplicateKeyException concurrent) {
            // 并发续建/同键创建：主键冲突或同版本唯一约束冲突均按业务冲突返回，失败不占用 requestId
            if (delegationRepository.findByKey(request.delegationKey()).isPresent()) {
                throw ApiException.conflict(CODE_DELEGATION_KEY_CONFLICT, "delegationKey 已存在");
            }
            throw ApiException.conflict(CODE_DELEGATION_EDGE_EXISTS, "同一对起止点已存在有效委托边");
        }

        DelegationResponse response = toResponse(row);
        idempotencySupport.storeSuccess(request.requestId(), OP_DELEGATE, fingerprint, response);
        return response;
    }

    /**
     * 撤销单条委托边：只影响撤销提交后的写入，历史记录中的写入依据快照保持不变。
     */
    @Transactional
    public DelegationResponse revoke(DelegationRevokeRequest request) {
        String fingerprint = OP_DELEGATE_REVOKE + "|" + request.delegationKey();
        Optional<IdempotencyRow> replayed = idempotencySupport.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencySupport.readSnapshot(replayed.get().responseBody(), DelegationResponse.class);
        }

        DelegationRepository.DelegationRow edge = delegationRepository.findByKey(request.delegationKey())
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATION_NOT_FOUND, "委托边不存在"));
        // 先锁授权代次行，再锁边，与委托/写入保持一致加锁顺序
        consentRepository.findGrantForUpdate(edge.subjectKey(), edge.purpose(), edge.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        DelegationRepository.DelegationRow locked = delegationRepository.findByKeyForUpdate(request.delegationKey())
                .orElseThrow(() -> ApiException.notFound(CODE_DELEGATION_NOT_FOUND, "委托边不存在"));
        if (locked.status() == DelegationStatus.REVOKED) {
            throw ApiException.conflict(CODE_DELEGATION_ALREADY_REVOKED, "委托边已撤销");
        }
        delegationRepository.revokeByKey(request.delegationKey());

        DelegationResponse response = new DelegationResponse(
                locked.delegationKey(), locked.subjectKey(), locked.purpose().name(), locked.epoch(),
                locked.fromKey(), locked.toKey(), locked.version(), DelegationStatus.REVOKED.name(),
                locked.expiresAt());
        idempotencySupport.storeSuccess(request.requestId(), OP_DELEGATE_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 当前有效链只读查询：返回主体到目标处理方的最短有效路径；目标为主体时返回空链。
     *
     * @param epoch 指定代次；为 null 时查询最新代次
     */
    @Transactional(readOnly = true)
    public ChainResponse currentChain(String subjectKey, Purpose purpose, Integer epoch, String callerKey) {
        Instant now = timeSource.now();
        ConsentRepository.GrantRow grant;
        if (epoch == null) {
            grant = consentRepository.findLatestGrant(subjectKey, purpose)
                    .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        } else {
            grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                    .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        }
        requireGrantEffective(grant, now);

        List<DelegationRepository.DelegationRow> path;
        if (callerKey.equals(subjectKey)) {
            path = List.of();
        } else {
            List<DelegationRepository.DelegationRow> edges =
                    delegationRepository.findActiveEdges(subjectKey, purpose, grant.epoch());
            path = delegationGraph.shortestPath(edges, subjectKey, callerKey, now);
            if (path == null) {
                throw ApiException.notFound(CODE_DELEGATION_CHAIN_NOT_FOUND, "主体到调用方不存在有效委托链");
            }
        }
        List<ChainEdgeResponse> chainEdges = path.stream()
                .map(edge -> new ChainEdgeResponse(edge.delegationKey(), edge.fromKey(), edge.toKey(),
                        edge.version(), edge.expiresAt()))
                .toList();
        return new ChainResponse(subjectKey, purpose.name(), grant.epoch(), callerKey, chainEdges, now);
    }

    /**
     * 锁定指定授权域的最新代次并要求当前有效且未到期，供委托与写入复用。
     */
    ConsentRepository.GrantRow lockActiveGrant(String subjectKey, Purpose purpose, Instant now) {
        ConsentRepository.GrantRow grant = consentRepository.findLatestGrantForUpdate(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantEffective(grant, now);
        return grant;
    }

    private void requireGrantEffective(ConsentRepository.GrantRow grant, Instant now) {
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        if (!grant.expiresAt().isAfter(now)) {
            throw ApiException.forbidden(CODE_CONSENT_EXPIRED, "授权已到期");
        }
    }

    private DelegationResponse toResponse(DelegationRepository.DelegationRow row) {
        return new DelegationResponse(row.delegationKey(), row.subjectKey(), row.purpose().name(), row.epoch(),
                row.fromKey(), row.toKey(), row.version(), row.status().name(), row.expiresAt());
    }
}
