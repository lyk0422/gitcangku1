package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.LabelTargetRequest;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.api.TargetSnapshotView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.LabelExpression;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TargetSnapshot;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TargetSnapshotRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 发布单生命周期：创建（版本从1开始）、目标快照一次性解析固化、扩量（版本校验+只增不减）、
 * 失败率自动暂停、人工恢复（版本加一并开启新监控轮次）、取消（未终结任务转 CANCELLED）。
 * 快照解析在单事务内完成：字典 meta 行锁 → 同型号同来源版本候选设备行锁 → 表达式过滤 →
 * 发布单与快照原子落库；快照为空整体 422 回滚，不落发布单、不占幂等键。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final TargetSnapshotRepository targetSnapshotRepository;
    private final DeviceRepository deviceRepository;
    private final LabelDictService labelDictService;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          TargetSnapshotRepository targetSnapshotRepository,
                          DeviceRepository deviceRepository,
                          LabelDictService labelDictService,
                          IdempotencyService idempotency, ObjectMapper objectMapper, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.targetSnapshotRepository = targetSnapshotRepository;
        this.deviceRepository = deviceRepository;
        this.labelDictService = labelDictService;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 规范化标签表达式；非法（null、三组全空、重复标签、包含与排除冲突）统一 422。
     */
    public static LabelExpression parseExpression(LabelTargetRequest target) {
        if (target == null) {
            throw ApiException.unprocessable("EMPTY_LABEL_CONDITION",
                    "标签表达式为空：必须包含、不得包含、至少包含一项三类条件至少提供一类");
        }
        return LabelExpression.of(target.mustInclude(), target.mustExclude(), target.includeAny());
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        // 表达式先于事务规范化校验：422 失败不占幂等键
        LabelExpression expression = parseExpression(request.target());
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        String fingerprint = Fingerprint.of(objectMapper, "release.create", request.operator(),
                Fingerprint.payload("model", request.model(), "fromVersion", request.fromVersion(),
                        "toVersion", request.toVersion(), "ratio", request.ratio(),
                        "sampleFloor", sampleFloor, "failureThresholdPercent", threshold,
                        "mustInclude", expression.mustInclude(), "mustExclude", expression.mustExclude(),
                        "includeAny", expression.includeAny()));
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            // 先锁字典版本行，与标签修订串行；再锁候选设备行，读取一致的标签集合
            long dictVersion = labelDictService.lockAndCurrentVersion();
            String resolvedAtUtc = Instant.now(clock).toString();
            List<Device> candidates =
                    deviceRepository.findCandidatesForUpdate(request.model(), request.fromVersion());
            List<String> targetDevices = candidates.stream()
                    .filter(device -> expression.matches(device.labels()))
                    .map(Device::deviceId)
                    .sorted()
                    .toList();
            if (targetDevices.isEmpty()) {
                // 目标快照为空：回滚整事务，不留发布单与半成品，不占幂等键
                throw ApiException.unprocessable("TARGET_SNAPSHOT_EMPTY",
                        "目标快照为空：型号 " + request.model() + "、来源版本 " + request.fromVersion()
                                + " 下没有设备满足标签表达式（要求至少1台，实际0台，差额1台）");
            }
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold,
                        dictVersion, targetDevices.size(), resolvedAtUtc);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在未终结发布单: " + request.model());
            }
            TargetSnapshot snapshot = new TargetSnapshot(id, expression, dictVersion, targetDevices, resolvedAtUtc);
            targetSnapshotRepository.insert(snapshot);
            for (String deviceId : targetDevices) {
                targetSnapshotRepository.insertMember(id, deviceId);
            }
            return buildView(findOrder(id));
        }, ReleaseView.class);
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {
        String fingerprint = Fingerprint.of(objectMapper, "release.expand", null,
                Fingerprint.payload("releaseId", releaseId, "expectedVersion", request.expectedVersion(),
                        "ratio", request.ratio()));
        return idempotency.execute(request.requestId(), "release.expand", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能扩量");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            if (request.ratio() < order.ratio()) {
                throw ApiException.conflict("RATIO_DECREASE", "投放比例只增不减，当前: " + order.ratio());
            }
            releaseRepository.expand(releaseId, request.expectedVersion(), request.ratio());
            return buildView(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复为 ACTIVE；版本加一、开启新监控轮次、本轮统计清零。
     * 与取消并发时按行锁提交顺序决定唯一结果：取消先提交则恢复 409。
     */
    public ReleaseView resume(long releaseId, ResumeReleaseRequest request) {
        String fingerprint = Fingerprint.of(objectMapper, "release.resume", null,
                Fingerprint.payload("releaseId", releaseId, "expectedVersion", request.expectedVersion(),
                        "reason", request.reason()));
        return idempotency.execute(request.requestId(), "release.resume", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能恢复");
            }
            if (order.status() != ReleaseStatus.PAUSED) {
                throw ApiException.conflict("RELEASE_NOT_PAUSED",
                        "发布单状态为 " + order.status() + "，仅 PAUSED 可恢复");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }
            releaseRepository.resume(releaseId, request.expectedVersion());
            ReleaseOrder resumed = findOrder(releaseId);
            resumeRecordRepository.insert(releaseId, resumed.monitorRound(), request.reason(),
                    Instant.now(clock).toString());
            return buildView(resumed);
        }, ReleaseView.class);
    }

    public ReleaseView cancel(long releaseId, String requestId) {
        String fingerprint = Fingerprint.of(objectMapper, "release.cancel", null,
                Fingerprint.payload("releaseId", releaseId));
        return idempotency.execute(requestId, "release.cancel", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED) {
                releaseRepository.cancel(releaseId);
                taskRepository.cancelPendingByRelease(releaseId);
            }
            return buildView(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 当前监控轮次统计（只读，不触发状态变化）。
     */
    public MonitorView monitor(long releaseId) {
        return MonitorView.of(findOrder(releaseId));
    }

    /**
     * 暂停/恢复历史（只读，不触发状态变化）。
     */
    public ReleaseHistoryResponse history(long releaseId) {
        findOrder(releaseId);
        var pauses = pauseRecordRepository.findByRelease(releaseId).stream()
                .map(PauseRecordView::of).toList();
        var resumes = resumeRecordRepository.findByRelease(releaseId).stream()
                .map(ResumeRecordView::of).toList();
        return new ReleaseHistoryResponse(releaseId, pauses, resumes);
    }

    /**
     * 目标快照诊断查询（只读）：固化表达式、字典版本、解析时刻与规范排序成员。
     */
    public TargetSnapshotView targetSnapshot(long releaseId) {
        findOrder(releaseId);
        return TargetSnapshotView.of(targetSnapshotRepository.findByRelease(releaseId)
                .orElseThrow(() -> new IllegalStateException("发布单目标快照缺失: " + releaseId)));
    }

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }

    private ReleaseView buildView(ReleaseOrder order) {
        TargetSnapshot snapshot = targetSnapshotRepository.findByRelease(order.id())
                .orElseThrow(() -> new IllegalStateException("发布单目标快照缺失: " + order.id()));
        return ReleaseView.of(order, TargetSnapshotView.of(snapshot));
    }
}
