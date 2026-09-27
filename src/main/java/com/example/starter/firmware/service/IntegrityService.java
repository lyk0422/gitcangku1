package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ChunkDigestInput;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.ChunkEntry;
import com.example.starter.firmware.domain.ChunkReceipt;
import com.example.starter.firmware.domain.IntegrityEvent;
import com.example.starter.firmware.domain.IntegrityEventType;
import com.example.starter.firmware.domain.IntegrityFailureReason;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskAttempt;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ChunkManifestRepository;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.IntegrityEventRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskAttemptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固件分片完整性核验。
 *
 * <p>设备逐分片（可单条或批量）提交接收摘要：本批全部合法时接收证据与 CHUNK_ACCEPTED 事件在一个事务提交，
 * 集合尚不齐备则任务保持 PENDING；全部分片存在、摘要匹配且聚合摘要匹配时落 INSTALLABLE 判定事件
 * 并进入可安装状态。序号超范围、重复（批内或与已接收证据重复）或任一摘要不匹配时，
 * 同事务固化 INTEGRITY_FAILED 事件并将任务与当前尝试代次转为 INTEGRITY_FAILED，禁止安装，
 * 且不写入本批任何接收证据，不留半成品；完整性失败不计入设备执行失败率。
 *
 * <p>同任务并发提交同序号分片由唯一约束裁决，最多一笔成功，落选方回滚并得到可区分的 409。
 * 失败裁决的证据必须落库，而失败不占幂等键，因此走已裁决执行器：证据事务提交后再向调用方抛出错误。
 */
