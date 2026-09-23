package com.example.starter.consent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.DelegationRepository.DelegationRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现限时授权、委托链校验写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>写入在同一事务快照内完成：先锁定授权代次行，读取唯一评估时刻 now，校验授权
 * ACTIVE 且未到期、委托路径从主体连续到调用方、每边未撤销且在 now 有效、提交版本与
 * 现网一致且路径为当前最短有效路径；任一不满足均抛错回滚，不写数据。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权状态：即使 requestId
 * 命中幂等记录，只要所属代次已撤回或授权已到期，仍返回 410/403。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_GRANT_EXPIRED = "GRANT_EXPIRED";
    static final String CODE_DELEGATION_PATH_INVALID = "DELEGATION_PATH_INVALID";
    static final String CODE_DELEGATION_EDGE_REVOKED = "DELEGATION_EDGE_REVOKED";
    static final String CODE_DELEGATION_EXPIRED = "DELEGATION_EDGE_EXPIRED";
    static final String CODE_DELEGATION_VERSION_CONFLICT = "DELEGATION_VERSION_CONFLICT";
    static final String CODE_DELEGATION_PATH_NOT_SHORTEST = "DELEGATION_PATH_NOT_SHORTEST";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";

    private static final TypeReference<List<String>> PATH_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Integer>> VERSIONS_TYPE = new TypeReference<>() {
    };

    private final ConsentRepository consentRepository;
    private final DelegationRepository delegationRepository;
    private final DelegationGraph delegationGraph;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final TimeSource timeSource;

    public ConsentService(ConsentRepository consentRepository,
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
     * 授权：当前授权仍有效（ACTIVE 且未到期）时返回原代次；撤回或到期后生成下一代（从 1 起递增）。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.expiresAt();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        Instant now = timeSource.now();
        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        GrantResponse response;
        if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE
                && !latest.get().expiresAt().isBefore(now)) {
            response = toGrantResponse(latest.get());
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
                response = toGrantResponse(committed);
                storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(), nextEpoch,
                    GrantStatus.ACTIVE, request.expiresAt());
        }
        storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；主体直写提交空委托链，处理方写入提交完整链与各边版本。
     * 同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload()
                + "|" + request.delegationPath() + "|" + request.edgeVersions();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireGrantWritable(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch(),
                    timeSource.now());
            return snapshot;
        }

        Instant now = timeSource.now();
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        // 锁定代次行：与撤回/续建及其他写入按提交顺序串行化，杜绝过期快照穿透
        ConsentRepository.GrantRow locked = consentRepository
                .findGrantForUpdate(latest.subjectKey(), latest.purpose(), latest.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        requireGrantRowWritable(locked, now);

        List<DelegationRow> chain = verifyDelegationPath(request, locked, now);

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), locked.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        List<String> path = request.delegationPath();
        List<Integer> versions = request.edgeVersions();
        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), locked.epoch(),
                    request.recordKey(), request.payload(), writeJson(path), writeJson(versions),
                    now, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), locked.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(committed);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                locked.epoch(), request.recordKey(), request.payload(), path, versions, now);
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代查询立即返回 410，
     * 该代整条委托链随之失效，新代次不得读取旧代数据。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.epoch();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        // 锁定代次行，与写入/委托并发按提交顺序串行化
        ConsentRepository.GrantRow existing = consentRepository
                .findGrantForUpdate(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED, existing.expiresAt());
        storeSuccess(request.requestId(), OP_REVOKE, fingerprint, response);
        return response;
    }

    /**
     * 查询：仅当前有效代次可查；撤回后返回 410，记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        return consentRepository.findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
    }

    /**
     * 在同一快照内校验提交的委托路径并返回最短有效边链。主体直写（空链）只受授权状态约束。
     * 调用前已锁定授权代次行；本方法再按主键顺序锁定该代次全部 ACTIVE 边，
     * 保证与委托撤销/续建按提交顺序串行化且加锁顺序确定。
     */
    private List<DelegationRow> verifyDelegationPath(RecordWriteRequest request,
                                                     ConsentRepository.GrantRow grant, Instant now) {
        List<String> path = request.delegationPath();
        List<Integer> submittedVersions = request.edgeVersions();
        if (path.size() != submittedVersions.size()) {
            throw ApiException.badRequest(CODE_DELEGATION_PATH_INVALID, "委托链与边版本数量不一致");
        }
        if (path.isEmpty()) {
            return List.of();
        }
        if (path.size() > DelegationGraph.MAX_DEPTH) {
            throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID,
                    "委托链最长不得超过 " + DelegationGraph.MAX_DEPTH + " 层");
        }
        if (path.stream().distinct().count() != path.size()) {
            throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "委托链不能包含重复节点");
        }

        // 加锁点：授权代次行之后、任何边行之前，按 id 顺序锁定全部 ACTIVE 边
        List<DelegationRow> activeEdges = delegationRepository.findActiveEdgesForUpdate(
                grant.subjectKey(), grant.purpose(), grant.epoch());

        // 逐边核对连续性、版本归属、状态与到期时刻（ACTIVE 行已在锁定快照内）
        for (int i = 0; i < path.size(); i++) {
            String delegator = i == 0 ? grant.subjectKey() : path.get(i - 1);
            String processor = path.get(i);
            int version = submittedVersions.get(i);
            DelegationRow edge = delegationRepository.findEdge(
                    grant.subjectKey(), grant.purpose(), grant.epoch(),
                    delegator, processor, version)
                    .orElseGet(() -> {
                        // 该有向边存在但提交版本不符 -> 409；边根本不存在 -> 403
                        Optional<DelegationRow> latestEdge = delegationRepository.findLatestEdge(
                                grant.subjectKey(), grant.purpose(), grant.epoch(), delegator, processor);
                        if (latestEdge.isPresent()) {
                            throw ApiException.conflict(CODE_DELEGATION_VERSION_CONFLICT,
                                    "委托边版本已过期: " + delegator + "->" + processor);
                        }
                        throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID,
                                "委托边缺失: " + delegator + "->" + processor);
                    });
            if (edge.status() == DelegationStatus.REVOKED) {
                throw ApiException.forbidden(CODE_DELEGATION_EDGE_REVOKED,
                        "委托边已撤销: " + delegator + "->" + processor);
            }
            if (edge.expiresAt().isBefore(now)) {
                throw ApiException.forbidden(CODE_DELEGATION_EXPIRED,
                        "委托边已到期: " + delegator + "->" + processor);
            }
        }

        // 图校验：提交路径必须是主体到调用方当前的最短有效路径（逐边版本也须一致）
        String caller = path.get(path.size() - 1);
        List<DelegationRow> shortest = delegationGraph.shortestPath(
                activeEdges, grant.subjectKey(), caller, now);
        if (shortest == null) {
            throw ApiException.forbidden(CODE_DELEGATION_PATH_INVALID, "调用方不在当前有效委托链内");
        }
        if (shortest.size() != path.size()) {
            throw ApiException.conflict(CODE_DELEGATION_PATH_NOT_SHORTEST,
                    "提交的委托路径不是当前最短有效路径");
        }
        for (int i = 0; i < shortest.size(); i++) {
            DelegationRow expected = shortest.get(i);
            if (!expected.processorKey().equals(path.get(i))
                    || expected.version() != submittedVersions.get(i)) {
                throw ApiException.conflict(CODE_DELEGATION_PATH_NOT_SHORTEST,
                        "提交的委托路径或版本不是当前最短有效路径");
            }
        }
        return shortest;
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        if (grant.expiresAt().isBefore(timeSource.now())) {
            throw ApiException.forbidden(CODE_GRANT_EXPIRED, "授权已到期");
        }
    }

    private void requireGrantWritable(String subjectKey, Purpose purpose, int epoch, Instant now) {
        ConsentRepository.GrantRow grant = consentRepository.findGrantForUpdate(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        requireGrantRowWritable(grant, now);
    }

    private void requireGrantRowWritable(ConsentRepository.GrantRow grant, Instant now) {
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        if (grant.expiresAt().isBefore(now)) {
            throw ApiException.forbidden(CODE_GRANT_EXPIRED, "授权已到期");
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

    private GrantResponse toGrantResponse(ConsentRepository.GrantRow row) {
        return new GrantResponse(row.subjectKey(), row.purpose(), row.epoch(), row.status(), row.expiresAt());
    }

    private RecordResponse toResponse(ConsentRepository.RecordRow row) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(), row.recordKey(),
                row.payload(), readJson(row.delegationPath(), PATH_TYPE),
                readJson(row.edgeVersions(), VERSIONS_TYPE), row.evaluatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T readJson(String body, TypeReference<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    private String writeSnapshot(Object response) {
        return writeJson(response);
    }

    private <T> T readSnapshot(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应快照反序列化失败", e);
        }
    }
}
