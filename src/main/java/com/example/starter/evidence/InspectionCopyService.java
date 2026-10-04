package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CopyContentView;
import com.example.starter.evidence.dto.CopyView;
import com.example.starter.evidence.dto.IssueCopyRequest;
import com.example.starter.evidence.dto.RevokeCopyRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 阅检副本服务。签发、回收与交接/借出/封条异常登记互斥：一律先锁定证物行
 * （SELECT ... FOR UPDATE）再判定，检查后不可能插入漏管副本。
 * 权限检查（仅当前保管人）先于幂等重放；签发冻结摘要、水印、合成文本与有效期，
 * 重放签发只返回元数据，不返回文本。副本授权不改变保管权，也不绕过封条异常。
 */
@Service
public class InspectionCopyService {

    static final String OP_COPY_ISSUE = "COPY_ISSUE";
    static final String OP_COPY_REVOKE = "COPY_REVOKE";

    /**
     * 副本有效期上限：60 分钟。
     */
    static final long MAX_VALIDITY_MINUTES = 60;

    private final EvidenceRepository evidenceRepository;
    private final InspectionCopyRepository copyRepository;
    private final CommandLogRepository commandLogRepository;
    private final ObjectMapper objectMapper;
    private final EvidenceClock clock;

    public InspectionCopyService(EvidenceRepository evidenceRepository,
                                 InspectionCopyRepository copyRepository,
                                 CommandLogRepository commandLogRepository,
                                 ObjectMapper objectMapper,
                                 EvidenceClock clock) {
        this.evidenceRepository = evidenceRepository;
        this.copyRepository = copyRepository;
        this.commandLogRepository = commandLogRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 签发阅检副本：仅当前保管人；证物须 SEALED（封条正常、无借出、无待交接），
     * 否则 422。首次响应含合成文本，重放仅返回元数据。
     */
    @Transactional
    public StoredResponse issue(String actorId, String evidenceKey,
                                IssueCopyRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.unprocessable("证物未处于封存正常状态（存在借出、待交接或封条异常），禁止签发阅检副本: " + evidenceKey);
        }
        if (evidence.digest() == null || evidence.syntheticText() == null) {
            throw ApiException.unprocessable("证物未登记数字摘要或合成文本，禁止签发阅检副本: " + evidenceKey);
        }
        requireExpectedVersion(evidence, request.expectedVersion());
        LocalDateTime now = clock.nowUtc();
        LocalDateTime validFrom = request.validFrom() != null ? request.validFrom() : now;
        LocalDateTime validTo = request.validTo();
        if (!validTo.isAfter(validFrom)) {
            throw ApiException.badRequest("有效期终点须晚于起点");
        }
        if (validTo.isAfter(validFrom.plusMinutes(MAX_VALIDITY_MINUTES))) {
            throw ApiException.badRequest("副本有效期最长 " + MAX_VALIDITY_MINUTES + " 分钟");
        }
        if (!validTo.isAfter(now)) {
            throw ApiException.badRequest("有效期终点须晚于服务端当前时刻");
        }
        String copyId = "CPY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String watermark = "COPY[" + copyId + "]|REVIEWER[" + request.reviewerId() + "]";
        copyRepository.insert(copyId, evidenceKey, request.reviewerId(), evidence.digest(),
                watermark, evidence.syntheticText(), validFrom, validTo, now, LocalDateTime.now());
        InspectionCopy copy = copyRepository.findByCopyId(copyId).orElseThrow();
        // 命令日志只存元数据：重放签发不返回合成文本。
        commandLogRepository.insert(request.requestId(), actorId, OP_COPY_ISSUE, requestHash,
                201, toJson(toView(copy)), LocalDateTime.now());
        return new StoredResponse(201, toJson(toContentView(copy)));
    }

    /**
     * 读取副本内容：每次重新鉴权，仅阅检人本人且在有效区间内可读；
     * 越权 403，过期、已回收或封条异常 410。不返回其他副本或原件内部记录。
     */
    @Transactional(readOnly = true)
    public CopyContentView read(String actorId, String evidenceKey, String copyId) {
        InspectionCopy copy = copyRepository.findByCopyId(copyId)
                .orElseThrow(() -> ApiException.notFound("阅检副本不存在: " + copyId));
        if (!copy.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("阅检副本不属于该证物: " + copyId);
        }
        if (!copy.reviewerId().equals(actorId)) {
            throw ApiException.forbidden("仅阅检人本人可读取副本: " + actorId);
        }
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.gone("封条异常，全部副本读取已停止: " + evidenceKey);
        }
        if (copy.status() == InspectionCopyStatus.REVOKED) {
            throw ApiException.gone("副本已回收: " + copyId);
        }
        LocalDateTime now = clock.nowUtc();
        if (now.isBefore(copy.validFrom()) || !now.isBefore(copy.validTo())) {
            throw ApiException.gone("副本不在有效期内（未生效或已过期）: " + copyId);
        }
        return toContentView(copy);
    }

    /**
     * 回收副本：仅当前保管人；回收不删除签发历史。已回收副本再次回收（新请求键）返回 409。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String evidenceKey, String copyId,
                                 RevokeCopyRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.requestId(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        InspectionCopy copy = copyRepository.findByCopyId(copyId)
                .orElseThrow(() -> ApiException.notFound("阅检副本不存在: " + copyId));
        if (!copy.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("阅检副本不属于该证物: " + copyId);
        }
        requireExpectedVersion(evidence, request.expectedVersion());
        if (copy.status() == InspectionCopyStatus.REVOKED) {
            throw ApiException.conflict("副本已回收: " + copyId);
        }
        if (!copyRepository.revoke(copy.id(), clock.nowUtc())) {
            throw ApiException.conflict("副本已被并发回收: " + copyId);
        }
        InspectionCopy updated = copyRepository.findByCopyId(copyId).orElseThrow();
        return record(request.requestId(), OP_COPY_REVOKE, actorId, requestHash, 200, toView(updated));
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.forbidden("操作人不是当前保管人: " + actorId);
        }
    }

    private void requireExpectedVersion(Evidence evidence, Long expectedVersion) {
        if (expectedVersion != null && expectedVersion.longValue() != evidence.version()) {
            throw ApiException.conflict("期望版本不一致: 期望 " + expectedVersion
                    + "，当前 " + evidence.version());
        }
    }

    private Optional<StoredResponse> checkReplay(String requestId, String requestHash) {
        return commandLogRepository.findByKey(requestId).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + requestId);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String requestId, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(requestId, actorId, operation, requestHash, status, json,
                LocalDateTime.now());
        return new StoredResponse(status, json);
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private CopyView toView(InspectionCopy copy) {
        return new CopyView(copy.copyId(), copy.evidenceKey(), copy.reviewerId(), copy.digest(),
                copy.watermark(), copy.validFrom(), copy.validTo(), copy.status(),
                copy.issuedAt(), copy.revokedAt());
    }

    private CopyContentView toContentView(InspectionCopy copy) {
        return new CopyContentView(copy.copyId(), copy.evidenceKey(), copy.reviewerId(),
                copy.digest(), copy.watermark(), copy.validFrom(), copy.validTo(), copy.status(),
                copy.syntheticText());
    }
}