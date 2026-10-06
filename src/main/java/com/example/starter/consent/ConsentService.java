package com.example.starter.consent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.CohortSnapshotRepository.SnapshotRow;
import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.CohortAggregateRequest;
import com.example.starter.consent.dto.CohortAggregateResponse;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.NumericRecordResponse;
import com.example.starter.consent.dto.NumericRecordWriteRequest;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RevokeRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
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
    static final String CODE_RECORD_TYPE_CONFLICT = "RECORD_TYPE_CONFLICT";
    static final String CODE_RECORD_VALUE_CONFLICT = "RECORD_VALUE_CONFLICT";
    static final String CODE_PURPOSE_NOT_ALLOWED = "PURPOSE_NOT_ALLOWED";
    static final String CODE_DUPLICATE_SUBJECT = "DUPLICATE_SUBJECT";
    static final String CODE_EPOCH_SUPERSEDED = "EPOCH_SUPERSEDED";
    static final String CODE_RECORD_NOT_NUMERIC = "RECORD_NOT_NUMERIC";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_WRITE_NUMERIC = "WRITE_NUMERIC";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_AGGREGATE = "AGGREGATE";

    private final ConsentRepository consentRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final CohortSnapshotRepository cohortSnapshotRepository;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentRepository consentRepository,
                          IdempotencyRepository idempotencyRepository,
                          CohortSnapshotRepository cohortSnapshotRepository,
                          ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.cohortSnapshotRepository = cohortSnapshotRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权：当前授权仍有效时返回原代次；撤回后或首次授权生成下一代（从 1 开始递增）。
     */
    @Transactional
    public GrantResponse grant(GrantRequest request) {
        String fingerprint = OP_GRANT + "|" + request.subjectKey() + "|" + request.purpose();
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
        String fingerprint = OP_WRITE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.recordKey() + "|" + request.payload();
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
            requireTextRecord(existing.get());
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
            requireTextRecord(committed);
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
        String fingerprint = OP_REVOKE + "|" + request.subjectKey() + "|" + request.purpose() + "|" + request.epoch();
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

    /**
     * 数值写入：仅当前有效代次可写，值为 0-1000 非负整数；响应仅回元数据，不含数值本身。
     * 同代同 recordKey 同值去重返回元数据，不同值返回 409；与字符串记录同键返回 409。
     */
    @Transactional
    public NumericRecordResponse writeNumeric(NumericRecordWriteRequest request) {
        String fingerprint = numericWriteFingerprint(request);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            // 当前授权有效才允许重放；撤回后载荷操作返回 410
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
            return dedupNumeric(request, existing.get(), fingerprint);
        }

        try {
            consentRepository.insertNumericRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), request.value(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准
            ConsentRepository.RecordRow committed = consentRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            return dedupNumeric(request, committed, fingerprint);
        }

        NumericRecordResponse response = new NumericRecordResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), request.recordKey(), RecordType.NUMBER);
        storeSuccess(request.requestId(), OP_WRITE_NUMERIC, fingerprint, response);
        return response;
    }

    /**
     * 最小群体汇总：仅研究用途，3-10 个不同主体、每主体一项、指定代次须仍为当前有效代次，
     * 全部成员完整有效才返回人数与总和；成功保存完整依据快照，公开响应不含个人值。
     */
    @Transactional
    public CohortAggregateResponse aggregate(CohortAggregateRequest request) {
        if (request.purpose() != Purpose.RESEARCH) {
            throw ApiException.badRequest(CODE_PURPOSE_NOT_ALLOWED, "群体汇总仅支持研究用途");
        }
        Set<String> subjects = new HashSet<>();
        for (CohortAggregateRequest.Member member : request.members()) {
            if (!subjects.add(member.subjectKey())) {
                throw ApiException.badRequest(CODE_DUPLICATE_SUBJECT, "同一主体在群体中重复出现");
            }
        }

        String fingerprint = aggregateFingerprint(request.members());
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            // 重放先核验原成员的指定代仍有效，失效即 410，不删成员继续汇总
            SnapshotRow snapshot = cohortSnapshotRepository.find(request.requestId())
                    .orElseThrow(() -> new IllegalStateException("群体汇总快照缺失: " + request.requestId()));
            for (StoredMember member : readBasis(snapshot.membersJson())) {
                requireEpochCurrentAndActive(member.subjectKey(), member.epoch());
            }
            return readSnapshot(replayed.get().responseBody(), CohortAggregateResponse.class);
        }

        // 与写入、撤回同一事务视图取数，逐成员核验并取值
        List<StoredMember> basis = new ArrayList<>();
        for (CohortAggregateRequest.Member member : request.members()) {
            requireEpochCurrentAndActive(member.subjectKey(), member.epoch());
            ConsentRepository.RecordRow record = consentRepository.findRecord(
                            member.subjectKey(), Purpose.RESEARCH, member.epoch(), member.recordKey())
                    .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));
            if (record.recordType() != RecordType.NUMBER) {
                throw ApiException.unprocessableEntity(CODE_RECORD_NOT_NUMERIC, "记录不是数值类型");
            }
            basis.add(new StoredMember(member.subjectKey(), member.epoch(), member.recordKey(), record.numValue()));
        }

        CohortAggregateResponse response = new CohortAggregateResponse(
                basis.size(), basis.stream().mapToLong(StoredMember::value).sum());
        // 先占幂等键再写快照：竞争失败时校验指纹并返回已提交结果，不泄露唯一键异常
        try {
            idempotencyRepository.insert(request.requestId(), OP_AGGREGATE, fingerprint, writeSnapshot(response));
        } catch (DuplicateKeyException concurrent) {
            IdempotencyRow committed = idempotencyRepository.find(request.requestId())
                    .orElseThrow(() -> concurrent);
            if (!committed.paramsFingerprint().equals(fingerprint)) {
                throw ApiException.conflict(CODE_REQUEST_ID_CONFLICT, "同一 requestId 参数不一致");
            }
            return readSnapshot(committed.responseBody(), CohortAggregateResponse.class);
        }
        cohortSnapshotRepository.insert(request.requestId(), Purpose.RESEARCH,
                response.memberCount(), response.total(), writeSnapshot(basis));
        return response;
    }

    /**
     * 汇总完整依据中的成员项：含逐主体值，仅服务端留存，不对外返回。
     *
     * @param subjectKey 主体标识
     * @param epoch      汇总时核验的授权代次
     * @param recordKey  记录键
     * @param value      该主体的数值（0-1000）
     */
    public record StoredMember(String subjectKey, int epoch, String recordKey, int value) {
    }

    private NumericRecordResponse dedupNumeric(NumericRecordWriteRequest request,
                                               ConsentRepository.RecordRow existing, String fingerprint) {
        if (existing.recordType() != RecordType.NUMBER) {
            throw ApiException.conflict(CODE_RECORD_TYPE_CONFLICT, "相同 recordKey 已存在字符串记录");
        }
        if (!existing.numValue().equals(request.value())) {
            throw ApiException.conflict(CODE_RECORD_VALUE_CONFLICT, "相同 recordKey 已存在不同数值");
        }
        NumericRecordResponse response = new NumericRecordResponse(existing.subjectKey(), existing.purpose(),
                existing.epoch(), existing.recordKey(), RecordType.NUMBER);
        storeSuccess(request.requestId(), OP_WRITE_NUMERIC, fingerprint, response);
        return response;
    }

    private void requireTextRecord(ConsentRepository.RecordRow record) {
        if (record.recordType() != RecordType.TEXT) {
            throw ApiException.conflict(CODE_RECORD_TYPE_CONFLICT, "相同 recordKey 已存在数值记录");
        }
    }

    /**
     * 核验指定代次仍存在、未撤回且仍为当前最新有效代；已撤回或被新代替代均返回 410。
     */
    private void requireEpochCurrentAndActive(String subjectKey, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, Purpose.RESEARCH, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, Purpose.RESEARCH)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        if (latest.epoch() != epoch || latest.status() != GrantStatus.ACTIVE) {
            throw ApiException.gone(CODE_EPOCH_SUPERSEDED, "授权代次已被新代替代");
        }
    }

    /**
     * 数值写入幂等身份：结构化 JSON，含操作、资源（主体/用途/记录键）与全部参数，避免分隔符碰撞。
     */
    private String numericWriteFingerprint(NumericRecordWriteRequest request) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_WRITE_NUMERIC);
        identity.put("subjectKey", request.subjectKey());
        identity.put("purpose", request.purpose().name());
        identity.put("recordKey", request.recordKey());
        identity.put("value", request.value());
        return writeSnapshot(identity);
    }

    /**
     * 群体汇总幂等身份：结构化 JSON，成员按主体/代次/记录键排序，集合换序视为相同参数。
     */
    private String aggregateFingerprint(List<CohortAggregateRequest.Member> members) {
        List<CohortAggregateRequest.Member> sorted = new ArrayList<>(members);
        sorted.sort(Comparator.comparing(CohortAggregateRequest.Member::subjectKey)
                .thenComparing(CohortAggregateRequest.Member::epoch)
                .thenComparing(CohortAggregateRequest.Member::recordKey));
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_AGGREGATE);
        identity.put("purpose", Purpose.RESEARCH.name());
        identity.put("members", sorted);
        return writeSnapshot(identity);
    }

    private List<StoredMember> readBasis(String membersJson) {
        try {
            return objectMapper.readValue(membersJson, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("汇总依据反序列化失败", e);
        }
    }
}
