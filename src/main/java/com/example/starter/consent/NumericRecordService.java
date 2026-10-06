package com.example.starter.consent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.dto.DeriveRequest;
import com.example.starter.consent.dto.NumericRecordResponse;
import com.example.starter.consent.dto.NumericWriteRequest;
import com.example.starter.consent.dto.NumericWriteResponse;
import com.example.starter.consent.dto.SourceRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 研究数值/派生记录服务：数值写入、多来源精确求和派生与重新鉴权读取。
 *
 * <p>溯源规则：派生保存直接来源及完整祖先代次集合（主体＋代次＋记录键固化，
 * 不按当前同名键替换）；派生最多 3 层。创建、读取与含值成功重放共同核验完整闭包，
 * 任一祖先代失效则整体 410，即使目标授权仍有效；新授权同名记录不能恢复旧派生。
 *
 * <p>幂等规则：身份含操作、操作者、资源及全部参数和期望版本，以 JSON 结构化序列化比较，
 * 避免分隔符碰撞；同 requestId 同参数重放首次完整响应，异参 409，失败不占键；
 * 仅当前授权有效才重放，撤回后载荷操作 410。业务与去重原子提交，竞争不泄露唯一键异常。
 */
@Service
public class NumericRecordService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_SOURCE_NOT_FOUND = "SOURCE_NOT_FOUND";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_DERIVATION_REVOKED = "DERIVATION_REVOKED";
    static final String CODE_DUPLICATE_SOURCE = "DUPLICATE_SOURCE";
    static final String CODE_RECORD_KEY_CONFLICT = "RECORD_KEY_CONFLICT";
    static final String CODE_EPOCH_MISMATCH = "EPOCH_MISMATCH";
    static final String CODE_SUM_OVERFLOW = "SUM_OVERFLOW";
    static final String CODE_DERIVATION_DEPTH_EXCEEDED = "DERIVATION_DEPTH_EXCEEDED";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";

    static final int MAX_DERIVATION_DEPTH = 3;

    private static final String OP_NUMERIC_WRITE = "NUMERIC_WRITE";
    private static final String OP_DERIVE = "DERIVE";

    private final ConsentRepository consentRepository;
    private final NumericRecordRepository numericRecordRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public NumericRecordService(ConsentRepository consentRepository,
                                NumericRecordRepository numericRecordRepository,
                                IdempotencyRepository idempotencyRepository,
                                ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.numericRecordRepository = numericRecordRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 数值写入：仅当前有效研究代次可写；响应仅含标识与代次，不含值。
     */
    @Transactional
    public NumericWriteResponse writeNumeric(NumericWriteRequest request) {
        String fingerprint = structuredFingerprint(OP_NUMERIC_WRITE, request.operator(),
                request.subjectKey(), request.recordKey(), request.value(), request.expectedEpoch());
        Optional<IdempotencyRepository.IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            NumericWriteResponse snapshot = readSnapshot(replayed.get().responseBody(), NumericWriteResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = requireTargetGrant(request.subjectKey(), request.expectedEpoch());
        ensureKeyAbsent(request.subjectKey(), latest.epoch(), request.recordKey());
        try {
            numericRecordRepository.insert(request.subjectKey(), latest.epoch(), request.recordKey(),
                    RecordKind.NUMERIC, request.value(), 0, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一目标键：不泄露唯一键异常，统一按目标键冲突处理
            throw keyConflict();
        }
        NumericWriteResponse response = new NumericWriteResponse(
                request.subjectKey(), latest.epoch(), request.recordKey());
        storeSuccess(request.requestId(), OP_NUMERIC_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 求和派生：引用 2 至 5 条当前有效研究记录精确求和，超 long 范围 400，派生超 3 层 422。
     */
    @Transactional
    public NumericWriteResponse derive(DeriveRequest request) {
        List<List<Object>> sourceParts = request.sources().stream()
                .map(s -> List.<Object>of(s.subjectKey(), s.epoch(), s.recordKey()))
                .collect(Collectors.toList());
        String fingerprint = structuredFingerprint(OP_DERIVE, request.operator(),
                request.targetSubjectKey(), request.recordKey(), request.expectedEpoch(), sourceParts);
        Optional<IdempotencyRepository.IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            NumericWriteResponse snapshot = readSnapshot(replayed.get().responseBody(), NumericWriteResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.epoch());
            requireClosureActive(snapshot.subjectKey(), snapshot.epoch(), snapshot.recordKey());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = requireTargetGrant(request.targetSubjectKey(), request.expectedEpoch());
        ensureNoDuplicateSources(request.sources());

        List<NumericRecordRepository.NumericRow> sources = new ArrayList<>();
        for (SourceRef ref : request.sources()) {
            sources.add(requireValidSource(ref));
        }
        long sum = 0;
        int depth = 1;
        for (NumericRecordRepository.NumericRow source : sources) {
            sum = exactAdd(sum, source.value());
            depth = Math.max(depth, source.depth() + 1);
        }
        if (depth > MAX_DERIVATION_DEPTH) {
            // 超限不生成记录、不占目标键
            throw ApiException.unprocessableEntity(CODE_DERIVATION_DEPTH_EXCEEDED, "派生层级超过上限 3 层");
        }

        Set<SourceIdentity> ancestors = new LinkedHashSet<>();
        for (int i = 0; i < sources.size(); i++) {
            SourceRef ref = request.sources().get(i);
            ancestors.add(new SourceIdentity(ref.subjectKey(), ref.epoch(), ref.recordKey()));
            if (sources.get(i).kind() == RecordKind.DERIVED) {
                for (NumericRecordRepository.AncestorRow row
                        : numericRecordRepository.findAncestors(ref.subjectKey(), ref.epoch(), ref.recordKey())) {
                    ancestors.add(new SourceIdentity(row.subjectKey(), row.epoch(), row.recordKey()));
                }
            }
        }

        ensureKeyAbsent(request.targetSubjectKey(), latest.epoch(), request.recordKey());
        try {
            numericRecordRepository.insert(request.targetSubjectKey(), latest.epoch(), request.recordKey(),
                    RecordKind.DERIVED, sum, depth, request.requestId());
            for (int i = 0; i < request.sources().size(); i++) {
                SourceRef ref = request.sources().get(i);
                numericRecordRepository.insertSource(request.targetSubjectKey(), latest.epoch(),
                        request.recordKey(), i, ref.subjectKey(), ref.epoch(), ref.recordKey());
            }
            for (SourceIdentity ancestor : ancestors) {
                numericRecordRepository.insertAncestor(request.targetSubjectKey(), latest.epoch(),
                        request.recordKey(), ancestor.subjectKey(), ancestor.epoch(), ancestor.recordKey());
            }
        } catch (DuplicateKeyException concurrent) {
            // 并发派生同一目标键：不泄露唯一键异常，统一按目标键冲突处理
            throw keyConflict();
        }
        NumericWriteResponse response = new NumericWriteResponse(
                request.targetSubjectKey(), latest.epoch(), request.recordKey());
        storeSuccess(request.requestId(), OP_DERIVE, fingerprint, response);
        return response;
    }

    /**
     * 读取：重新鉴权（目标授权及完整祖先闭包均须有效）后返回值。
     */
    @Transactional(readOnly = true)
    public NumericRecordResponse read(String subjectKey, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(subjectKey, Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.epoch());
        NumericRecordRepository.NumericRow row = numericRecordRepository
                .find(subjectKey, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        if (row.kind() == RecordKind.DERIVED) {
            requireClosureActive(subjectKey, latest.epoch(), recordKey);
        }
        return new NumericRecordResponse(row.subjectKey(), row.epoch(), row.recordKey(), row.kind(), row.value());
    }

    /**
     * 目标授权校验：不存在 404；已撤回 410；期望代次与当前代次不一致 409。
     */
    private ConsentRepository.GrantRow requireTargetGrant(String subjectKey, int expectedEpoch) {
        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(subjectKey, Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.epoch());
        if (latest.epoch() != expectedEpoch) {
            throw ApiException.conflict(CODE_EPOCH_MISMATCH, "期望代次与当前研究代次不一致");
        }
        return latest;
    }

    private void requireGrantActive(String subjectKey, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, Purpose.RESEARCH, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 来源校验：授权代次未知 404，旧代或已撤回 410，记录不存在 404。
     */
    private NumericRecordRepository.NumericRow requireValidSource(SourceRef ref) {
        requireGrantActive(ref.subjectKey(), ref.epoch());
        return numericRecordRepository.find(ref.subjectKey(), ref.epoch(), ref.recordKey())
                .orElseThrow(() -> ApiException.notFound(CODE_SOURCE_NOT_FOUND, "来源记录不存在"));
    }

    /**
     * 完整祖先闭包核验：任一祖先代授权失效（不存在或已撤回）则整体 410。
     */
    private void requireClosureActive(String subjectKey, int epoch, String recordKey) {
        for (NumericRecordRepository.AncestorRow ancestor
                : numericRecordRepository.findAncestors(subjectKey, epoch, recordKey)) {
            Optional<ConsentRepository.GrantRow> grant = consentRepository.findGrant(
                    ancestor.subjectKey(), Purpose.RESEARCH, ancestor.epoch());
            if (grant.isEmpty() || grant.get().status() == GrantStatus.REVOKED) {
                throw ApiException.gone(CODE_DERIVATION_REVOKED, "派生祖先授权已撤回");
            }
        }
    }

    private void ensureNoDuplicateSources(List<SourceRef> sources) {
        Set<SourceIdentity> seen = new HashSet<>();
        for (SourceRef ref : sources) {
            if (!seen.add(new SourceIdentity(ref.subjectKey(), ref.epoch(), ref.recordKey()))) {
                throw ApiException.badRequest(CODE_DUPLICATE_SOURCE, "来源记录重复");
            }
        }
    }

    private void ensureKeyAbsent(String subjectKey, int epoch, String recordKey) {
        if (numericRecordRepository.find(subjectKey, epoch, recordKey).isPresent()) {
            throw keyConflict();
        }
    }

    private ApiException keyConflict() {
        return ApiException.conflict(CODE_RECORD_KEY_CONFLICT, "目标记录键已存在");
    }

    private long exactAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw ApiException.badRequest(CODE_SUM_OVERFLOW, "求和结果超出 long 范围");
        }
    }

    /**
     * 幂等重放检查：命中且参数一致返回原快照；参数不一致返回 409。
     */
    private Optional<IdempotencyRepository.IdempotencyRow> checkReplay(String requestId, String fingerprint) {
        Optional<IdempotencyRepository.IdempotencyRow> row = idempotencyRepository.find(requestId);
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
            IdempotencyRepository.IdempotencyRow committed = idempotencyRepository.find(requestId)
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
        }
    }

    /**
     * 结构化参数指纹：以 JSON 数组序列化操作、操作者、资源及全部参数和期望版本，
     * 依靠 JSON 转义避免分隔符碰撞。
     */
    private String structuredFingerprint(String operation, Object... parts) {
        List<Object> identity = new ArrayList<>();
        identity.add(operation);
        identity.addAll(Arrays.asList(parts));
        try {
            return objectMapper.writeValueAsString(identity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("参数指纹序列化失败", e);
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

    /**
     * 来源身份：主体＋代次＋记录键，用于重复来源检测与祖先集合去重。
     */
    private record SourceIdentity(String subjectKey, int epoch, String recordKey) {
    }
}
