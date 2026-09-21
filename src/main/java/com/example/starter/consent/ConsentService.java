package com.example.starter.consent;

import com.example.starter.web.ApiException;
import com.example.starter.web.dto.GrantResponse;
import com.example.starter.web.dto.RecordResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 授权与记录业务服务。
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>授权按主体+用途管理，epoch 从 1 递增；有效时重复授权返回原 epoch，撤回后重新授权生成下一代。</li>
 *   <li>仅当前有效 epoch 可写入与查询；撤回后旧代查询返回 410、写入被拒绝，数据不物理删除。</li>
 *   <li>写入与撤回通过授权行锁（FOR UPDATE）按事务提交顺序串行生效。</li>
 *   <li>幂等记录与业务结果同事务提交；失败请求回滚，不占用 requestId。</li>
 * </ul>
 */
@Service
public class ConsentService {

    private final ConsentGrantRepository grantRepository;
    private final ConsentRecordRepository recordRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;

    public ConsentService(ConsentGrantRepository grantRepository,
                          ConsentRecordRepository recordRepository,
                          IdempotencyRepository idempotencyRepository,
                          ObjectMapper objectMapper) {
        this.grantRepository = grantRepository;
        this.recordRepository = recordRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 授权：首次生成第 1 代；当前仍有效时返回原 epoch；撤回后重新授权生成下一代。
     */
    @Transactional
    public GrantResponse grant(String requestId, String subjectKey, Purpose purpose) {
        return idempotent(requestId, "GRANT", subjectKey + "|" + purpose.name(), GrantResponse.class, () -> {
            var latest = grantRepository.findLatestForUpdate(subjectKey, purpose);
            if (latest.isPresent() && latest.get().status() == GrantStatus.ACTIVE) {
                return toResponse(latest.get());
            }
            int nextEpoch = latest.map(ConsentGrant::epoch).orElse(0) + 1;
            try {
                return toResponse(grantRepository.insert(subjectKey, purpose, nextEpoch, requestId));
            } catch (DuplicateKeyException concurrent) {
                // 并发首次授权：唯一约束冲突后以先提交者为准。
                return toResponse(grantRepository.findLatest(subjectKey, purpose).orElseThrow());
            }
        });
    }

    /**
     * 撤回指定代次：仅允许 ACTIVE -> REVOKED；代次不存在返回 404，已撤回返回 409。
     */
    @Transactional
    public GrantResponse revoke(String requestId, String subjectKey, Purpose purpose, int epoch) {
        return idempotent(requestId, "REVOKE", subjectKey + "|" + purpose.name() + "|" + epoch,
                GrantResponse.class, () -> {
                    var grant = grantRepository.findByEpochForUpdate(subjectKey, purpose, epoch)
                            .orElseThrow(() -> ApiException.notFound("GRANT_NOT_FOUND",
                                    "授权代次不存在: " + subjectKey + "/" + purpose.name() + "/" + epoch));
                    if (grant.status() == GrantStatus.REVOKED) {
                        throw ApiException.conflict("ALREADY_REVOKED", "该代授权已撤回，不允许重复撤回");
                    }
                    grantRepository.markRevoked(grant.id());
                    return new GrantResponse(grant.subjectKey(), grant.purpose().name(),
                            grant.epoch(), GrantStatus.REVOKED.name());
                });
    }

    /**
     * 写入记录到当前有效代次。同代同 key 同 payload 返回原记录；同 key 不同 payload 返回 409；
     * 授权已撤回返回 410（即使重放原写入请求）；从未授权返回 404。
     *
     * <p>授权状态校验先于幂等重放：撤回后重放已成功过的写入请求同样被拒绝，
     * 不允许用幂等结果绕过授权状态。</p>
     */
    @Transactional
    public RecordResponse write(String requestId, String subjectKey, Purpose purpose,
                                String recordKey, String payload) {
        String fingerprint = subjectKey + "|" + purpose.name() + "|" + recordKey + "|" + payload;
        var grant = grantRepository.findActiveForUpdate(subjectKey, purpose)
                .orElseThrow(() -> noActiveGrant(subjectKey, purpose));
        return idempotent(requestId, "WRITE", fingerprint, RecordResponse.class, () -> {
            var existing = recordRepository.find(subjectKey, purpose, grant.epoch(), recordKey);
            if (existing.isPresent()) {
                if (!existing.get().payload().equals(payload)) {
                    throw ApiException.conflict("PAYLOAD_CONFLICT",
                            "同一代内相同 recordKey 已存在不同 payload 的记录");
                }
                return toResponse(existing.get());
            }
            return toResponse(recordRepository.insert(
                    subjectKey, purpose, grant.epoch(), recordKey, payload, requestId));
        });
    }

    /**
     * 查询当前有效代次下的记录。授权已撤回返回 410；从未授权或记录不存在返回 404。
     */
    @Transactional(readOnly = true)
    public RecordResponse read(String subjectKey, Purpose purpose, String recordKey) {
        var grant = grantRepository.findLatest(subjectKey, purpose)
                .orElseThrow(() -> ApiException.notFound("GRANT_NOT_FOUND",
                        "授权不存在: " + subjectKey + "/" + purpose.name()));
        if (grant.status() == GrantStatus.REVOKED) {
            throw ApiException.gone("授权已撤回，旧代数据不可见");
        }
        return recordRepository.find(subjectKey, purpose, grant.epoch(), recordKey)
                .map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("RECORD_NOT_FOUND",
                        "当前有效代次下记录不存在: " + recordKey));
    }

    /**
     * 无有效授权时区分 404（从未授权）与 410（已撤回）。
     */
    private ApiException noActiveGrant(String subjectKey, Purpose purpose) {
        return grantRepository.findLatest(subjectKey, purpose)
                .map(latest -> ApiException.gone("授权已撤回，写入被拒绝"))
                .orElseGet(() -> ApiException.notFound("GRANT_NOT_FOUND",
                        "授权不存在: " + subjectKey + "/" + purpose.name()));
    }

    /**
     * 幂等执行：同一 requestId 相同参数返回原结果，参数不同返回 409；
     * 成功结果与幂等记录同事务保存，失败回滚不占用 requestId。
     */
    private <T> T idempotent(String requestId, String type, String fingerprint,
                             Class<T> responseType, Supplier<T> action) {
        String fullFingerprint = type + "|" + fingerprint;
        var existing = idempotencyRepository.find(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fullFingerprint, responseType);
        }
        T response = action.get();
        boolean saved = idempotencyRepository.tryInsert(new IdempotentRequest(
                requestId, type, fullFingerprint, 200, toJson(response), null));
        if (!saved) {
            // 并发相同 requestId：以先提交者为准。
            var concurrent = idempotencyRepository.find(requestId).orElseThrow();
            return replay(concurrent, fullFingerprint, responseType);
        }
        return response;
    }

    /**
     * 重放已成功的幂等请求；参数指纹不一致时返回 409。
     */
    private <T> T replay(IdempotentRequest stored, String fingerprint, Class<T> responseType) {
        if (!stored.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "同一 requestId 请求参数发生变化: " + stored.requestId());
        }
        try {
            return objectMapper.readValue(stored.responseBody(), responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private GrantResponse toResponse(ConsentGrant grant) {
        return new GrantResponse(grant.subjectKey(), grant.purpose().name(),
                grant.epoch(), grant.status().name());
    }

    private RecordResponse toResponse(ConsentRecord record) {
        return new RecordResponse(record.subjectKey(), record.purpose().name(),
                record.epoch(), record.recordKey(), record.payload());
    }
}
