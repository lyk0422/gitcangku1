package com.example.starter.consent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.StructuredRecordRepository.RestrictionRow;
import com.example.starter.consent.StructuredRecordRepository.StructuredRecordRow;
import com.example.starter.consent.dto.FieldRestrictionReplaceRequest;
import com.example.starter.consent.dto.FieldRestrictionResponse;
import com.example.starter.consent.dto.StructuredRecordReadResponse;
import com.example.starter.consent.dto.StructuredRecordWriteRequest;
import com.example.starter.consent.dto.StructuredRecordWriteResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化记录与字段级限制域服务：在不撤回整代授权的前提下，对当前代次的字段读取做临时禁止。
 *
 * <p>限制清单绑定主体、用途与代次，整体替换式版本从 1 开始递增；读取先验证当前授权，
 * 再按当前清单投影字段，被禁止字段不出现，全部被禁止返回空对象。限制不跨用途、不跨代次继承，
 * 重新授权后新代次默认无清单。
 *
 * <p>幂等规则与 {@link ConsentService} 一致：成功结果与业务变更同事务保存；同一 requestId
 * 相同参数重试返回首次完整响应，参数变更返回 409；失败请求不占用 requestId。参数指纹使用
 * 结构化 JSON（操作、主体、用途、资源及全部参数和期望版本），避免分隔符碰撞。
 * 重放不绕过授权状态：所属代次已撤回时，载荷操作（写入、限制替换）仍返回 410。
 */
@Service
public class StructuredRecordService {

    static final String CODE_GRANT_NOT_FOUND = "GRANT_NOT_FOUND";
    static final String CODE_RECORD_NOT_FOUND = "RECORD_NOT_FOUND";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_RECORD_PAYLOAD_CONFLICT = "RECORD_PAYLOAD_CONFLICT";
    static final String CODE_CONSENT_REVOKED = "CONSENT_REVOKED";
    static final String CODE_INVALID_FIELD = "INVALID_FIELD";
    static final String CODE_UNKNOWN_FIELD = "UNKNOWN_FIELD";
    static final String CODE_RESTRICTION_VERSION_CONFLICT = "RESTRICTION_VERSION_CONFLICT";

    private static final String OP_STRUCTURED_WRITE = "STRUCTURED_WRITE";
    private static final String OP_RESTRICTION_REPLACE = "RESTRICTION_REPLACE";
    private static final Pattern FIELD_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final int MAX_VALUE_LENGTH = 100;

