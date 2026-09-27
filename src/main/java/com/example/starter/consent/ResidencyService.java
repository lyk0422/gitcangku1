package com.example.starter.consent;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.consent.IdempotencyRepository.IdempotencyRow;
import com.example.starter.consent.dto.GrantResidencyView;
import com.example.starter.consent.dto.MigrationRequest;
import com.example.starter.consent.dto.MigrationResponse;
import com.example.starter.consent.dto.MigrationView;
import com.example.starter.consent.dto.RegionRegisterRequest;
import com.example.starter.consent.dto.RegionResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 驻留域服务：区域登记、驻留迁移与诊断查询。
 *
 * <p>迁移规则：先校验完整最终状态（全部记录存在、未清除、区域一致、目标区域已登记），
 * 再在一个事务中创建新代次、原子复制明确列出的记录并撤回源代次；
 * 任一校验失败整次 422，源和目标均不留下部分副本。
 */
@Service
public class ResidencyService {

    static final String CODE_REGION_ALREADY_REGISTERED = "REGION_ALREADY_REGISTERED";
    static final String CODE_REGION_NOT_REGISTERED = "REGION_NOT_REGISTERED";
    static final String CODE_REQUEST_ID_CONFLICT = "REQUEST_ID_CONFLICT";
    static final String CODE_MIGRATION_GRANT_NOT_FOUND = "MIGRATION_GRANT_NOT_FOUND";
    static final String CODE_MIGRATION_GRANT_NOT_ACTIVE = "MIGRATION_GRANT_NOT_ACTIVE";
    static final String CODE_MIGRATION_SAME_REGION = "MIGRATION_SAME_REGION";
    static final String CODE_MIGRATION_RECORD_NOT_FOUND = "MIGRATION_RECORD_NOT_FOUND";
    static final String CODE_MIGRATION_RECORD_ERASED = "MIGRATION_RECORD_ERASED";
    static final String CODE_MIGRATION_REGION_MISMATCH = "MIGRATION_REGION_MISMATCH";
    static final String CODE_MIGRATION_CONFLICT = "MIGRATION_CONFLICT";

    private static final String OP_REGION_REGISTER = "REGION_REGISTER";
    private static final String OP_MIGRATE = "MIGRATE";

