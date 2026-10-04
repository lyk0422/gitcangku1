package com.example.starter.evidence;

import com.example.starter.error.ApiException;
import com.example.starter.evidence.dto.CommandRequest;
import com.example.starter.evidence.dto.InspectionCopyContentView;
import com.example.starter.evidence.dto.InspectionCopyIssueRequest;
import com.example.starter.evidence.dto.InspectionCopyView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 阅检副本服务：证物合成数字副本的签发、读取与回收。
 * 副本授权不改变原件保管权；签发与交接、借出、异常登记通过证物行锁互斥判定，
 * 检查与写入在同一事务内完成，不能在检查之后插入漏管副本。
 * 签发冻结摘要、水印与有效区间；签发与重放签发只返回元数据，不返回合成文本。
 */
@Service
public class InspectionCopyService {

    static final String OP_COPY_ISSUE = "COPY_ISSUE";
    static final String OP_COPY_REVOKE = "COPY_REVOKE";

    /**
     * 副本最长有效期：1 小时。
     */
    static final long MAX_COPY_HOURS = 1;

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
     * 签发阅检副本：仅当前保管人，原件须已封存且封条正常、没有借出或待交接（即 SEALED），
     * 否则 422。有效区间 [签发时刻, validUntil) 为 UTC 左闭右开，最长一小时。
     * 权限检查先于幂等重放；失败不占幂等键。
     */
    @Transactional
    public StoredResponse issue(String actorId, String evidenceKey,
                                InspectionCopyIssueRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        if (evidence.status() != EvidenceStatus.SEALED) {
            throw ApiException.unprocessable(
                    "原件未处于已封存且封条正常状态，禁止签发阅检副本: " + evidenceKey);
        }
        LocalDateTime nowUtc = clock.nowUtc();
        if (!request.validUntil().isAfter(nowUtc)) {
            throw ApiException.badRequest("失效时刻须晚于服务端当前时刻");
        }
        if (request.validUntil().isAfter(nowUtc.plusHours(MAX_COPY_HOURS))) {
            throw ApiException.badRequest("副本有效期不得超过 " + MAX_COPY_HOURS + " 小时");
        }
        if (copyRepository.findByCopyKey(request.copyKey()).isPresent()) {
            throw ApiException.conflict("副本键已存在: " + request.copyKey());
        }
        String watermark = "INSPECTION-COPY/" + request.copyKey() + "/" + request.reviewerId();
        copyRepository.insert(request.copyKey(), evidenceKey, request.reviewerId(),
                evidence.digest(), watermark, nowUtc, request.validUntil(), LocalDateTime.now());
        InspectionCopy copy = copyRepository.findByCopyKey(request.copyKey()).orElseThrow();
        return record(request.commandKey(), OP_COPY_ISSUE, actorId, requestHash, 201, toView(copy));
    }

    /**
     * 读取副本内容：仅阅检人本人在有效期内可读，每次读取重新鉴权。
     * 越权 403；封条异常后 422；已回收或自然到期 410。只返回本副本，不返回原件内部记录。
     */
    @Transactional(readOnly = true)
    public InspectionCopyContentView read(String actorId, String evidenceKey, String copyKey) {
        InspectionCopy copy = copyRepository.findByCopyKey(copyKey)
                .orElseThrow(() -> ApiException.notFound("阅检副本不存在: " + copyKey));
        if (!copy.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("阅检副本不属于该证物: " + copyKey);
        }
        if (!copy.reviewerId().equals(actorId)) {
            throw ApiException.forbidden("仅阅检人本人可读取副本: " + actorId);
        }
        Evidence evidence = evidenceRepository.findByKey(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
        if (evidence.status() == EvidenceStatus.SEAL_BROKEN) {
            throw ApiException.unprocessable("封条异常，全部副本停止读取: " + evidenceKey);
        }
        if (copy.status() == CopyStatus.REVOKED) {
            throw ApiException.gone("阅检副本已回收: " + copyKey);
        }
        if (!clock.nowUtc().isBefore(copy.validUntil())) {
            throw ApiException.gone("阅检副本已过有效期: " + copyKey);
        }
        return new InspectionCopyContentView(copy.copyKey(), copy.evidenceKey(), copy.reviewerId(),
                copy.digest(), copy.watermark(), copy.validFrom(), copy.validUntil(),
                evidence.syntheticText());
    }

    /**
     * 回收副本：仅当前保管人，回收不删除签发历史。
     */
    @Transactional
    public StoredResponse revoke(String actorId, String evidenceKey, String copyKey,
                                 CommandRequest request, String requestHash) {
        Evidence evidence = lockEvidence(evidenceKey);
        requireCustodian(evidence, actorId);
        Optional<StoredResponse> replay = checkReplay(request.commandKey(), requestHash);
        if (replay.isPresent()) {
            return replay.get();
        }
        InspectionCopy copy = copyRepository.findByCopyKey(copyKey)
                .orElseThrow(() -> ApiException.notFound("阅检副本不存在: " + copyKey));
        if (!copy.evidenceKey().equals(evidenceKey)) {
            throw ApiException.notFound("阅检副本不属于该证物: " + copyKey);
        }
        if (copy.status() == CopyStatus.REVOKED) {
            throw ApiException.conflict("阅检副本已回收: " + copyKey);
        }
        if (!copyRepository.revoke(copy.id(), clock.nowUtc())) {
            throw ApiException.conflict("阅检副本已被并发回收: " + copyKey);
        }
        InspectionCopy revoked = copyRepository.findByCopyKey(copyKey).orElseThrow();
        return record(request.commandKey(), OP_COPY_REVOKE, actorId, requestHash, 200, toView(revoked));
    }

    private Evidence lockEvidence(String evidenceKey) {
        return evidenceRepository.findByKeyForUpdate(evidenceKey)
                .orElseThrow(() -> ApiException.notFound("证物不存在: " + evidenceKey));
    }

    private void requireCustodian(Evidence evidence, String actorId) {
        if (!evidence.custodianId().equals(actorId)) {
            throw ApiException.conflict("操作人不是当前保管人: " + actorId);
        }
    }

    private Optional<StoredResponse> checkReplay(String commandKey, String requestHash) {
        return commandLogRepository.findByKey(commandKey).map(log -> {
            if (!log.requestHash().equals(requestHash)) {
                throw ApiException.conflict("幂等键已被不同参数使用: " + commandKey);
            }
            return new StoredResponse(log.responseStatus(), log.responseBody());
        });
    }

    private StoredResponse record(String commandKey, String operation, String actorId,
                                  String requestHash, int status, Object body) {
        String json = toJson(body);
        commandLogRepository.insert(commandKey, actorId, operation, requestHash, status, json,
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

    private InspectionCopyView toView(InspectionCopy copy) {
        return new InspectionCopyView(copy.copyKey(), copy.evidenceKey(), copy.reviewerId(),
                copy.digest(), copy.watermark(), copy.validFrom(), copy.validUntil(),
                copy.status(), copy.revokedAt(), copy.createdAt());
    }
}