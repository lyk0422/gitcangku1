package com.example.starter.consent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.EdgeBasis;
import com.example.starter.consent.dto.EdgeVersionInput;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现限时授权、委托链写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>写入在同一事务快照内先锁授权代次行、再锁当代全部有效委托边，校验：授权 ACTIVE 且未到期、
 * 提交路径自主体连续到调用方、每边未撤销未过期、版本相符且为当前最短有效路径；任一不满足均
 * 拒绝写入（403/409）。记录固化授权代次、完整委托版本与评估时刻，此后不可变。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。重放不得绕过当前授权与委托状态。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_CONSENT_EXPIRED = "CONSENT_EXPIRED";
    static final String CODE_GRANT_EXPIRES_INVALID = "GRANT_EXPIRES_INVALID";
    static final String CODE_DELEGATION_PATH_INVALID = "DELEGATION_PATH_INVALID";
    static final String CODE_DELEGATION_EDGE_MISSING = "DELEGATION_EDGE_MISSING";
    static final String CODE_DELEGATION_EDGE_SCOPE_MISMATCH = "DELEGATION_EDGE_SCOPE_MISMATCH";
    static final String CODE_DELEGATION_EDGE_EXPIRED = "DELEGATION_EDGE_EXPIRED";
    static final String CODE_EDGE_VERSION_CONFLICT = "EDGE_VERSION_CONFLICT";
    static final String CODE_DELEGATION_PATH_NOT_SHORTEST = "DELEGATION_PATH_NOT_SHORTEST";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";

    private final ConsentRepository consentRepository;
    private final DelegationRepository delegationRepository;
    private final DelegationGraph delegationGraph;
    private final IdempotencySupport idempotencySupport;
    private final TimeSource timeSource;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentRepository consentRepository,
                          DelegationRepository delegationRepository,
                          DelegationGraph delegationGraph,
                          IdempotencySupport idempotencySupport,
                          TimeSource timeSource,
                          ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.delegationRepository = delegationRepository;
        this.delegationGraph = delegationGraph;
        this.idempotencySupport = idempotencySupport;
        this.timeSource = timeSource;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权：当前授权有效（ACTIVE 且未到期）时返回原代次；撤回、到期或首次授权生成下一代。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.expiresAt();
        Optional<IdempotencyRow> replayed = idempotencySupport.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencySupport.readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        Instant now = timeSource.now();
        if (!request.expiresAt().isAfter(now)) {
            throw ApiException.badRequest(CODE_GRANT_EXPIRES_INVALID, "授权到期时刻必须晚于当前时刻");
        }

        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        boolean effective = latest.isPresent()
                && latest.get().status() == GrantStatus.ACTIVE
                && latest.get().expiresAt().isAfter(now);
        GrantResponse response;
        if (effective) {
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    latest.get().epoch(), GrantStatus.ACTIVE, latest.get().expiresAt());
        } else {
            int nextEpoch = latest.map(row -> row.epoch() + 1).orElse(1);
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch,
                        request.expiresAt(), request.requestId());
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                ConsentRepository.GrantRow committed =
                        consentRepository.findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                                .orElseThrow(() -> concurrent);
                response = new GrantResponse(committed.subjectKey(), committed.purpose(),
                        committed.epoch(), committed.status(), committed.expiresAt());
                idempotencySupport.storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(), nextEpoch,
                    GrantStatus.ACTIVE, request.expiresAt());
        }
        idempotencySupport.storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    /**
     * 写入：主体直写或处理方沿当前最短有效委托链写入；固化不可变写入依据。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.callerKey() + "|" + request.recordKey() + "|" + request.payload()
                + "|" + pathFingerprint(request.delegationPath());
        Optional<IdempotencyRow> replayed = idempotencySupport.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot =
                    idempotencySupport.readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            // 重放不得绕过当前授权与委托状态
            revalidateBasis(snapshot);
            return snapshot;
        }

        Instant now = timeSource.now();
        ConsentRepository.GrantRow grant = lockActiveGrant(request.subjectKey(), request.purpose(), now);
        List<DelegationRepository.DelegationRow> edges =
                delegationRepository.findActiveEdgesForUpdate(request.subjectKey(), request.purpose(), grant.epoch());

        List<DelegationRepository.DelegationRow> validatedPath =
                validateDelegationPath(request, grant, edges, now);

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), grant.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get());
            idempotencySupport.storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        List<EdgeBasis> basis = validatedPath.stream()
                .map(edge -> new EdgeBasis(edge.delegationKey(), edge.fromKey(), edge.toKey(),
                        edge.version(), edge.expiresAt()))
                .toList();
        String basisJson = basis.isEmpty() ? null : writeBasis(basis);

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), grant.epoch(),
                    request.callerKey(), request.recordKey(), request.payload(), basisJson, now,
                    request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), grant.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(committed);
            idempotencySupport.storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose().name(),
                grant.epoch(), request.callerKey(), request.recordKey(), request.payload(), now, basis);
        idempotencySupport.storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代查询立即 410、写入被拒绝，
     * 旧代整条委托链随之失效；新代次读取不到旧代数据。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = idempotencySupport.checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return idempotencySupport.readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        ConsentRepository.GrantRow grant = consentRepository
                .findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED, grant.expiresAt());
        idempotencySupport.storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 查询：仅当前有效代次可查；撤回返回 410，到期返回 403，记录不存在返回 404。
     * 响应携带写入时固化的不可变委托版本与评估时刻。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        Instant now = timeSource.now();
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantEffective(latest, now);
        return consentRepository.findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
    }

    /**
     * 在锁定的授权与有效边快照上校验处理方提交的委托路径，返回校验通过的有序边。
     * 主体直写（callerKey == subjectKey）要求路径为空。
     */
    private List<DelegationRepository.DelegationRow> validateDelegationPath(
            RecordWriteRequest request, ConsentRepository.GrantRow grant,
            List<DelegationRepository.DelegationRow> edges, Instant now) {
        List<EdgeVersionInput> submitted = request.delegationPath() == null ? List.of() : request.delegationPath();

        if (request.callerKey().equals(request.subjectKey())) {
            if (!submitted.isEmpty()) {
                throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "主体直写不得提交委托路径");
            }
            return List.of();
        }
        if (submitted.isEmpty()) {
            throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "处理方写入必须提交委托路径");
        }
        if (submitted.size() > DelegationGraph.MAX_DEPTH) {
            throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID,
                    "委托链最长 " + DelegationGraph.MAX_DEPTH + " 层");
        }

        Map<String, DelegationRepository.DelegationRow> edgeByKey = new LinkedHashMap<>();
        for (DelegationRepository.DelegationRow edge : edges) {
            edgeByKey.put(edge.delegationKey(), edge);
        }

        List<DelegationRepository.DelegationRow> path = new ArrayList<>();
        String expectedFrom = request.subjectKey();
        for (EdgeVersionInput ref : submitted) {
            DelegationRepository.DelegationRow edge = edgeByKey.get(ref.delegationKey());
            if (edge == null) {
                // 锁定快照只含当代 ACTIVE 边：键不存在即缺失或已撤销
                throw ApiException.forbidden(CODE_DELEGATION_EDGE_MISSING,
                        "委托边不存在或已撤销: " + ref.delegationKey());
            }
            if (!edge.subjectKey().equals(request.subjectKey()) || edge.purpose() != request.purpose()
                    || edge.epoch() != grant.epoch()) {
                throw ApiException.forbidden(CODE_DELEGATION_EDGE_SCOPE_MISMATCH,
                        "委托边不属于当前 subject/purpose/epoch");
            }
            if (!edge.fromKey().equals(expectedFrom)) {
                throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "委托路径不从主体连续到调用方");
            }
            if (!edge.expiresAt().isAfter(now)) {
                throw ApiException.forbidden(CODE_DELEGATION_EDGE_EXPIRED,
                        "委托边已到期: " + ref.delegationKey());
            }
            if (edge.version() != ref.version()) {
                throw ApiException.conflict(CODE_EDGE_VERSION_CONFLICT,
                        "委托边版本不符: " + ref.delegationKey());
            }
            path.add(edge);
            expectedFrom = edge.toKey();
        }
        if (!expectedFrom.equals(request.callerKey())) {
            throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "委托路径末端与调用方不一致");
        }

        // 提交路径必须是当前最短有效路径：续建或新增更短边后，旧长路径不得再写入
        List<DelegationRepository.DelegationRow> shortest =
                delegationGraph.shortestPath(edges, request.subjectKey(), request.callerKey(), now);
        if (shortest == null || shortest.size() != path.size()) {
            throw ApiException.conflict(CODE_DELEGATION_PATH_NOT_SHORTEST, "提交路径不是当前最短有效路径");
        }
        for (int i = 0; i < shortest.size(); i++) {
            if (!shortest.get(i).delegationKey().equals(path.get(i).delegationKey())) {
                throw ApiException.conflict(CODE_DELEGATION_PATH_NOT_SHORTEST, "提交路径不是当前最短有效路径");
            }
        }
        return path;
    }

    /**
     * 重放快照时按当前状态重新校验：授权须仍有效；固化过的委托路径须仍连续、未撤销未过期。
     */
    private void revalidateBasis(RecordResponse snapshot) {
        Instant now = timeSource.now();
        ConsentRepository.GrantRow grant = consentRepository
                .findGrant(snapshot.subjectKey(), Purpose.valueOf(snapshot.purpose()), snapshot.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        requireGrantEffective(grant, now);

        List<EdgeBasis> basis = snapshot.delegationPath() == null ? List.of() : snapshot.delegationPath();
        if (basis.isEmpty()) {
            return;
        }
        List<DelegationRepository.DelegationRow> edges = delegationRepository.findActiveEdges(
                snapshot.subjectKey(), Purpose.valueOf(snapshot.purpose()), snapshot.epoch());
        Map<String, DelegationRepository.DelegationRow> edgeByKey = new LinkedHashMap<>();
        for (DelegationRepository.DelegationRow edge : edges) {
            edgeByKey.put(edge.delegationKey(), edge);
        }
        String expectedFrom = snapshot.subjectKey();
        for (EdgeBasis ref : basis) {
            DelegationRepository.DelegationRow edge = edgeByKey.get(ref.delegationKey());
            if (edge == null || edge.version() != ref.version()) {
                throw ApiException.forbidden(CODE_DELEGATION_EDGE_MISSING, "委托边不存在、已撤销或版本变化");
            }
            if (!edge.fromKey().equals(expectedFrom) || !edge.toKey().equals(ref.toKey())) {
                throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "委托路径不再连续");
            }
            if (!edge.expiresAt().isAfter(now)) {
                throw ApiException.forbidden(CODE_DELEGATION_EDGE_EXPIRED, "委托边已到期");
            }
            expectedFrom = edge.toKey();
        }
    }

    private ConsentRepository.GrantRow lockActiveGrant(String subjectKey, Purpose purpose, Instant now) {
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

    private String pathFingerprint(List<EdgeVersionInput> path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (EdgeVersionInput ref : path) {
            sb.append(ref.delegationKey()).append('@').append(ref.version()).append(',');
        }
        return sb.toString();
    }

    private RecordResponse toResponse(ConsentRepository.RecordRow row) {
        List<EdgeBasis> basis = row.delegationPath() == null ? List.of() : readBasis(row.delegationPath());
        return new RecordResponse(row.subjectKey(), row.purpose().name(), row.epoch(), row.callerKey(),
                row.recordKey(), row.payload(), row.evaluatedAt(), basis);
    }

    private String writeBasis(List<EdgeBasis> basis) {
        try {
            return objectMapper.writeValueAsString(basis);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("写入依据序列化失败", e);
        }
    }

    private List<EdgeBasis> readBasis(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<EdgeBasis>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("写入依据反序列化失败", e);
        }
    }
}