    private final ConsentRepository consentRepository;
    private final ResidencyRepository residencyRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ResidencyService(ConsentRepository consentRepository,
                            ResidencyRepository residencyRepository,
                            IdempotencyRepository idempotencyRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
        this.consentRepository = consentRepository;
        this.residencyRepository = residencyRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 区域登记：区域代码必须预先登记才能用于授权与迁移；重复登记返回 409。
     */
    @Transactional
    public RegionResponse registerRegion(RegionRegisterRequest request) {
        String fingerprint = OP_REGION_REGISTER + "|" + request.regionCode();
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), RegionResponse.class);
        }

        Instant createdAt = Instant.now(clock);
        try {
            residencyRepository.insertRegion(request.regionCode(), request.requestId(), createdAt);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict(CODE_REGION_ALREADY_REGISTERED,
                    "驻留区域已登记: " + request.regionCode());
        }
        RegionResponse response = new RegionResponse(request.regionCode(), createdAt);
        storeSuccess(request.requestId(), OP_REGION_REGISTER, fingerprint, response);
        return response;
    }

    /**
     * 已登记区域列表，只读。
     */
    @Transactional(readOnly = true)
    public List<RegionResponse> listRegions() {
        return residencyRepository.findAllRegions().stream()
                .map(row -> new RegionResponse(row.regionCode(), row.createdAt()))
                .toList();
    }

    /**
     * 驻留迁移：创建新授权代次并把明确列出的记录原子复制到目标区域，源代次同时撤回。
     */
    @Transactional
    public MigrationResponse migrate(MigrationRequest request) {
        // 记录键去重并保持请求顺序，保证指纹与响应稳定
        Set<String> recordKeys = new LinkedHashSet<>(request.recordKeys());
        String fingerprint = OP_MIGRATE + "|" + request.subjectKey() + "|" + request.purpose()
                + "|" + request.targetRegionCode() + "|" + String.join(",", recordKeys);
        Optional<IdempotencyRow> replayed = checkReplay(request.requestId(), fingerprint);
        if (replayed.isPresent()) {
            return readSnapshot(replayed.get().responseBody(), MigrationResponse.class);
        }

        if (!residencyRepository.regionExists(request.targetRegionCode())) {
            throw ApiException.unprocessable(CODE_REGION_NOT_REGISTERED,
                    "目标驻留区域未预先登记: " + request.targetRegionCode());
        }

        ConsentRepository.GrantRow source = consentRepository
                .findLatestGrant(request.subjectKey(), request.purpose())
                .orElseThrow(() -> ApiException.unprocessable(CODE_MIGRATION_GRANT_NOT_FOUND,
                        "迁移集合包含无授权对象: " + request.subjectKey() + "/" + request.purpose()));
        if (source.status() == GrantStatus.REVOKED) {
            throw ApiException.unprocessable(CODE_MIGRATION_GRANT_NOT_ACTIVE,
                    "源授权代次已撤回，不可迁移: epoch=" + source.epoch());
        }
        if (Objects.equals(source.regionCode(), request.targetRegionCode())) {
            throw ApiException.unprocessable(CODE_MIGRATION_SAME_REGION,
                    "目标区域与源区域相同，无需迁移: " + request.targetRegionCode());
        }

        // 先校验完整最终状态：全部记录存在、未清除、区域一致
        List<ConsentRepository.RecordRow> records = recordKeys.stream()
                .map(recordKey -> validateMigratable(source, recordKey, recordKeys.size()))
                .toList();

        // 再在一个事务中提交：新代次 + 原子复制 + 源代次撤回
        int targetEpoch = source.epoch() + 1;
        try {
            consentRepository.insertGrant(request.subjectKey(), request.purpose(), targetEpoch,
                    request.targetRegionCode(), request.requestId());
        } catch (DuplicateKeyException concurrent) {
            throw ApiException.conflict(CODE_MIGRATION_CONFLICT,
                    "并发迁移或授权已创建目标代次: epoch=" + targetEpoch);
        }
        for (ConsentRepository.RecordRow record : records) {
            consentRepository.insertRecord(record.subjectKey(), record.purpose(), targetEpoch,
                    record.recordKey(), record.payload(), request.targetRegionCode(), request.requestId());
        }
        Instant migratedAt = Instant.now(clock);
        boolean revoked = consentRepository.revokeGrant(
                request.subjectKey(), request.purpose(), source.epoch(), migratedAt);
        if (!revoked) {
            throw ApiException.conflict(CODE_MIGRATION_CONFLICT,
                    "源授权代次已被并发撤回: epoch=" + source.epoch());
        }

        residencyRepository.insertMigration(request.requestId(), request.subjectKey(), request.purpose(),
                source.epoch(), targetEpoch, source.regionCode(), request.targetRegionCode(),
                recordKeys.size(), records.size(), migratedAt);

        MigrationResponse response = new MigrationResponse(request.subjectKey(), request.purpose(),
                source.epoch(), targetEpoch, source.regionCode(), request.targetRegionCode(),
                recordKeys.size(), records.size(), migratedAt);
        storeSuccess(request.requestId(), OP_MIGRATE, fingerprint, response);
        return response;
    }

    /**
     * 授权代次驻留诊断视图，只读。
     */
    @Transactional(readOnly = true)
    public List<GrantResidencyView> grantDiagnostics(String subjectKey, Purpose purpose) {
        return consentRepository.findGrantViews(subjectKey, purpose);
    }

    /**
     * 迁移历史，只读。
     */
    @Transactional(readOnly = true)
    public List<MigrationView> migrationHistory(String subjectKey, Purpose purpose) {
        return residencyRepository.findMigrations(subjectKey, purpose);
    }

    /**
     * 校验单条记录可迁移：存在、未清除、区域与源代次一致；失败整次 422 且可区分原因。
     */
    private ConsentRepository.RecordRow validateMigratable(
            ConsentRepository.GrantRow source, String recordKey, int requestedCount) {
        Optional<ConsentRepository.RecordRow> current = consentRepository.findRecord(
                source.subjectKey(), source.purpose(), source.epoch(), recordKey);
        if (current.isEmpty()) {
            // 记录键存在于其他代次：属于不同区域对象；否则属于无授权（当前代次无此记录）对象
            List<ConsentRepository.RecordRow> anyEpoch = consentRepository.findRecordsAnyEpoch(
                    source.subjectKey(), source.purpose(), recordKey);
            if (!anyEpoch.isEmpty()) {
                throw ApiException.unprocessable(CODE_MIGRATION_REGION_MISMATCH,
                        "迁移集合包含不同区域对象: " + recordKey + " 属于代次 "
                                + anyEpoch.get(0).epoch() + "（区域 " + anyEpoch.get(0).regionCode()
                                + "），源代次 " + source.epoch() + "（区域 " + source.regionCode() + "）");
            }
            throw ApiException.unprocessable(CODE_MIGRATION_RECORD_NOT_FOUND,
                    "迁移集合包含无授权对象: " + recordKey + " 在当前代次 " + source.epoch()
                            + " 不存在（要求迁移 " + requestedCount +  " 条）");
        }
        ConsentRepository.RecordRow record = current.get();
        if (record.erasedAt() != null) {
            throw ApiException.unprocessable(CODE_MIGRATION_RECORD_ERASED,
                    "迁移集合包含已清除对象: " + recordKey + "（清除时间 " + record.erasedAt() + "）");
        }
        if (!Objects.equals(record.regionCode(), source.regionCode())) {
            throw ApiException.unprocessable(CODE_MIGRATION_REGION_MISMATCH,
                    "迁移集合包含不同区域对象: " + recordKey + " 区域 " + record.regionCode()
                            + " 与源代次区域 " + source.regionCode() + " 不一致");
        }
        return record;
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
