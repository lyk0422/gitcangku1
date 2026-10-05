package com.example.starter.consent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.StructuredRecordRepository.RestrictionRow;
import com.example.starter.consent.StructuredRecordRepository.StructuredRecordRow;
import com.example.starter.consent.dto.GrantRequest;
import com.example.starter.consent.dto.GrantResponse;
import com.example.starter.consent.dto.RecordResponse;
import com.example.starter.consent.dto.RecordWriteRequest;
import com.example.starter.consent.dto.RestrictionReplaceRequest;
import com.example.starter.consent.dto.RestrictionResponse;
import com.example.starter.consent.dto.RevokeRequest;
import com.example.starter.consent.dto.StructuredRecordReadResponse;
import com.example.starter.consent.dto.StructuredRecordWriteRequest;
import com.example.starter.consent.dto.StructuredRecordWriteResponse;
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
    static final String CODE_INVALID_FIELD = "INVALID_FIELD";
    static final String CODE_UNKNOWN_FIELD = "UNKNOWN_FIELD";
    static final String CODE_RESTRICTION_VERSION_CONFLICT = "RESTRICTION_VERSION_CONFLICT";

    private static final String OP_GRANT = "GRANT";
    private static final String OP_WRITE = "WRITE";
    private static final String OP_REVOKE = "REVOKE";
    private static final String OP_STRUCTURED_WRITE = "STRUCTURED_WRITE";
    private static final String OP_RESTRICT = "RESTRICT";

    /** 字段名规则：英文字母、数字、下划线 */
    private static final Pattern FIELD_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]+");
    /** 单个字段值最大长度（字符数） */
    private static final int MAX_FIELD_VALUE_LENGTH = 100;
    /** 单条结构化记录字段数上限 */
    private static final int MAX_FIELD_COUNT = 10;

    private final ConsentRepository consentRepository;
    private final StructuredRecordRepository structuredRecordRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentRepository consentRepository,
                          StructuredRecordRepository structuredRecordRepository,
                          IdempotencyRepository idempotencyRepository,
                          ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.structuredRecordRepository = structuredRecordRepository;
        this.idempotencyRepository = idempotencyRepository;
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

    /**
     * 结构化写入：仅当前有效代次可写；保存 1 至 10 个合成字符串字段。
     * 响应只含记录标识与版本元数据，不带字段内容；同代同 recordKey 同字段去重，不同字段返回 409。
     */
    @Transactional
    public StructuredRecordWriteResponse writeStructured(StructuredRecordWriteRequest request) {
        Map<String, String> fields = validateAndCanonicalizeFields(request.fields());
        String fieldsJson = writeSnapshot(fields);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_STRUCTURED_WRITE);
        identity.put("subjectKey", request.subjectKey());
        identity.put("purpose", request.purpose().name());
        identity.put("recordKey", request.recordKey());
        identity.put("fields", fields);
        String fingerprint = structuredFingerprint(identity);

        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            StructuredRecordWriteResponse snapshot =
                    readSnapshot(replayed.get().responseBody(), StructuredRecordWriteResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        Optional<StructuredRecordRow> existing = structuredRecordRepository.findStructuredRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().fieldsJson().equals(fieldsJson)) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同字段内容");
            }
            StructuredRecordWriteResponse response = new StructuredRecordWriteResponse(request.subjectKey(),
                    request.purpose(), latest.epoch(), request.recordKey(),
                    currentRestrictionVersion(request.subjectKey(), request.purpose(), latest.epoch()));
            storeSuccess(request.requestId(), OP_STRUCTURED_WRITE, fingerprint, response);
            return response;
        }

        try {
            structuredRecordRepository.insertStructuredRecord(request.subjectKey(), request.purpose(),
                    latest.epoch(), request.recordKey(), fieldsJson, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准，不泄露唯一键异常
            StructuredRecordRow committed = structuredRecordRepository.findStructuredRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.fieldsJson().equals(fieldsJson)) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同字段内容");
            }
            StructuredRecordWriteResponse response = new StructuredRecordWriteResponse(request.subjectKey(),
                    request.purpose(), latest.epoch(), request.recordKey(),
                    currentRestrictionVersion(request.subjectKey(), request.purpose(), latest.epoch()));
            storeSuccess(request.requestId(), OP_STRUCTURED_WRITE, fingerprint, response);
            return response;
        }

        StructuredRecordWriteResponse response = new StructuredRecordWriteResponse(request.subjectKey(),
                request.purpose(), latest.epoch(), request.recordKey(),
                currentRestrictionVersion(request.subjectKey(), request.purpose(), latest.epoch()));
        storeSuccess(request.requestId(), OP_STRUCTURED_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 结构化读取：每次调用都重新验证当前授权，再按当前限制清单投影。
     * 可指定字段集合；未知字段返回 400，禁止字段不出现，全部被禁止时返回空对象。
     */
    @Transactional(readOnly = true)
    public StructuredRecordReadResponse readStructured(String subjectKey, Purpose purpose, String recordKey,
                                                       Set<String> requestedFields) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        StructuredRecordRow row = structuredRecordRepository
                .findStructuredRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));

        Map<String, String> fields = readFieldsJson(row.fieldsJson());
        if (requestedFields != null) {
            for (String name : requestedFields) {
                if (!fields.containsKey(name)) {
                    throw ApiException.badRequest(CODE_UNKNOWN_FIELD, "字段不存在: " + name);
                }
            }
        }

        Optional<RestrictionRow> restriction =
                structuredRecordRepository.findLatestRestriction(subjectKey, purpose, latest.epoch());
        int restrictionVersion = restriction.map(RestrictionRow::version).orElse(0);
        Set<String> forbidden = restriction
                .<Set<String>>map(r -> new LinkedHashSet<>(readForbiddenFieldsJson(r.forbiddenFieldsJson())))
                .orElseGet(Set::of);

        Map<String, String> projection = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (requestedFields != null && !requestedFields.contains(entry.getKey())) {
                continue;
            }
            if (forbidden.contains(entry.getKey())) {
                continue;
            }
            projection.put(entry.getKey(), entry.getValue());
        }
        return new StructuredRecordReadResponse(subjectKey, purpose, latest.epoch(), recordKey,
                restrictionVersion, projection);
    }

    /**
     * 限制替换：整体替换当前有效代次的禁止字段清单，版本从 1 递增。
     * expectedRestrictionVersion 与当前版本不一致返回 409；禁止字段集合可为空。
     */
    @Transactional
    public RestrictionResponse replaceRestriction(RestrictionReplaceRequest request) {
        List<String> forbidden = validateAndCanonicalizeForbidden(request.forbiddenFields());
        String forbiddenJson = writeSnapshot(forbidden);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("operation", OP_RESTRICT);
        identity.put("subjectKey", request.subjectKey());
        identity.put("purpose", request.purpose().name());
        identity.put("expectedRestrictionVersion", request.expectedRestrictionVersion());
        identity.put("forbiddenFields", forbidden);
        String fingerprint = structuredFingerprint(identity);

        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            RestrictionResponse snapshot = readSnapshot(replayed.get().responseBody(), RestrictionResponse.class);
            requireGrantActive(snapshot.subjectKey(), snapshot.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        int currentVersion = currentRestrictionVersion(request.subjectKey(), request.purpose(), latest.epoch());
        if (request.expectedRestrictionVersion() != currentVersion) {
            throw ApiException.conflict(CODE_RESTRICTION_VERSION_CONFLICT,
                    "限制清单版本不匹配，当前版本: " + currentVersion);
        }
        int newVersion = currentVersion + 1;
        try {
            structuredRecordRepository.insertRestriction(request.subjectKey(), request.purpose(),
                    latest.epoch(), newVersion, forbiddenJson, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发替换同一版本：唯一约束拦截，转换为版本冲突，不泄露唯一键异常
            throw ApiException.conflict(CODE_RESTRICTION_VERSION_CONFLICT, "限制清单已被并发更新");
        }

        RestrictionResponse response = new RestrictionResponse(request.subjectKey(), request.purpose(),
                latest.epoch(), newVersion, forbidden);
        storeSuccess(request.requestId(), OP_RESTRICT, fingerprint, response);
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
     * 校验字段集合并按字段名排序规范化：1 至 10 个字段，字段名为英文字母数字下划线，每值最多 100 字符。
     */
    private Map<String, String> validateAndCanonicalizeFields(Map<String, String> fields) {
        if (fields == null || fields.isEmpty() || fields.size() > MAX_FIELD_COUNT) {
            throw ApiException.badRequest(CODE_INVALID_FIELD, "字段数量必须为 1 至 " + MAX_FIELD_COUNT + " 个");
        }
        Map<String, String> canonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (name == null || !FIELD_NAME_PATTERN.matcher(name).matches()) {
                throw ApiException.badRequest(CODE_INVALID_FIELD, "字段名只允许英文字母数字下划线: " + name);
            }
            if (value == null || value.length() > MAX_FIELD_VALUE_LENGTH) {
                throw ApiException.badRequest(CODE_INVALID_FIELD,
                        "字段值不能为空且最多 " + MAX_FIELD_VALUE_LENGTH + " 字符: " + name);
            }
            canonical.put(name, value);
        }
        return canonical;
    }

    /**
     * 校验禁止字段名并排序去重；集合可为空。
     */
    private List<String> validateAndCanonicalizeForbidden(List<String> forbiddenFields) {
        if (forbiddenFields == null) {
            throw ApiException.badRequest(CODE_INVALID_FIELD, "禁止字段集合不能为 null");
        }
        for (String name : forbiddenFields) {
            if (name == null || !FIELD_NAME_PATTERN.matcher(name).matches()) {
                throw ApiException.badRequest(CODE_INVALID_FIELD, "字段名只允许英文字母数字下划线: " + name);
            }
        }
        return new ArrayList<>(new TreeSet<>(forbiddenFields));
    }

    private int currentRestrictionVersion(String subjectKey, Purpose purpose, int epoch) {
        return structuredRecordRepository.findLatestRestriction(subjectKey, purpose, epoch)
                .map(RestrictionRow::version)
                .orElse(0);
    }

    /**
     * 结构化参数指纹：把操作、主体、资源与全部参数按固定结构序列化为 JSON 后取 SHA-256，
     * 避免字符串拼接的分隔符碰撞，同时控制指纹长度。
     */
    private String structuredFingerprint(Map<String, Object> identity) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(writeSnapshot(identity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private Map<String, String> readFieldsJson(String fieldsJson) {
        try {
            return objectMapper.readValue(fieldsJson, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("字段集合反序列化失败", e);
        }
    }

    private List<String> readForbiddenFieldsJson(String forbiddenFieldsJson) {
        try {
            return objectMapper.readValue(forbiddenFieldsJson, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("禁止字段集合反序列化失败", e);
        }
    }
}
