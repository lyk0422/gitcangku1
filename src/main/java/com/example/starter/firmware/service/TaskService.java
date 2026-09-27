package com.example.starter.firmware.service;

import com.example.starter.firmware.api.BatchShardReceiptRequest;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.ShardReceiptRequest;
import com.example.starter.firmware.api.ShardReceiptView;
import com.example.starter.firmware.api.AttemptView;
import com.example.starter.firmware.api.TaskDiagnosticsResponse;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.DigestRules;
import com.example.starter.firmware.domain.IntegrityFailureReason;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseShard;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ReleaseShardRepository;
import com.example.starter.firmware.repo.ShardReceiptRepository;
import com.example.starter.firmware.repo.TaskAttemptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投放任务：设备拉取（固化分片摘要快照并锁定发布单清单）、逐分片/批量接收、完整性判定、
 * 完整性失败后重新拉取开新尝试代次、任务撤销、安装回执。
 * 所有裁决均先锁发布单行再锁任务行，与取消/恢复形成一致提交顺序；
 * 同任务同代次同序号分片由唯一约束保证并发最多一笔成功。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskAttemptRepository taskAttemptRepository;
    private final ShardReceiptRepository shardReceiptRepository;
    private final ReleaseRepository releaseRepository;
    private final ReleaseShardRepository releaseShardRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, TaskAttemptRepository taskAttemptRepository,
                       ShardReceiptRepository shardReceiptRepository,
                       ReleaseRepository releaseRepository, ReleaseShardRepository releaseShardRepository,
                       DeviceRepository deviceRepository, PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.taskAttemptRepository = taskAttemptRepository;
        this.shardReceiptRepository = shardReceiptRepository;
        this.releaseRepository = releaseRepository;
        this.releaseShardRepository = releaseShardRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回；否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * 分片发布单的首个任务拉取会冻结清单（之后不可修改），并建立第1代尝试记录，固化要求分片数与完整包摘要。
     * PAUSED 时不创建新任务，已有任务仍可查看。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                return new PullResponse(null);
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                return new PullResponse(buildView(existing.get(), order));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            List<ReleaseShard> shards = order.packageDigest() == null
                    ? List.of() : releaseShardRepository.findByReleaseOrdered(order.id());
            String nowUtc = Instant.now(clock).toString();
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId, shards.size(), order.packageDigest(), nowUtc);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(buildView(task, order));
            }
            if (order.packageDigest() != null) {
                // 首任务拉取即锁定清单；并发拉取由发布单行锁串行，lockManifestIfUnlocked 只生效一次
                releaseRepository.lockManifestIfUnlocked(order.id(), nowUtc);
                taskAttemptRepository.insertReceiving(taskId, 1, order.id(), order.version(),
                        shards.size(), order.packageDigest(), nowUtc);
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(buildView(task, order));
        }, PullResponse.class);
    }

    /**
     * 单片接收：固化证据后按当前代次完整最终集合判定。缺失时保持 PENDING；
     * 越界/摘要不匹配/聚合不匹配时整代次转 INTEGRITY_FAILED。同序号并发最多一笔成功。
     */
    public TaskView receiveShard(long taskId, ShardReceiptRequest request) {
        String fingerprint = String.join("|", "task.shard", String.valueOf(taskId),
                String.valueOf(request.shardIndex()), request.shardDigest());
        return idempotency.execute(request.requestId(), "task.shard", fingerprint,
                () -> receiveInternal(taskId, List.of(new ShardSubmission(request.shardIndex(),
                        request.shardDigest())), false), TaskView.class, TaskService::isStoredResult);
    }

    /**
     * 批量接收：按“已有证据 ∪ 本批（去重）”的完整最终集合一次性校验并同事务提交，
     * 任一分片越界/重复/摘要不匹配则整代次 INTEGRITY_FAILED，不留下部分可安装状态。
     */
    public TaskView receiveShardsBatch(long taskId, BatchShardReceiptRequest request) {
        List<ShardSubmission> submissions = request.shards().stream()
                .map(s -> new ShardSubmission(s.shardIndex(), s.shardDigest())).toList();
        String joined = submissions.stream()
                .map(s -> s.shardIndex() + "=" + s.shardDigest())
                .sorted().reduce("", (a, b) -> a + "|" + b);
        String fingerprint = String.join("|", "task.shards.batch", String.valueOf(taskId), joined);
        return idempotency.execute(request.requestId(), "task.shards.batch", fingerprint,
                () -> receiveInternal(taskId, submissions, true), TaskView.class,
                TaskService::isStoredResult);
    }

    /**
     * 完整性失败属业务失败：证据照常提交，但不占用 requestId（失败不占键），之后同键可再用。
     */
    private static boolean isStoredResult(TaskView view) {
        return !TaskStatus.INTEGRITY_FAILED.name().equals(view.status());
    }

    private record ShardSubmission(int shardIndex, String shardDigest) {
    }

    /**
     * 分片接收共同裁决。调用方处于幂等事务内，持有发布单行锁与任务行锁。
     * 单片为增量提交，缺口保持 PENDING；批量按“已有证据 ∪ 本批”的完整最终集合裁决，缺口判 MISSING_SHARD。
     */
    private TaskView receiveInternal(long taskId, List<ShardSubmission> submissions, boolean finalSetClaim) {
        RolloutTask snapshot = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        assertReceiving(task);

        List<ReleaseShard> manifest = releaseShardRepository.findByReleaseOrdered(order.id());
        String nowUtc = Instant.now(clock).toString();

        // 1) 已固化证据同序号（异 requestId 的后到提交，含并发输家）按提交顺序裁决：409，整笔回滚不改状态
        java.util.Set<Integer> distinctInRequest = new java.util.HashSet<>();
        boolean duplicateInBatch = false;
        for (ShardSubmission submission : submissions) {
            if (!distinctInRequest.add(submission.shardIndex())) {
                duplicateInBatch = true;
            }
            if (shardReceiptRepository.find(taskId, task.attemptNo(), submission.shardIndex()).isPresent()) {
                throw ApiException.conflict("SHARD_ALREADY_RECEIVED",
                        "分片 " + submission.shardIndex() + " 已接收（按提交顺序裁决），同序号最多一笔成功");
            }
        }

        // 2) 越界：同批所有不同序号都原样固化证据（越界 expected 为 null），随后判 OUT_OF_RANGE
        Map<Integer, ShardSubmission> distinct = new LinkedHashMap<>();
        for (ShardSubmission submission : submissions) {
            distinct.putIfAbsent(submission.shardIndex(), submission);
        }
        if (distinct.keySet().stream().anyMatch(i -> i < 0 || i >= task.requiredShards())) {
            for (ShardSubmission submission : distinct.values()) {
                boolean invalid = submission.shardIndex() < 0
                        || submission.shardIndex() >= task.requiredShards();
                String expected = invalid ? null : manifest.get(submission.shardIndex()).shardDigest();
                insertEvidence(task, order, submission.shardIndex(), expected,
                        submission.shardDigest(), nowUtc);
            }
            return failIntegrity(task, nowUtc, IntegrityFailureReason.OUT_OF_RANGE);
        }

        // 3) 逐片固化新证据；任一新证据摘要不匹配（含格式非法）即刻判 DIGEST_MISMATCH，不等集合补齐
        java.util.Set<Integer> inserted = new java.util.HashSet<>();
        boolean digestMismatch = false;
        for (ShardSubmission submission : submissions) {
            if (!inserted.add(submission.shardIndex())) {
                continue;
            }
            String expected = manifest.get(submission.shardIndex()).shardDigest();
            boolean matched = expected.equals(submission.shardDigest())
                    && DigestRules.isSha256Hex(submission.shardDigest());
            if (!matched) {
                digestMismatch = true;
            }
            try {
                insertEvidence(task, order, submission.shardIndex(), expected, submission.shardDigest(), nowUtc);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("SHARD_ALREADY_RECEIVED",
                        "分片 " + submission.shardIndex() + " 已被并发请求先接收，同序号最多一笔成功");
            }
        }
        if (digestMismatch) {
            return failIntegrity(task, nowUtc, IntegrityFailureReason.DIGEST_MISMATCH);
        }
        // 同一请求批次内重复序号属最终集合非法：已固化的不同序号证据保留，整代次判 DUPLICATE_SHARD
        if (duplicateInBatch) {
            return failIntegrity(task, nowUtc, IntegrityFailureReason.DUPLICATE_SHARD);
        }
        return evaluateFinalSet(task, nowUtc, finalSetClaim);
    }

    private void insertEvidence(RolloutTask task, ReleaseOrder order, int shardIndex,
                                String expectedDigest, String receivedDigest, String nowUtc) {
        shardReceiptRepository.insert(task.id(), task.attemptNo(), order.id(), order.version(),
                shardIndex, expectedDigest, receivedDigest,
                expectedDigest != null && expectedDigest.equals(receivedDigest), nowUtc);
    }

    /**
     * 按当前代次完整最终集合判定：全部存在、顺序0连续、逐片摘要匹配、聚合摘要匹配才可安装。
     * 单片增量接收存在缺口时保持 PENDING；批量声称最终集合但仍有缺口判 MISSING_SHARD。
     * 摘要或聚合不匹配不可由后续接收修复，立即判 INTEGRITY_FAILED。
     */
    private TaskView evaluateFinalSet(RolloutTask task, String nowUtc, boolean finalSetClaim) {
        var receipts = shardReceiptRepository.findByTaskAndAttemptOrdered(task.id(), task.attemptNo());
        Map<Integer, String> byIndex = new LinkedHashMap<>();
        for (var receipt : receipts) {
            byIndex.putIfAbsent(receipt.shardIndex(), receipt.receivedDigest());
        }
        taskRepository.updateReceivedShards(task.id(), byIndex.size());

        List<ReleaseShard> manifest = releaseShardRepository
                .findByReleaseOrdered(task.releaseId());
        List<String> missing = new java.util.ArrayList<>();
        for (int i = 0; i < task.requiredShards(); i++) {
            if (!byIndex.containsKey(i)) {
                missing.add(String.valueOf(i));
            }
        }
        if (!missing.isEmpty()) {
            if (finalSetClaim) {
                return failIntegrity(task, nowUtc, IntegrityFailureReason.MISSING_SHARD);
            }
            // 增量接收、仍有缺口：保持接收中，等待后续分片，不留下失败/可安装终态
            return reloadView(task.id());
        }
        for (ReleaseShard shard : manifest) {
            String received = byIndex.get(shard.shardIndex());
            if (!DigestRules.isSha256Hex(received) || !shard.shardDigest().equals(received)) {
                return failIntegrity(task, nowUtc, IntegrityFailureReason.DIGEST_MISMATCH);
            }
        }
        List<String> orderedReceived = manifest.stream()
                .map(s -> byIndex.get(s.shardIndex())).toList();
        String aggregate = DigestRules.aggregateOfOrderedDigests(orderedReceived);
        if (!aggregate.equals(task.packageDigest())) {
            return failIntegrity(task, nowUtc, IntegrityFailureReason.AGGREGATE_MISMATCH);
        }
        taskRepository.markInstallable(task.id(), nowUtc);
        taskAttemptRepository.finalizeInstallable(task.id(), task.attemptNo(), task.requiredShards(),
                aggregate, nowUtc);
        return reloadView(task.id());
    }

    /**
     * 完整性失败：固化失败原因与时刻。已接收分片证据不删除、不可改写。
     */
    private TaskView failIntegrity(RolloutTask task, String nowUtc, IntegrityFailureReason reason) {
        long count = shardReceiptRepository.countByTaskAndAttempt(task.id(), task.attemptNo());
        taskRepository.updateReceivedShards(task.id(), (int) count);
        taskRepository.markIntegrityFailed(task.id(), reason.name(), nowUtc);
        taskAttemptRepository.finalizeIntegrityFailed(task.id(), task.attemptNo(), (int) count,
                reason.name(), nowUtc);
        return reloadView(task.id());
    }

    private void assertReceiving(RolloutTask task) {
        if (task.requiredShards() == 0) {
            throw ApiException.unprocessable("NO_SHARD_MANIFEST",
                    "该发布单未登记分片清单，不支持分片接收");
        }
        switch (task.status()) {
            case PENDING -> {
            }
            case INTEGRITY_FAILED -> throw ApiException.conflict("ATTEMPT_INTEGRITY_FAILED",
                    "当前代次已完整性失败，请重新拉取建立新代次");
            case INSTALLABLE -> throw ApiException.conflict("TASK_INSTALLABLE",
                    "任务已判定可安装，分片接收已关闭");
            case SUCCESS, FAILED -> throw ApiException.conflict("TASK_TERMINATED",
                    "任务已安装终结，不再接收分片");
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已撤销，不再接收分片");
        }
    }

    /**
     * 重新拉取：仅 INTEGRITY_FAILED 的未安装任务可建立新尝试代次；旧代次记录与证据永久保留。
     * 新代次重新固化当时的要求分片数与完整包摘要（清单已锁定，二者不会变化）。
     */
    public TaskView repull(long taskId, String requestId) {
        String fingerprint = String.join("|", "task.repull", String.valueOf(taskId));
        return idempotency.execute(requestId, "task.repull", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case INTEGRITY_FAILED -> {
                    List<ReleaseShard> shards = releaseShardRepository.findByReleaseOrdered(order.id());
                    String nowUtc = Instant.now(clock).toString();
                    int updated = taskRepository.openNewAttempt(taskId, shards.size(), order.packageDigest());
                    if (updated != 1) {
                        // 并发下状态被改写：以当前状态裁决
                        yield reloadView(taskId);
                    }
                    RolloutTask reopened = taskRepository.findById(taskId).orElseThrow();
                    taskAttemptRepository.insertReceiving(taskId, reopened.attemptNo(), order.id(),
                            order.version(), shards.size(), order.packageDigest(), nowUtc);
                    yield buildView(reopened, order);
                }
                case PENDING -> throw ApiException.conflict("TASK_RECEIVING", "当前代次仍在接收中，无需重新拉取");
                case INSTALLABLE -> throw ApiException.conflict("TASK_INSTALLABLE", "任务已可安装，不能重新拉取");
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_TERMINATED", "任务已安装终结，不能重新拉取");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已撤销，不能重新拉取");
            };
        }, TaskView.class);
    }

    /**
     * 任务撤销：未安装任务（接收中/完整性失败/可安装）转 CANCELLED，代次同步留撤销终态；
     * 已安装（SUCCESS/FAILED）不可撤销。重复撤销幂等返回 CANCELLED。
     */
    public TaskView cancel(long taskId, String requestId) {
        String fingerprint = String.join("|", "task.cancel", String.valueOf(taskId));
        return idempotency.execute(requestId, "task.cancel", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (task.status() == TaskStatus.SUCCESS || task.status() == TaskStatus.FAILED) {
                throw ApiException.conflict("TASK_TERMINATED", "任务已安装终结，不可撤销");
            }
            if (task.status() != TaskStatus.CANCELLED) {
                taskRepository.cancelReceiving(taskId);
                taskAttemptRepository.cancelAttempt(taskId, task.attemptNo());
            }
            return buildView(taskRepository.findById(taskId).orElseThrow(), order);
        }, TaskView.class);
    }

    /**
     * 安装回执：分片发布单仅 INSTALLABLE 可回执，PENDING/INTEGRITY_FAILED 一律拒绝且不计失败率；
     * 无分片清单的遗留发布单沿用 PENDING 即回执。首次终结计入完成时所在监控轮次，仅 SUCCESS 更新设备版本；
     * 同结果重复成功（不重复计数），改结果 409；已取消任务的后到回执 409 且不更新设备版本。
     * 样本达到下限且失败率越限时，同事务将仍为 ACTIVE 的发布单原子转为 PAUSED 并落暂停记录。
     */
    public TaskView receipt(long taskId, ReceiptRequest request) {
        String fingerprint = String.join("|", "task.receipt", String.valueOf(taskId), request.result().name());
        return idempotency.execute(request.requestId(), "task.receipt", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder lockedOrder = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            return switch (task.status()) {
                case PENDING -> {
                    if (task.requiredShards() > 0) {
                        throw ApiException.conflict("TASK_NOT_INSTALLABLE",
                                "分片尚未接收完整（实际 " + task.receivedShards() + "/要求 "
                                        + task.requiredShards() + "），禁止安装回执");
                    }
                    yield completeReceipt(task, lockedOrder, request.result());
                }
                case INTEGRITY_FAILED -> throw ApiException.conflict("INTEGRITY_FAILED",
                        "分片完整性失败（" + task.failureReason() + "），禁止安装和成功回执，且不计入失败率");
                case INSTALLABLE -> completeReceipt(task, lockedOrder, request.result());
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield buildView(task, lockedOrder);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，回执不再受理");
            };
        }, TaskView.class);
    }

    private TaskView completeReceipt(RolloutTask task, ReleaseOrder lockedOrder, ReceiptResult result) {
        taskRepository.complete(task.id(), result);
        releaseRepository.incrementRoundStats(task.releaseId(), result);
        if (result == ReceiptResult.SUCCESS) {
            deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
        }
        ReleaseOrder updated = releaseRepository.findById(task.releaseId()).orElseThrow();
        pauseIfThresholdReached(updated, task.id());
        return reloadView(task.id());
    }

    /**
     * 样本数达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的发布单原子转 PAUSED。
     * 调用方持有发布单行锁，并发回执串行通过，每轮至多生成一条暂停记录。
     * INTEGRITY_FAILED 不进入统计，永不触发暂停。
     */
    private void pauseIfThresholdReached(ReleaseOrder order, long triggerTaskId) {
        if (order.status() != ReleaseStatus.ACTIVE) {
            return;
        }
        int samples = order.roundSuccess() + order.roundFailed();
        if (samples < order.sampleFloor()) {
            return;
        }
        if ((long) order.roundFailed() * 100 < (long) samples * order.failureThresholdPercent()) {
            return;
        }
        if (releaseRepository.pauseIfActive(order.id()) == 1) {
            pauseRecordRepository.insert(order.id(), order.monitorRound(), triggerTaskId,
                    order.roundSuccess(), order.roundFailed(), Instant.now(clock).toString());
        }
    }

    /**
     * 任务诊断（只读）：当前状态（含实际/要求分片数与差额）、全部尝试代次、各代次分片接收证据。
     */
    public TaskDiagnosticsResponse diagnostics(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseService.findOrder(task.releaseId());
        var attempts = taskAttemptRepository.findByTaskOrdered(taskId);
        List<ShardReceiptView> receipts = new java.util.ArrayList<>();
        for (var attempt : attempts) {
            shardReceiptRepository.findByTaskAndAttemptOrdered(taskId, attempt.attemptNo()).stream()
                    .map(ShardReceiptView::of).forEach(receipts::add);
        }
        return new TaskDiagnosticsResponse(buildView(task, order),
                attempts.stream().map(AttemptView::of).toList(), receipts);
    }

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<ReleaseShard> shards = order.packageDigest() == null
                ? List.of() : releaseShardRepository.findByReleaseOrdered(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> buildView(task, order, shards))
                .toList();
        return new TaskListResponse(tasks);
    }

    private TaskView reloadView(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务读取失败: " + taskId));
        return buildView(task, releaseService.findOrder(task.releaseId()));
    }

    private TaskView buildView(RolloutTask task, ReleaseOrder order) {
        List<ReleaseShard> shards = order.packageDigest() == null
                ? List.of() : releaseShardRepository.findByReleaseOrdered(order.id());
        return TaskView.of(task, order, shards);
    }

    private TaskView buildView(RolloutTask task, ReleaseOrder order, List<ReleaseShard> shards) {
        return TaskView.of(task, order, shards);
    }
}
