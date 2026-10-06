package com.example.starter.consent;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.NumericRecordRepository.AncestorRef;
import com.example.starter.consent.NumericRecordRepository.NumericRecordRow;
import com.example.starter.consent.dto.DeriveRequest;
import com.example.starter.consent.dto.NumericRecordAck;
import com.example.starter.consent.dto.NumericRecordResponse;
import com.example.starter.consent.dto.NumericWriteRequest;
import com.example.starter.consent.dto.SourceRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 数值记录域服务：研究用途下的整数数值写入、求和派生与重新鉴权读取。
 *
 * <p>派生规则：引用 2 至 5 条当前有效研究记录（数值或派生），精确求和，超出 long 范围返回 400；
 * 派生层数最大 3，超限返回 422 且不生成记录、不占目标键。创建时固化直接来源与完整祖先代次集合，
 * 读取与重放共同核验闭包：任一祖先代失效（撤回或旧代）整体返回 410。
 *
 * <p>幂等规则与授权域一致：成功结果与业务变更同事务保存；指纹为结构化 JSON（含操作、操作者、
 * 资源及全部参数和期望版本），避免分隔符碰撞；失败不占 requestId；撤回后重放载荷操作返回 410。
 */
@Service
public class NumericRecordService {

    static final String CODE_RECORD_KEY_CONFLICT = "RECORD_KEY_CONFLICT";
    static final String CODE_DUPLICATE_SOURCE = "DUPLICATE_SOURCE";
    static final String CODE_SUM_OVERFLOW = "SUM_OVERFLOW";
    static final String CODE_DERIVATION_DEPTH_EXCEEDED = "DERIVATION_DEPTH_EXCEEDED";

    static final int MAX_DERIVATION_DEPTH = 3;

    private static final String OP_NUMERIC_WRITE = "NUMERIC_WRITE";
    private static final String OP_DERIVE = "DERIVE";

