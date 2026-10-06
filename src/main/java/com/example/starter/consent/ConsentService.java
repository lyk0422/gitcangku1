package com.example.starter.consent;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.CohortAggregateRequest;
import com.example.starter.consent.dto.CohortAggregateResponse;
import com.example.starter.consent.dto.CohortMember;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.NumericRecordReadResponse;
import com.example.starter.consent.dto.NumericRecordResponse;
import com.example.starter.consent.dto.NumericRecordWriteRequest;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 授权域服务：实现授权、写入、撤回与查询的业务规则及幂等语义。
 *
 * <p>幂等规则：成功结果与业务变更同事务保存；同一 requestId 相同参数重试返回原结果，
 * 参数变更返回 409；失败请求不占用 requestId。写入重放不得绕过授权状态：
 * 即使 requestId 命中幂等记录，只要所属代次已撤回，仍返回 410。
 */
@Service
public class ConsentService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_GRANT_ALREADY_REVOKED = "GRANT_ALREADY_REVOKED";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_RECORD_NOT_NUMERIC = "RECORD_NOT_NUMERIC";
    static final String CODE_EPOCH_SUPERSEDED = "EPOCH_SUPERSEDED";
    static final String CODE_DUPLICATE_SUBJECT = "DUPLICATE_SUBJECT";
    static final String CODE_PURPOSE_NOT_ALLOWED = "PURPOSE_NOT_ALLOWED";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_NUMERIC_WRITE = "NUMERIC_WRITE";
    private static final String OP_COHORT_AGGREGATE = "COHORT_AGGREGATE";

    /** 成员规范排序：保证集合换序后指纹一致，视为同参。 */
    private static final Comparator<CohortMember> MEMBER_ORDER = Comparator
            .comparing(CohortMember::subjectKey)
            .thenComparing(CohortMember::epoch)
            .thenComparing(CohortMember::recordKey);

    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentRepository consentRepository,
                          IdempotencyRepository idempotencyRepository,
                          ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subjectKey", request.subjectKey());
        params.put("purpose", request.purpose());
        String fingerprint = fingerprintOf(OP_GRANT, params);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        Optional<ConsentRepository.GrantRow> latest =
                consentRepository.findLatestGrant(request.subjectKey(), request.purpose());
        GrantResponse response;
        if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE) {
            response = new GrantResponse(request.subjectKey(), request.purpose(),
                    latest.get().epoch(), GrantStatus.ACTIVE);
        } else {
            int nextEpoch = latest.map(row -> row.epoch() + 1).orElse(1);
            try {
                consentRepository.insertGrant(request.subjectKey(), request.purpose(), nextEpoch, request.requestId());
            } catch (DuplicateKeyException concurrent) {
                // 并发授权同一代次：以已提交的行为准
                ConsentRepository.GrantRow committed =
                        consentRepository.findGrant(request.subjectKey(), request.purpose(), nextEpoch)
                                .orElseThrow(() -> concurrent);
                response = new GrantResponse(committed.subjectKey(), committed.purpose(),
                        committed.epoch(), committed.status());
                storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
                return response;
            }
            response = new GrantResponse(request.subjectKey(), request.purpose(), nextEpoch, GrantStatus.ACTIVE);
        }
        storeSuccess(request.requestId(), OP_GRANT, fingerprint, response);
        return response;
    }

    /**
     * 写入：仅当前有效代次可写；同代同 recordKey 同 payload 去重返回原记录，不同 payload 返回 409。
     */
    @Transactional
    public RecordResponse write(RecordWriteRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subjectKey", request.subjectKey());
        params.put("purpose", request.purpose());
        params.put("recordKey", request.recordKey());
        params.put("payload", request.payload());
        String fingerprint = fingerprintOf(OP_WRITE, params);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RecordResponse snapshot = readSnapshot(replayed.get().responseBody(), RecordResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(existing.get());
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        try {
            consentRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.payload(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.payload().equals(request.payload())) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            RecordResponse response = toResponse(committed);
            storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
            return response;
        }

        RecordResponse response = new RecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), request.payload());
        storeSuccess(request.requestId(), OP_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 撤回：指定代次只允许从有效变为已撤回；撤回提交后旧代查询立即返回 410，写入被拒绝。
     */
    @Transactional
    public GrantResponse revoke(RevokeRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subjectKey", request.subjectKey());
        params.put("purpose", request.purpose());
        params.put("epoch", request.epoch());
        String fingerprint = fingerprintOf(OP_REVOKE, params);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), GrantResponse.class);
        }

        consentRepository.findGrant(request.subjectKey(), request.purpose(), request.epoch())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        boolean revoked = consentRepository.revokeGrant(request.subjectKey(), request.purpose(), request.epoch());
        if (!revoked) {
            throw ApiException.conflict(CODE_GRANT_ALREADY_REVOKED, "授权代次已撤回");
        }
        GrantResponse response = new GrantResponse(request.subjectKey(), request.purpose(),
                request.epoch(), GrantStatus.REVOKED);
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
     * 数值写入：仅当前有效代次可写；同代同 recordKey 同值去重返回原记录元数据，
     * 类型或值不同返回 409。响应仅含元数据，不含数值载荷；重放仍需当前授权有效。
     */
    @Transactional
    public NumericRecordResponse writeNumeric(NumericRecordWriteRequest request) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subjectKey", request.subjectKey());
        params.put("purpose", request.purpose());
        params.put("recordKey", request.recordKey());
        params.put("value", request.value());
        String fingerprint = fingerprintOf(OP_NUMERIC_WRITE, params);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            NumericRecordResponse snapshot = readSnapshot(replayed.get().responseBody(), NumericRecordResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        Optional<ConsentRepository.RecordRow> existing = consentRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            return dedupeNumeric(request, existing.get(), fingerprint);
        }

        try {
            consentRepository.insertNumericRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.value(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            return dedupeNumeric(request, committed, fingerprint);
        }

        NumericRecordResponse response = new NumericRecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey());
        storeSuccess(request.requestId(), OP_NUMERIC_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 数值读取：仅当前有效代次可读；记录不是数值类型返回 422。
     */
    @Transactional(readOnly = true)
    public NumericRecordReadResponse readNumeric(String subjectKey, Purpose purpose, String recordKey) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        ConsentRepository.RecordRow row = consentRepository
                .findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
        if (row.recordType() != RecordType.NUMERIC) {
            throw ApiException.unprocessableEntity(CODE_RECORD_NOT_NUMERIC, "记录不是数值类型");
        }
        return new NumericRecordReadResponse(row.subjectKey(), row.purpose(), row.epoch(),
                row.recordKey(), row.numericValue());
    }

    /**
     * 最小群体汇总：仅研究用途，3-10 个不同主体，每主体一项数值记录；完整有效才返回人数与总和。
     * 成功保存请求参数与结果快照；重放先重新核验原成员指定代仍有效，失效即 410，
     * 不删减成员继续汇总。公开响应不含逐主体值或名单。
     */
    @Transactional
    public CohortAggregateResponse aggregate(CohortAggregateRequest request) {
        if (request.purpose() != Purpose.RESEARCH) {
            throw ApiException.badRequest(CODE_PURPOSE_NOT_ALLOWED, "群体汇总仅支持研究用途");
        }
        List<CohortMember> members = request.members().stream().sorted(MEMBER_ORDER).toList();
        if (members.stream().map(CohortMember::subjectKey).distinct().count() != members.size()) {
            throw ApiException.badRequest(CODE_DUPLICATE_SUBJECT, "成员主体必须互不重复");
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("purpose", request.purpose());
        params.put("members", members.stream().map(member -> {
            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("subjectKey", member.subjectKey());
            entry.put("epoch", member.epoch());
            entry.put("recordKey", member.recordKey());
            return entry;
        }).toList());
        String fingerprint = fingerprintOf(OP_COHORT_AGGREGATE, params);

        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            for (CohortMember member : members) {
                requireCohortEpochCurrent(member.subjectKey(), member.epoch());
            }
            return readSnapshot(replayed.get().responseBody(), CohortAggregateResponse.class);
        }

        long total = 0;
        for (CohortMember member : members) {
            requireCohortEpochCurrent(member.subjectKey(), member.epoch());
            ConsentRepository.RecordRow record = consentRepository.findRecord(
                            member.subjectKey(), Purpose.RESEARCH, member.epoch(), member.recordKey())
                    .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
            if (record.recordType() != RecordType.NUMERIC) {
                throw ApiException.unprocessableEntity(CODE_RECORD_NOT_NUMERIC, "记录不是数值类型");
            }
            total += record.numericValue();
        }
        CohortAggregateResponse response = new CohortAggregateResponse(request.purpose(), members.size(), total);
        storeSuccess(request.requestId(), OP_COHORT_AGGREGATE, fingerprint, response);
        return response;
    }

    /**
     * 核验指定代次仍是该主体研究用途的当前有效代：代次不存在 404，被新代替代或已撤回均 410。
     */
    private void requireCohortEpochCurrent(String subjectKey, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, Purpose.RESEARCH, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        int latestEpoch = consentRepository.findLatestGrant(subjectKey, Purpose.RESEARCH)
                .map(ConsentRepository.GrantRow::epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (latestEpoch != epoch) {
            throw ApiException.gone(CODE_EPOCH_SUPERSEDED, "指定代次已被新代替代");
        }
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    /**
     * 数值记录去重：同 recordKey 已存在 NUMERIC 且值相同返回元数据，否则 409。
     */
    private NumericRecordResponse dedupeNumeric(NumericRecordWriteRequest request,
                                                ConsentRepository.RecordRow existing, String fingerprint) {
        if (existing.recordType() != RecordType.NUMERIC
                || !Objects.equals(existing.numericValue(), request.value())) {
            throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同类型或数值的内容");
        }
        NumericRecordResponse response = new NumericRecordResponse(existing.subjectKey(), existing.purpose(),
                existing.epoch(), existing.recordKey());
        storeSuccess(request.requestId(), OP_NUMERIC_WRITE, fingerprint, response);
        return response;
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
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

    /**
     * 结构化参数指纹：操作＋资源＋全部参数（含期望版本）序列化为 JSON，
     * 键值结构化比较，避免分隔符碰撞。
     */
    private String fingerprintOf(String operation, Map<String, ?> params) {
        Map<String, Object> identity = new LinkedHashMap<>(2);
        identity.put("operation", operation);
        identity.put("params", params);
        return writeSnapshot(identity);
    }

    private RecordResponse toResponse(ConsentRepository.RecordRow row) {
        return new RecordResponse(row.subjectKey(), row.purpose(), row.epoch(), row.recordKey(), row.payload());
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
