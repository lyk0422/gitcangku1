package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ApproveFreezeResumeRequest;
import com.example.starter.firmware.api.FreezeEventView;
import com.example.starter.firmware.api.FreezeHistoryResponse;
import com.example.starter.firmware.api.FreezeVersionRequest;
import com.example.starter.firmware.api.FreezeView;
import com.example.starter.firmware.api.ResumeFreezeRequest;
import com.example.starter.firmware.domain.FreezeEventType;
import com.example.starter.firmware.domain.FreezeRecord;
import com.example.starter.firmware.domain.FreezeStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.FreezeEventRepository;
import com.example.starter.firmware.repo.FreezeRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 目标固件版本正式发布冻结：冻结（记录创建人、原因与 UTC 生效时刻）、恢复申请、
 * 异于冻结创建人的操作人审批恢复。冻结期间任务拉取不再新建正式任务（409，不占分桶），
 * 已有任务回执与只读查询不受影响。
 *
 * <p>并发约定（统一加锁顺序：冻结行 F → 发布单行 R，避免死锁）：</p>
 * <ul>
 *   <li>冻结：插入 F 后在同事务锁定该版本全部未终结发布单行，与任务拉取按提交顺序决胜；</li>
 *   <li>拉取：先锁 F（存在时）再锁 R，锁内用非锁定读复查冻结状态后才允许建任务；</li>
 *   <li>申请/审批：只锁 F；拉取以 F 为第一道门，审批提交后新任务立即可建。</li>
 * </ul>
 */
@Service
public class FreezeService {