    private final ConsentRepository consentRepository;
    private final NumericRecordRepository numericRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public NumericRecordService(ConsentRepository consentRepository,
                                NumericRecordRepository numericRepository,
                                IdempotencyRepository idempotencyRepository,
                                ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.numericRepository = numericRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 结构化幂等身份：操作、操作者、资源及全部参数和期望版本，序列化为 JSON 避免分隔符碰撞。
     */
    public record SourceIdentity(String subjectKey, int epoch, String recordKey) {
    }

    public record WriteFingerprint(String operation, String operator, String subjectKey,
                                   String recordKey, long value) {
    }

    public record DeriveFingerprint(String operation, String operator, String targetSubjectKey,
                                    int targetEpoch, String recordKey, List<SourceIdentity> sources) {
    }

    /**
     * 数值写入：仅当前有效研究代次可写；目标键已存在返回 409；响应仅含记录键与代次。
     */
    @Transactional
    public NumericRecordAck writeValue(NumericWriteRequest request) {
        String fingerprint = fingerprintOf(new WriteFingerprint(
                OP_NUMERIC_WRITE, request.operator(), request.subjectKey(), request.recordKey(), request.value()));
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            NumericRecordAck snapshot = readSnapshot(replayed.get().responseBody(), NumericRecordAck.class);
            requireGrantActive(request.subjectKey(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.epoch());

        if (numericRepository.findRecord(request.subjectKey(), latest.epoch(), request.recordKey()).isPresent()) {
            throw ApiException.conflict(CODE_RECORD_KEY_CONFLICT, "目标记录键已存在");
        }
        try {
            numericRepository.insertRecord(request.subjectKey(), latest.epoch(), request.recordKey(),
                    RecordKind.VALUE, request.value(), 0, request.operator(), request.requestId());
            numericRepository.insertAncestor(request.subjectKey(), latest.epoch(), request.recordKey(),
                    request.subjectKey(), latest.epoch());
        } catch (DuplicateKeyException concurrent) {
            return resolveConcurrentWrite(request.subjectKey(), latest.epoch(), request.recordKey(),
                    request.requestId());
        }
        NumericRecordAck ack = new NumericRecordAck(request.recordKey(), latest.epoch());
        storeSuccess(request.requestId(), OP_NUMERIC_WRITE, fingerprint, ack);
        return ack;
    }

    /**
     * 求和派生：来源与目标授权均须当前有效（未知 404、旧代或撤回 410）；来源重复 400；
     * 求和溢出 400；目标键已存在 409；派生层数超 3 返回 422。失败不生成记录、不占目标键。
     */
    @Transactional
    public NumericRecordAck derive(DeriveRequest request) {
        List<SourceRef> sources = request.sources();
        if (new HashSet<>(sources).size() != sources.size()) {
            throw ApiException.badRequest(CODE_DUPLICATE_SOURCE, "来源记录重复");
        }
        List<SourceIdentity> identities = sources.stream()
                .map(s -> new SourceIdentity(s.subjectKey(), s.epoch(), s.recordKey()))
                .sorted(Comparator.comparing(SourceIdentity::subjectKey)
                        .thenComparingInt(SourceIdentity::epoch)
                        .thenComparing(SourceIdentity::recordKey))
                .toList();
        String fingerprint = fingerprintOf(new DeriveFingerprint(OP_DERIVE, request.operator(),
                request.targetSubjectKey(), request.targetEpoch(), request.recordKey(), identities));
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            NumericRecordAck snapshot = readSnapshot(replayed.get().responseBody(), NumericRecordAck.class);
            requireGrantActive(request.targetSubjectKey(), snapshot.epoch());
            requireClosureActive(request.targetSubjectKey(), snapshot.epoch(), snapshot.recordKey());
            return snapshot;
        }

        requireGrantActive(request.targetSubjectKey(), request.targetEpoch());
        if (numericRepository.findRecord(
                request.targetSubjectKey(), request.targetEpoch(), request.recordKey()).isPresent()) {
            throw ApiException.conflict(CODE_RECORD_KEY_CONFLICT, "目标记录键已存在");
        }

        long sum = 0;
        int maxDepth = 0;
        Set<AncestorRef> ancestors = new LinkedHashSet<>();
        for (SourceRef source : sources) {
            requireGrantActive(source.subjectKey(), source.epoch());
            NumericRecordRow sourceRow = numericRepository
                    .findRecord(source.subjectKey(), source.epoch(), source.recordKey())
                    .orElseThrow(() -> ApiException.notFound(
                            ConsentService.CODE_RECORD_NOT_FOUND, "来源记录不存在"));
            List<AncestorRef> sourceAncestors = numericRepository.findAncestors(
                    source.subjectKey(), source.epoch(), source.recordKey());
            for (AncestorRef ancestor : sourceAncestors) {
                requireAncestorActive(ancestor);
            }
            ancestors.addAll(sourceAncestors);
            try {
                sum = Math.addExact(sum, sourceRow.value());
            } catch (ArithmeticException overflow) {
                throw ApiException.badRequest(CODE_SUM_OVERFLOW, "求和结果超出 long 范围");
            }
            maxDepth = Math.max(maxDepth, sourceRow.depth());
        }
        int depth = maxDepth + 1;
        if (depth > MAX_DERIVATION_DEPTH) {
            throw ApiException.unprocessableEntity(CODE_DERIVATION_DEPTH_EXCEEDED, "派生层数超过上限 3");
        }
        ancestors.add(new AncestorRef(request.targetSubjectKey(), request.targetEpoch()));

        try {
            numericRepository.insertRecord(request.targetSubjectKey(), request.targetEpoch(),
                    request.recordKey(), RecordKind.DERIVED, sum, depth, request.operator(), request.requestId());
            int ordinal = 0;
            for (SourceRef source : sources) {
                numericRepository.insertSource(request.targetSubjectKey(), request.targetEpoch(),
                        request.recordKey(), ordinal++, source.subjectKey(), source.epoch(), source.recordKey());
            }
            for (AncestorRef ancestor : ancestors) {
                numericRepository.insertAncestor(request.targetSubjectKey(), request.targetEpoch(),
                        request.recordKey(), ancestor.subjectKey(), ancestor.epoch());
            }
        } catch (DuplicateKeyException concurrent) {
            return resolveConcurrentWrite(request.targetSubjectKey(), request.targetEpoch(),
                    request.recordKey(), request.requestId());
        }
        NumericRecordAck ack = new NumericRecordAck(request.recordKey(), request.targetEpoch());
        storeSuccess(request.requestId(), OP_DERIVE, fingerprint, ack);
        return ack;
    }

    /**
     * 重新鉴权读取：目标授权当前有效且记录完整祖先闭包全部有效才返回值；任一祖先代失效整体 410。
     */
    @Transactional(readOnly = true)
    public NumericRecordResponse read(String subjectKey, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(subjectKey, Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.epoch());
        NumericRecordRow row = numericRepository.findRecord(subjectKey, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_RECORD_NOT_FOUND, "记录不存在"));
        requireClosureActive(row.subjectKey(), row.epoch(), row.recordKey());
        return new NumericRecordResponse(row.subjectKey(), row.epoch(), row.recordKey(), row.kind(), row.value());
    }

    /**
     * 授权代次须存在且当前有效：未知 404，旧代或已撤回 410。
     */
    private void requireGrantActive(String subjectKey, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, Purpose.RESEARCH, epoch)
                .orElseThrow(() -> ApiException.notFound(ConsentService.CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "授权已撤回或为旧代");
        }
    }

    private void requireAncestorActive(AncestorRef ancestor) {
        Optional<ConsentRepository.GrantRow> grant =
                consentRepository.findGrant(ancestor.subjectKey(), Purpose.RESEARCH, ancestor.epoch());
        if (grant.isEmpty() || grant.get().status() == GrantStatus.REVOKED) {
            throw ApiException.gone(ConsentService.CODE_CONSENT_REVOKED, "祖先授权已撤回或为旧代");
        }
    }

    private void requireClosureActive(String subjectKey, int epoch, String recordKey) {
        for (AncestorRef ancestor : numericRepository.findAncestors(subjectKey, epoch, recordKey)) {
            requireAncestorActive(ancestor);
        }
    }

    /**
     * 并发唯一键冲突：已提交记录属于同一 requestId 时视为同一请求返回原结果，否则 409，不泄露唯一键异常。
     */
    private NumericRecordAck resolveConcurrentWrite(String subjectKey, int epoch, String recordKey, String requestId) {
        String committedRequestId = numericRepository.findRecordRequestId(subjectKey, epoch, recordKey)
                .orElseThrow(() -> ApiException.conflict(CODE_RECORD_KEY_CONFLICT, "目标记录键已存在"));
        if (!committedRequestId.equals(requestId)) {
            throw ApiException.conflict(CODE_RECORD_KEY_CONFLICT, "目标记录键已存在");
        }
        return new NumericRecordAck(recordKey, epoch);
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

    private String fingerprintOf(Object identity) {
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等指纹序列化失败", e);
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