    private final ConsentRepository consentRepository;
    private final StructuredRecordRepository structuredRecordRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public StructuredRecordService(ConsentRepository consentRepository,
                                   StructuredRecordRepository structuredRecordRepository,
                                   IdempotencyRepository idempotencyRepository,
                                   ObjectMapper objectMapper) {
        this.consentRepository = consentRepository;
        this.structuredRecordRepository = structuredRecordRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入结构化记录：仅当前有效代次可写；同代同 recordKey 同字段去重返回原结果，不同字段返回 409。
     * 响应只含记录标识与版本元数据，不携带字段内容。
     */
    @Transactional
    public StructuredRecordWriteResponse write(StructuredRecordWriteRequest request) {
        Map<String, String> fields = validateFields(request.fields());
        String fieldsJson = writeJson(fields);
        String fingerprint = fingerprint(OP_STRUCTURED_WRITE, request.subjectKey(),
                request.purpose().name(), request.recordKey(), fields);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            StructuredRecordWriteResponse snapshot =
                    readSnapshot(replayed.get().responseBody(), StructuredRecordWriteResponse.class);
            requireGrantActive(request.subjectKey(), request.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        int restrictionVersion = currentRestriction(latest.subjectKey(), latest.purpose(), latest.epoch())
                .map(RestrictionRow::restrictionVersion).orElse(0);

        Optional<StructuredRecordRow> existing = structuredRecordRepository.findRecord(
                request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey());
        if (existing.isPresent()) {
            if (!existing.get().fieldsJson().equals(fieldsJson)) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            StructuredRecordWriteResponse response = new StructuredRecordWriteResponse(
                    request.recordKey(), latest.epoch(), restrictionVersion);
            storeSuccess(request.requestId(), OP_STRUCTURED_WRITE, fingerprint, response);
            return response;
        }

        try {
            structuredRecordRepository.insertRecord(request.subjectKey(), request.purpose(), latest.epoch(),
                    request.recordKey(), fieldsJson, request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发写入同一 recordKey：以已提交的记录为准，不向调用方泄露唯一键异常
            StructuredRecordRow committed = structuredRecordRepository.findRecord(
                            request.subjectKey(), request.purpose(), latest.epoch(), request.recordKey())
                    .orElseThrow(() -> concurrent);
            if (!committed.fieldsJson().equals(fieldsJson)) {
                throw ApiException.conflict(CODE_RECORD_PAYLOAD_CONFLICT, "相同 recordKey 已存在不同内容");
            }
            StructuredRecordWriteResponse response = new StructuredRecordWriteResponse(
                    request.recordKey(), latest.epoch(), restrictionVersion);
            storeSuccess(request.requestId(), OP_STRUCTURED_WRITE, fingerprint, response);
            return response;
        }

        StructuredRecordWriteResponse response = new StructuredRecordWriteResponse(
                request.recordKey(), latest.epoch(), restrictionVersion);
        storeSuccess(request.requestId(), OP_STRUCTURED_WRITE, fingerprint, response);
        return response;
    }

    /**
     * 读取结构化记录：先验证当前授权，再按当前限制清单投影字段。可指定字段集合，
     * 未知字段返回 400 不做静默忽略；被禁止字段不出现，全部被禁止返回空对象。
     * 每次读取都按当前授权与当前清单实时鉴权，不使用幂等载荷缓存。
     */
    @Transactional(readOnly = true)
    public StructuredRecordReadResponse read(String subjectKey, Purpose purpose, String recordKey,
                                             List<String> requestedFields) {
        ConsentRepository.GrantRow latest = consentRepository.findLatestGrant(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());
        StructuredRecordRow row = structuredRecordRepository
                .findRecord(subjectKey, purpose, latest.epoch(), recordKey)
                .orElseThrow(() -> ApiException.notFound(CODE_RECORD_NOT_FOUND, "记录不存在"));

        Map<String, String> stored = readJsonMap(row.fieldsJson());
        List<String> selected = new ArrayList<>();
        if (requestedFields == null || requestedFields.isEmpty()) {
            selected.addAll(stored.keySet());
        } else {
            for (String name : requestedFields) {
                if (!stored.containsKey(name)) {
                    throw ApiException.badRequest(CODE_UNKNOWN_FIELD, "未知字段: " + name);
                }
                selected.add(name);
            }
        }

        Optional<RestrictionRow> restriction = currentRestriction(subjectKey, purpose, latest.epoch());
        Set<String> restricted = restriction
                .map(r -> new LinkedHashSet<>(readJsonList(r.restrictedFieldsJson())))
                .orElseGet(LinkedHashSet::new);
        int restrictionVersion = restriction.map(RestrictionRow::restrictionVersion).orElse(0);

        Map<String, String> projection = new LinkedHashMap<>();
        for (String name : selected) {
            if (!restricted.contains(name)) {
                projection.putIfAbsent(name, stored.get(name));
            }
        }
        return new StructuredRecordReadResponse(subjectKey, purpose, latest.epoch(), recordKey,
                restrictionVersion, projection);
    }

    /**
     * 替换字段级限制清单：仅当前有效代次可替换；expectedRestrictionVersion 必须等于当前版本
     * （无清单时为 0），成功后版本递增。禁止字段集合可为空。并发同版本替换只有一个成功，
     * 其余返回 409，不向调用方泄露唯一键异常。
     */
    @Transactional
    public FieldRestrictionResponse replaceRestriction(FieldRestrictionReplaceRequest request) {
        List<String> restricted = validateRestrictedFields(request.restrictedFields());
        String fingerprint = fingerprint(OP_RESTRICTION_REPLACE, request.subjectKey(),
                request.purpose().name(), request.expectedRestrictionVersion(), restricted);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            FieldRestrictionResponse snapshot =
                    readSnapshot(replayed.get().responseBody(), FieldRestrictionResponse.class);
            requireGrantActive(request.subjectKey(), request.purpose(), snapshot.epoch());
            return snapshot;
        }

        ConsentRepository.GrantRow latest = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权不存在"));
        requireGrantActive(latest.subjectKey(), latest.purpose(), latest.epoch());

        int currentVersion = currentRestriction(latest.subjectKey(), latest.purpose(), latest.epoch())
                .map(RestrictionRow::restrictionVersion).orElse(0);
        if (request.expectedRestrictionVersion() != currentVersion) {
            throw ApiException.conflict(CODE_RESTRICTION_VERSION_CONFLICT,
                    "期望限制版本 " + request.expectedRestrictionVersion() + " 与当前版本 " + currentVersion + " 不一致");
        }
        int newVersion = currentVersion + 1;
        try {
            structuredRecordRepository.insertRestriction(latest.subjectKey(), latest.purpose(), latest.epoch(),
                    newVersion, writeJson(restricted), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            // 并发同版本替换：本事务未获胜，按版本冲突处理
            throw ApiException.conflict(CODE_RESTRICTION_VERSION_CONFLICT, "限制清单已被并发替换");
        }

        FieldRestrictionResponse response = new FieldRestrictionResponse(latest.subjectKey(), latest.purpose(),
                latest.epoch(), newVersion, restricted);
        storeSuccess(request.requestId(), OP_RESTRICTION_REPLACE, fingerprint, response);
        return response;
    }

    private void requireGrantActive(String subjectKey, Purpose purpose, int epoch) {
        ConsentRepository.GrantRow grant = consentRepository.findGrant(subjectKey, purpose, epoch)
                .orElseThrow(() -> ApiException.notFound(CODE_GRANT_NOT_FOUND, "授权代次不存在"));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone(CODE_CONSENT_REVOKED, "授权已撤回");
        }
    }

    private Optional<RestrictionRow> currentRestriction(String subjectKey, Purpose purpose, int epoch) {
        return structuredRecordRepository.findLatestRestriction(subjectKey, purpose, epoch);
    }

    /**
     * 校验并规范化结构化字段：字段名为英文字母、数字、下划线（1-64 字符），每值最多 100 字符；
     * 返回按键名升序的规范化映射，保证存储与比较的一致性。
     */
    private Map<String, String> validateFields(Map<String, String> fields) {
        Map<String, String> canonical = new TreeMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String name = entry.getKey();
            if (name == null || !FIELD_NAME.matcher(name).matches()) {
                throw ApiException.badRequest(CODE_INVALID_FIELD,
                        "字段名只允许英文字母、数字、下划线，长度 1-64: " + name);
            }
            String value = entry.getValue();
            if (value == null || value.length() > MAX_VALUE_LENGTH) {
                throw ApiException.badRequest(CODE_INVALID_FIELD,
                        "字段值不能为空且最多 " + MAX_VALUE_LENGTH + " 字符: " + name);
            }
            canonical.put(name, value);
        }
        return canonical;
    }

    /**
     * 校验并规范化禁止字段集合：字段名规则同结构化字段；升序去重，允许为空。
     */
    private List<String> validateRestrictedFields(List<String> restrictedFields) {
        List<String> canonical = new ArrayList<>();
        for (String name : restrictedFields) {
            if (name == null || !FIELD_NAME.matcher(name).matches()) {
                throw ApiException.badRequest(CODE_INVALID_FIELD,
                        "禁止字段名只允许英文字母、数字、下划线，长度 1-64: " + name);
            }
            if (!canonical.contains(name)) {
                canonical.add(name);
            }
        }
        canonical.sort(String::compareTo);
        return canonical;
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
            idempotencyRepository.insert(requestId, operation, fingerprint, writeJson(response));
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
     * 结构化参数指纹：以 JSON 数组序列化操作、主体、用途、资源及全部参数和期望版本，
     * 结构化比较避免分隔符碰撞。
     */
    private String fingerprint(Object... parts) {
        return writeJson(List.of(parts));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private Map<String, String> readJsonMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("结构化字段反序列化失败", e);
        }
    }

    private List<String> readJsonList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("限制清单反序列化失败", e);
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