    private final FreezeRecordRepository freezeRecordRepository;
    private final FreezeEventRepository freezeEventRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public FreezeService(FreezeRecordRepository freezeRecordRepository,
                         FreezeEventRepository freezeEventRepository, ReleaseRepository releaseRepository,
                         IdempotencyService idempotency, Clock clock) {
        this.freezeRecordRepository = freezeRecordRepository;
        this.freezeEventRepository = freezeEventRepository;
        this.releaseRepository = releaseRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 创建版本冻结，立即生效；同版本已有生效冻结返回 409。
     * 冻结行落库后锁定该版本全部未终结发布单，保证先拿到发布单行锁的拉取先提交（任务可建），
     * 其后冻结提交，新拉取一律 409。
     */
    public FreezeView freeze(FreezeVersionRequest request) {
        String fingerprint = String.join("|", "freeze.create", request.version(),
                request.operator(), request.reason());
        return idempotency.execute(request.requestId(), "freeze.create", fingerprint, () -> {
            String now = Instant.now(clock).toString();
            long freezeId;
            try {
                freezeId = freezeRecordRepository.insert(request.version(), request.operator(),
                        request.reason(), now);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("FREEZE_ALREADY_ACTIVE",
                        "版本已存在生效冻结: " + request.version());
            }
            // 持有冻结行后锁发布单行：与并发拉取形成 F→R 的一致加锁顺序。
            releaseRepository.findOpenByToVersionForUpdate(request.version());
            freezeEventRepository.insert(freezeId, request.version(), FreezeEventType.FROZEN,
                    request.operator(), request.reason(), now);
            return FreezeView.of(findRecord(freezeId));
        }, FreezeView.class);
    }

    /**
     * 申请恢复：仅 FROZEN 可申请，记录申请人、原因与 UTC 时刻；不改变冻结生效状态。
     * 已存在恢复申请时返回 409；审批失败不影响已登记的申请内容。
     */
    public FreezeView requestResume(long freezeId, ResumeFreezeRequest request) {
        String fingerprint = String.join("|", "freeze.requestResume", String.valueOf(freezeId),
                request.operator(), request.reason());
        return idempotency.execute(request.requestId(), "freeze.requestResume", fingerprint, () -> {
            FreezeRecord record = lockRecord(freezeId);
            if (record.status() == FreezeStatus.RESUMED) {
                throw ApiException.conflict("FREEZE_ALREADY_RESUMED", "该冻结已审批恢复，不能重复申请");
            }
            if (record.resumeRequestedAtUtc() != null) {
                throw ApiException.conflict("RESUME_ALREADY_REQUESTED",
                        "该冻结已存在恢复申请，等待审批");
            }
            String now = Instant.now(clock).toString();
            freezeRecordRepository.markResumeRequested(freezeId, request.operator(), request.reason(), now);
            freezeEventRepository.insert(freezeId, record.version(), FreezeEventType.RESUME_REQUESTED,
                    request.operator(), request.reason(), now);
            return FreezeView.of(findRecord(freezeId));
        }, FreezeView.class);
    }

    /**
     * 审批恢复：必须存在恢复申请，且审批人不同于冻结创建人；任一不满足返回 422，
     * 回滚事务，冻结状态、冻结人原因时刻与全部审计记录保持不变。
     */
    public FreezeView approveResume(long freezeId, ApproveFreezeResumeRequest request) {
        String fingerprint = String.join("|", "freeze.approve", String.valueOf(freezeId),
                request.approver());
        return idempotency.execute(request.requestId(), "freeze.approve", fingerprint, () -> {
            FreezeRecord record = lockRecord(freezeId);
            if (record.status() == FreezeStatus.RESUMED) {
                throw ApiException.conflict("FREEZE_ALREADY_RESUMED", "该冻结已审批恢复，不能重复审批");
            }
            if (record.resumeRequestedAtUtc() == null) {
                throw ApiException.unprocessable("RESUME_NOT_REQUESTED",
                        "尚无恢复申请，不能审批恢复");
            }
            if (record.createdBy().equals(request.approver())) {
                throw ApiException.unprocessable("SELF_APPROVAL_FORBIDDEN",
                        "恢复审批人必须不同于冻结创建人: " + record.createdBy());
            }
            String now = Instant.now(clock).toString();
            freezeRecordRepository.markResumed(freezeId, request.approver(), now);
            freezeEventRepository.insert(freezeId, record.version(), FreezeEventType.RESUME_APPROVED,
                    request.approver(), record.resumeReason(), now);
            return FreezeView.of(findRecord(freezeId));
        }, FreezeView.class);
    }

    /**
     * 版本冻结全景（只读）：当前生效冻结（无则 null）、全部冻结区间与只增审计流水。
     */
    public FreezeHistoryResponse history(String version) {
        var records = freezeRecordRepository.findByVersion(version).stream()
                .map(FreezeView::of).toList();
        var events = freezeEventRepository.findByVersion(version).stream()
                .map(FreezeEventView::of).toList();
        FreezeView current = freezeRecordRepository.findActiveByVersion(version)
                .map(FreezeView::of).orElse(null);
        return new FreezeHistoryResponse(version, current, records, events);
    }

    /**
     * 当前生效冻结（只读）；不存在返回 404。
     */
    public FreezeView active(String version) {
        return freezeRecordRepository.findActiveByVersion(version)
                .map(FreezeView::of)
                .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND",
                        "版本无生效冻结: " + version));
    }

    /**
     * 任务拉取在发布单行锁内复查冻结状态（非锁定读，READ_COMMITTED 下读最近提交）。
     */
    public boolean isFrozen(String version) {
        return freezeRecordRepository.findActiveByVersion(version).isPresent();
    }

    /**
     * 任务拉取进入发布单行锁前先锁冻结行（存在时），维持 F→R 加锁顺序。
     */
    public void lockActiveFreezeIfPresent(String version) {
        freezeRecordRepository.findActiveByVersionForUpdate(version);
    }

    public FreezeRecord findRecord(long freezeId) {
        return freezeRecordRepository.findById(freezeId)
                .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结记录不存在: " + freezeId));
    }

    private FreezeRecord lockRecord(long freezeId) {
        return freezeRecordRepository.findByIdForUpdate(freezeId)
                .orElseThrow(() -> ApiException.notFound("FREEZE_NOT_FOUND", "冻结记录不存在: " + freezeId));
    }
}
