package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.ManifestView;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.api.ShardInput;
import com.example.starter.firmware.api.ShardView;
import com.example.starter.firmware.api.UpdateManifestRequest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ReleaseShardRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TaskAttemptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 发布单生命周期：创建（版本从1开始，可同时登记规范分片清单）、摘要锁定后不可修改、
 * 扩量（版本校验+只增不减）、失败率自动暂停、人工恢复（版本加一并开启新监控轮次）、
 * 取消（未安装任务转 CANCELLED）。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final ReleaseShardRepository releaseShardRepository;
    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final IdempotencyService idempotency;
    private final ManifestValidator manifestValidator;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, ReleaseShardRepository releaseShardRepository,
                          TaskRepository taskRepository, TaskAttemptRepository taskAttemptRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          IdempotencyService idempotency, ManifestValidator manifestValidator, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.releaseShardRepository = releaseShardRepository;
        this.taskRepository = taskRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.idempotency = idempotency;
        this.manifestValidator = manifestValidator;
        this.clock = clock;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        // 登记前先校验分片清单：序号0连续、摘要格式固定、聚合摘要匹配，任一失败422，不写入任何数据
        List<ShardInput> normalized = manifestValidator.validate(request.packageDigest(), request.shards());
        String packageDigest = normalized == null ? null : request.packageDigest();
        String manifestPart = packageDigest == null ? "legacy"
                : packageDigest + ":" + normalized.stream().map(s -> s.shardIndex() + "=" + s.shardDigest())
                .reduce("", (a, b) -> a + "|" + b);
        String fingerprint = String.join("|", "release.create", request.model(), request.fromVersion(),
                request.toVersion(), String.valueOf(request.ratio()), String.valueOf(sampleFloor),
                String.valueOf(threshold), manifestPart);
        final List<ShardInput> toInsert = normalized;
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold, packageDigest);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在未终结发布单: " + request.model());
            }
            if (toInsert != null) {
                insertShards(id, toInsert);
            }
            return ReleaseView.of(findOrder(id));
        }, ReleaseView.class);
    }

    /**
     * 替换分片清单：仅尚无任务拉取（摘要未锁定）时允许。先校验完整最终清单再事务提交，
     * 校验失败（422/409）不改动旧清单。锁定后任何修改返回 MANIFEST_LOCKED(409)。
     */
    public ManifestView updateManifest(long releaseId, UpdateManifestRequest request) {
        List<ShardInput> normalized = manifestValidator.validate(request.packageDigest(), request.shards());
        String manifestPart = request.packageDigest() + ":" + normalized.stream()
                .map(s -> s.shardIndex() + "=" + s.shardDigest()).reduce("", (a, b) -> a + "|" + b);
        String fingerprint = String.join("|", "release.manifest", String.valueOf(releaseId), manifestPart);
        return idempotency.execute(request.requestId(), "release.manifest", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，清单不可修改");
            }
            if (order.manifestLocked()) {
                throw ApiException.conflict("MANIFEST_LOCKED",
                        "发布单摘要已被任务拉取（" + order.manifestLockedAtUtc() + " UTC），不可修改");
            }
            releaseShardRepository.deleteByRelease(releaseId);
            releaseRepository.updatePackageDigest(releaseId, request.packageDigest());
            insertShards(releaseId, normalized);
            return manifestView(releaseId);
        }, ManifestView.class);
    }

    private void insertShards(long releaseId, List<ShardInput> shards) {
        for (ShardInput shard : shards) {
            releaseShardRepository.insert(releaseId, shard.shardIndex(), shard.shardDigest());
        }
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {
        String fingerprint = String.join("|", "release.expand", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), String.valueOf(request.ratio()));
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
            return ReleaseView.of(findOrder(releaseId));
        }, ReleaseView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复为 ACTIVE；版本加一、开启新监控轮次、本轮统计清零。
     * 与取消并发时按行锁提交顺序决定唯一结果：取消先提交则恢复 409。
     */
    public ReleaseView resume(long releaseId, ResumeReleaseRequest request) {
        String fingerprint = String.join("|", "release.resume", String.valueOf(releaseId),
                String.valueOf(request.expectedVersion()), request.reason());
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
            return ReleaseView.of(resumed);
        }, ReleaseView.class);
    }

    public ReleaseView cancel(long releaseId, String requestId) {
        String fingerprint = String.join("|", "release.cancel", String.valueOf(releaseId));
        return idempotency.execute(requestId, "release.cancel", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED) {
                releaseRepository.cancel(releaseId);
                taskRepository.cancelPendingByRelease(releaseId);
                taskAttemptRepository.cancelOpenByRelease(releaseId);
            }
            return ReleaseView.of(findOrder(releaseId));
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
     * 分片清单诊断（只读）：发布单、分片摘要、完整包摘要、锁定状态与锁定时刻。
     */
    public ManifestView manifest(long releaseId) {
        findOrder(releaseId);
        return manifestView(releaseId);
    }

    private ManifestView manifestView(long releaseId) {
        ReleaseOrder order = findOrder(releaseId);
        List<ShardView> shards = releaseShardRepository.findByReleaseOrdered(releaseId).stream()
                .map(s -> new ShardView(s.shardIndex(), s.shardDigest())).toList();
        return new ManifestView(order.packageDigest(), order.manifestLocked(), order.manifestLockedAtUtc(),
                shards.size(), shards);
    }

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }
}