@Service
public class IntegrityService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final ChunkManifestRepository manifestRepository;
    private final ChunkReceiptRepository receiptRepository;
    private final TaskAttemptRepository attemptRepository;
    private final IntegrityEventRepository eventRepository;
    private final TaskService taskService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IntegrityService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                            ChunkManifestRepository manifestRepository,
                            ChunkReceiptRepository receiptRepository,
                            TaskAttemptRepository attemptRepository,
                            IntegrityEventRepository eventRepository,
                            TaskService taskService, IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.manifestRepository = manifestRepository;
        this.receiptRepository = receiptRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.taskService = taskService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 接收一批分片摘要并裁决。返回裁决后的任务视图（PENDING 表示还可继续补交，INSTALLABLE 表示可安装）；
     * 完整性失败时事务提交后抛出 409（错误码为失败原因）。
     */
    public TaskView receiveChunks(long taskId, String requestId, List<ChunkDigestInput> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            throw ApiException.badRequest("EMPTY_BATCH", "分片接收批次不能为空");
        }
        String sortedFingerprint = chunks.stream()
                .sorted(java.util.Comparator.comparing(ChunkDigestInput::chunkIndex)
                        .thenComparing(ChunkDigestInput::digest))
                .map(c -> c.chunkIndex() + ":" + c.digest())
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        String fingerprint = String.join("|", "task.chunks", String.valueOf(taskId), sortedFingerprint);
        return idempotency.executeAdjudicated(requestId, "task.chunks", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            if (!order.hasManifest()) {
                throw ApiException.conflict("MANIFEST_NOT_REGISTERED",
                        "发布单未登记分片清单，不支持分片接收: " + order.id());
            }
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            TaskAttempt attempt = attemptRepository.findCurrent(taskId)
                    .orElseThrow(() -> new IllegalStateException("清单任务缺少尝试代次: " + taskId));
            if (task.status() == TaskStatus.SUCCESS || task.status() == TaskStatus.FAILED) {
                throw ApiException.conflict("TASK_ALREADY_FINAL",
                        "任务已终结为 " + task.status() + "，不再接收分片");
            }
            if (task.status() == TaskStatus.CANCELLED) {
                throw ApiException.conflict("TASK_CANCELLED", "任务已取消，不再接收分片");
            }
            if (task.status() == TaskStatus.INTEGRITY_FAILED) {
                // 失败不占键，但同参重放须返回首次裁决：当前代次已固化的失败原因即首次响应；
                // 若当前代次没有失败事件（说明是重拉后的新代次），则按门禁拒绝提示重新拉取。
                IntegrityEvent lastFailure = null;
                for (IntegrityEvent event : eventRepository.findByTaskAndAttempt(taskId, attempt.attemptNo())) {
                    if (event.eventType() == IntegrityEventType.INTEGRITY_FAILED) {
                        lastFailure = event;
                    }
                }
                if (lastFailure != null) {
                    throw ApiException.conflict(lastFailure.reason().name(), lastFailure.detail());
                }
                throw ApiException.conflict("TASK_INTEGRITY_FAILED",
                        "任务历史尝试代次已完整性失败，请重新拉取建立新尝试代次后再提交");
            }
            if (task.status() == TaskStatus.INSTALLABLE) {
                throw ApiException.conflict("TASK_ALREADY_INSTALLABLE",
                        "任务已完成完整性核验并进入可安装状态，请勿重复提交");
            }

            String now = Instant.now(clock).toString();
            List<ChunkEntry> manifest = manifestRepository.findByRelease(order.id());
            Map<Integer, String> expectedByIndex = new HashMap<>();
            manifest.forEach(e -> expectedByIndex.put(e.chunkIndex(), e.digest()));
            List<ChunkReceipt> existing =
                    receiptRepository.findByTaskAndAttempt(taskId, attempt.attemptNo());
            Set<Integer> receivedIndices = new HashSet<>();
            existing.forEach(r -> receivedIndices.add(r.chunkIndex()));

            // 先校验完整最终集合：格式、范围、批内重复、与已接收证据重复、摘要匹配。
            Set<Integer> batchIndices = new HashSet<>();
            for (ChunkDigestInput chunk : chunks) {
                Integer index = chunk.chunkIndex();
                String digest = chunk.digest();
                if (index == null || digest == null) {
                    throw ApiException.badRequest("INVALID_CHUNK", "分片条目缺少 chunkIndex 或 digest");
                }
                if (!Digests.isSha256Hex(digest)) {
                    return commitFailure(order, taskId, attempt,
                            IntegrityFailureReason.DIGEST_MISMATCH, index, null, digest,
                            "分片 " + index + " 摘要格式不合法：要求64位小写十六进制，实际 " + digest, now);
                }
                if (index < 0 || index >= order.chunkCount()) {
                    return commitFailure(order, taskId, attempt,
                            IntegrityFailureReason.INDEX_OUT_OF_RANGE, index, null, digest,
                            "分片序号超出范围：要求 0.." + (order.chunkCount() - 1)
                                    + "（要求分片总数 " + order.chunkCount() + "），实际 " + index, now);
                }
                if (!batchIndices.add(index)) {
                    return commitFailure(order, taskId, attempt,
                            IntegrityFailureReason.DUPLICATE_INDEX, index,
                            expectedByIndex.get(index), digest,
                            "同一批次内分片序号重复: " + index, now);
                }
                if (receivedIndices.contains(index)) {
                    ChunkReceipt prior = existing.stream()
                            .filter(r -> r.chunkIndex() == index).findFirst().orElseThrow();
                    return commitFailure(order, taskId, attempt,
                            IntegrityFailureReason.DUPLICATE_INDEX, index, prior.digest(), digest,
                            "分片 " + index + " 在当前尝试代次已接收（已接收摘要 " + prior.digest()
                                    + "），不能重复提交", now);
                }
                String expected = expectedByIndex.get(index);
                if (!expected.equals(digest)) {
                    return commitFailure(order, taskId, attempt,
                            IntegrityFailureReason.DIGEST_MISMATCH, index, expected, digest,
                            "分片 " + index + " 摘要不匹配：期望 " + expected + "，实际 " + digest, now);
                }
            }

            // 校验通过：固化本批接收证据与逐分片 CHUNK_ACCEPTED 事件。
            for (ChunkDigestInput chunk : chunks) {
                try {
                    receiptRepository.insert(taskId, attempt.attemptNo(), chunk.chunkIndex(), chunk.digest(), now);
                } catch (DuplicateKeyException e) {
                    // 并发同序号提交的落选方：本事务回滚，不留半成品，返回可区分的并发冲突。
                    throw ApiException.conflict("CHUNK_CONCURRENT_CONFLICT",
                            "分片 " + chunk.chunkIndex() + " 已被并发请求先行接收，本批全部回滚，请重新核对后提交");
                }
                eventRepository.insert(new IntegrityEvent(0, taskId, attempt.attemptNo(), order.id(),
                        IntegrityEventType.CHUNK_ACCEPTED, chunk.chunkIndex(),
                        expectedByIndex.get(chunk.chunkIndex()), chunk.digest(),
                        null, null, null, null, null, now));
                receivedIndices.add(chunk.chunkIndex());
            }

            TaskStatus finalStatus;
            if (receivedIndices.size() == order.chunkCount()) {
                // 集合齐备：按序号顺序聚合接收摘要并与完整包摘要核对，固化 INSTALLABLE 判定。
                List<String> receivedInOrder = manifest.stream()
                        .map(e -> receiptRepository.find(taskId, attempt.attemptNo(), e.chunkIndex())
                                .orElseThrow(() -> new IllegalStateException("已判定齐备但缺少接收证据: " + e.chunkIndex()))
                                .digest())
                        .toList();
                String aggregate = Digests.aggregateOf(receivedInOrder);
                if (!aggregate.equals(order.packageDigest())) {
                    return commitFailure(order, taskId, attempt,
                            IntegrityFailureReason.AGGREGATE_MISMATCH, null,
                            order.packageDigest(), null,
                            "聚合摘要不匹配：要求等于登记完整包摘要 " + order.packageDigest()
                                    + "，实际聚合值 " + aggregate, now);
                }
                eventRepository.insert(new IntegrityEvent(0, taskId, attempt.attemptNo(), order.id(),
                        IntegrityEventType.INSTALLABLE, null, null, null,
                        order.chunkCount(), aggregate, order.packageDigest(), null, null, now));
                attemptRepository.updateStatus(taskId, attempt.attemptNo(), TaskStatus.INSTALLABLE, null);
                taskRepository.updateStatus(taskId, TaskStatus.INSTALLABLE);
                finalStatus = TaskStatus.INSTALLABLE;
            } else {
                finalStatus = TaskStatus.PENDING;
            }
            // 显式引用以表达终态由上面分支唯一决定。
            if (finalStatus != TaskStatus.INSTALLABLE && finalStatus != TaskStatus.PENDING) {
                throw new IllegalStateException("非法的接收裁决状态: " + finalStatus);
            }
            return IdempotencyService.AdjudicatedOutcome.ok(taskService.detail(taskId));
        }, TaskView.class);
    }

    /**
     * 固化完整性失败：在当前事务内写入 INTEGRITY_FAILED 事件，将任务与当前尝试代次转为
     * INTEGRITY_FAILED，返回“已提交失败”裁决（不占幂等键，证据随事务提交）。
     */
    private IdempotencyService.AdjudicatedOutcome<TaskView> commitFailure(
            ReleaseOrder order, long taskId, TaskAttempt attempt, IntegrityFailureReason reason,
            Integer chunkIndex, String expectedDigest, String receivedDigest,
            String detail, String now) {
        eventRepository.insert(new IntegrityEvent(0, taskId, attempt.attemptNo(), order.id(),
                IntegrityEventType.INTEGRITY_FAILED, chunkIndex, expectedDigest, receivedDigest,
                order.chunkCount(), null, order.packageDigest(), reason, detail, now));
        attemptRepository.updateStatus(taskId, attempt.attemptNo(), TaskStatus.INTEGRITY_FAILED, now);
        taskRepository.updateStatus(taskId, TaskStatus.INTEGRITY_FAILED);
        return IdempotencyService.AdjudicatedOutcome.committedFailure(
                ApiException.conflict(reason.name(), detail));
    }
}
