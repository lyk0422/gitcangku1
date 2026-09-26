package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ChunkDigestEntry;
import com.example.starter.firmware.api.ChunkSubmissionView;
import com.example.starter.firmware.api.ManifestView;
import com.example.starter.firmware.api.RegisterManifestRequest;
import com.example.starter.firmware.api.SubmitChunksRequest;
import com.example.starter.firmware.api.TaskIntegrityView;
import com.example.starter.firmware.domain.ChunkReceipt;
import com.example.starter.firmware.domain.IntegrityFailureReason;
import com.example.starter.firmware.domain.IntegrityRecord;
import com.example.starter.firmware.domain.Manifest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.IntegrityRecordRepository;
import com.example.starter.firmware.repo.ManifestRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固件分片完整性：发布版本分片清单登记、设备分片接收与完整性判定、诊断查询。
 * 清单登记先完整校验（重复序号、缺口、摘要格式、聚合摘要）再在一个事务内整体替换；
 * 任一任务拉取后清单锁定不可修改。分片接收与判定在发布单行锁 + 任务行锁内按提交顺序裁决，
 * 接收证据与判定记录只增不改；核验失败（缺失、重复、摘要不匹配）将任务转为 INTEGRITY_FAILED，
 * 该结果为已提交的业务事实，响应 200 并在结果体中区分原因。
 */
@Service
public class IntegrityService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final ManifestRepository manifestRepository;
    private final ChunkReceiptRepository chunkReceiptRepository;
    private final IntegrityRecordRepository integrityRecordRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IntegrityService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                            ManifestRepository manifestRepository,
                            ChunkReceiptRepository chunkReceiptRepository,
                            IntegrityRecordRepository integrityRecordRepository,
                            IdempotencyService idempotency, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.manifestRepository = manifestRepository;
        this.chunkReceiptRepository = chunkReceiptRepository;
        this.integrityRecordRepository = integrityRecordRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 登记/替换分片清单：序号从 0 连续、摘要格式固定、聚合摘要匹配，违反返回 422；
     * 已有任务拉取后返回 409 MANIFEST_LOCKED。
     */
    public ManifestView registerManifest(long releaseId, RegisterManifestRequest request) {
        List<ChunkDigestEntry> sorted = request.chunks().stream()
                .sorted(Comparator.comparingInt(ChunkDigestEntry::index))
                .toList();
        String fingerprint = String.join("|", "release.manifest", String.valueOf(releaseId),
                request.packageDigest(), Digests.sha256Hex(canonical(sorted)));
        return idempotency.execute(request.requestId(), "release.manifest", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (taskRepository.countByRelease(releaseId) > 0) {
                throw ApiException.conflict("MANIFEST_LOCKED",
                        "发布单已有任务拉取，分片清单不可修改: " + releaseId);
            }
            List<String> digests = validateAndOrder(request.packageDigest(), sorted);
            Manifest manifest = new Manifest(releaseId, order.toVersion(), digests.size(),
                    request.packageDigest(), UtcTimes.now(clock), digests);
            manifestRepository.replace(manifest);
            return ManifestView.of(manifest, false);
        }, ManifestView.class);
    }

    /**
     * 清单明细查询（只读，不改变状态）。locked 表示已有任务拉取、清单不可再修改。
     */
    public ManifestView getManifest(long releaseId) {
        releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        Manifest manifest = manifestRepository.findByReleaseId(releaseId)
                .orElseThrow(() -> ApiException.notFound("MANIFEST_NOT_FOUND",
                        "发布单未登记分片清单: " + releaseId));
        return ManifestView.of(manifest, taskRepository.countByRelease(releaseId) > 0);
    }

    /**
     * 校验并返回按序号升序的摘要列表：格式固定、序号从 0 连续无重复无缺口、聚合摘要匹配。
     */
    private List<String> validateAndOrder(String packageDigest, List<ChunkDigestEntry> sorted) {
        if (!Digests.isSha256Hex(packageDigest)) {
            throw ApiException.unprocessable("MANIFEST_DIGEST_FORMAT",
                    "完整包摘要格式不合法，要求 64 位小写十六进制 SHA-256，实际: " + packageDigest);
        }
        List<String> digests = new ArrayList<>();
        for (int position = 0; position < sorted.size(); position++) {
            ChunkDigestEntry entry = sorted.get(position);
            if (entry.index() < position) {
                throw ApiException.unprocessable("MANIFEST_DUPLICATE_INDEX",
                        "分片序号重复: " + entry.index());
            }
            if (entry.index() > position) {
                throw ApiException.unprocessable("MANIFEST_INDEX_GAP",
                        "分片序号存在缺口，要求连续序号 " + position + "，实际: " + entry.index());
            }
            if (!Digests.isSha256Hex(entry.digest())) {
                throw ApiException.unprocessable("MANIFEST_DIGEST_FORMAT",
                        "分片 " + entry.index() + " 摘要格式不合法，要求 64 位小写十六进制 SHA-256，实际: "
                                + entry.digest());
            }
            digests.add(entry.digest());
        }
        String computed = Digests.aggregate(digests);
        if (!computed.equals(packageDigest)) {
            throw ApiException.unprocessable("MANIFEST_AGGREGATE_MISMATCH",
                    "聚合摘要不匹配，按分片摘要计算要求值: " + computed + "，实际登记值: " + packageDigest);
        }
        return digests;
    }

    /**
     * 批量接收分片：先校验请求内序号与摘要格式，再在任务行锁内与已接收集合合并判定。
     * 重复序号、完整集合缺失或任一摘要不匹配时任务转为 INTEGRITY_FAILED 并落判定记录；
     * 全部存在且聚合匹配时转为 INSTALLABLE。任一失败不留下部分可安装状态。
     */
    public ChunkSubmissionView submitChunks(long taskId, SubmitChunksRequest request) {
        List<ChunkDigestEntry> sorted = request.chunks().stream()
                .sorted(Comparator.comparingInt(ChunkDigestEntry::index))
                .toList();
        String fingerprint = String.join("|", "task.chunks", String.valueOf(taskId),
                String.valueOf(request.effectiveComplete()), Digests.sha256Hex(canonical(sorted)));
        return idempotency.execute(request.requestId(), "task.chunks", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            Manifest manifest = manifestRepository.findByReleaseId(task.releaseId())
                    .orElseThrow(() -> ApiException.conflict("MANIFEST_NOT_FOUND",
                            "发布单未登记分片清单，不能接收分片: " + task.releaseId()));
            requirePending(task);
            validateSubmission(sorted, manifest.chunkCount());

            List<ChunkReceipt> received =
                    chunkReceiptRepository.findByTaskAndAttempt(taskId, task.attempt());
            Set<Integer> receivedIndexes = new HashSet<>();
            for (ChunkReceipt receipt : received) {
                receivedIndexes.add(receipt.chunkIndex());
            }
            List<Integer> duplicateIndexes = sorted.stream()
                    .map(ChunkDigestEntry::index)
                    .filter(receivedIndexes::contains)
                    .toList();
            if (!duplicateIndexes.isEmpty()) {
                // 重复序号：按提交顺序后到者判负，任务转 INTEGRITY_FAILED，已接收证据不改写
                return decide(task, order, manifest, TaskStatus.INTEGRITY_FAILED,
                        IntegrityFailureReason.CHUNK_DUPLICATE, received.size(), null,
                        "分片序号重复接收: " + duplicateIndexes.get(0), 0);
            }

            String receivedAtUtc = UtcTimes.now(clock);
            for (ChunkDigestEntry entry : sorted) {
                chunkReceiptRepository.insert(taskId, task.attempt(), task.releaseId(),
                        order.toVersion(), entry.index(), entry.digest(), receivedAtUtc);
            }
            Map<Integer, String> merged = new HashMap<>();
            for (ChunkReceipt receipt : received) {
                merged.put(receipt.chunkIndex(), receipt.digest());
            }
            for (ChunkDigestEntry entry : sorted) {
                merged.put(entry.index(), entry.digest());
            }
            int receivedCount = merged.size();

            if (!request.effectiveComplete() && receivedCount < manifest.chunkCount()) {
                return view(task, TaskStatus.PENDING, null, null, sorted.size(), receivedCount,
                        manifest, null, null);
            }
            if (receivedCount < manifest.chunkCount()) {
                return decide(task, order, manifest, TaskStatus.INTEGRITY_FAILED,
                        IntegrityFailureReason.CHUNK_MISSING, receivedCount, null,
                        "完整集合缺失 " + (manifest.chunkCount() - receivedCount) + " 个分片，要求 "
                                + manifest.chunkCount() + "，实际 " + receivedCount,
                        sorted.size());
            }
            List<String> inOrder = new ArrayList<>();
            for (int i = 0; i < manifest.chunkCount(); i++) {
                inOrder.add(merged.get(i));
            }
            String computed = Digests.aggregate(inOrder);
            for (int i = 0; i < manifest.chunkCount(); i++) {
                if (!inOrder.get(i).equals(manifest.chunkDigests().get(i))) {
                    return decide(task, order, manifest, TaskStatus.INTEGRITY_FAILED,
                            IntegrityFailureReason.CHUNK_DIGEST_MISMATCH, receivedCount, computed,
                            "分片 " + i + " 摘要不匹配，要求 " + manifest.chunkDigests().get(i)
                                    + "，实际 " + inOrder.get(i),
                            sorted.size());
                }
            }
            if (!computed.equals(manifest.packageDigest())) {
                return decide(task, order, manifest, TaskStatus.INTEGRITY_FAILED,
                        IntegrityFailureReason.PACKAGE_DIGEST_MISMATCH, receivedCount, computed,
                        "聚合摘要不匹配，要求 " + manifest.packageDigest() + "，实际 " + computed,
                        sorted.size());
            }
            return decide(task, order, manifest, TaskStatus.INSTALLABLE, null, receivedCount,
                    computed, null, sorted.size());
        }, ChunkSubmissionView.class);
    }

    /**
     * 任务分片完整性诊断（只读，不改变状态）：当前代次接收证据 + 全部代次判定历史。
     * fromUtc/toUtc 按左闭右开区间过滤判定历史。
     */
    public TaskIntegrityView taskIntegrity(long taskId, String fromUtc, String toUtc) {
        Instant from = parseBound(fromUtc, "fromUtc");
        Instant to = parseBound(toUtc, "toUtc");
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    "fromUtc 必须不晚于 toUtc（区间左闭右开）");
        }
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        Manifest manifest = manifestRepository.findByReleaseId(task.releaseId())
                .orElseThrow(() -> ApiException.notFound("MANIFEST_NOT_FOUND",
                        "发布单未登记分片清单: " + task.releaseId()));
        List<TaskIntegrityView.ChunkEvidenceView> chunks =
                chunkReceiptRepository.findByTaskAndAttempt(taskId, task.attempt()).stream()
                        .map(r -> new TaskIntegrityView.ChunkEvidenceView(r.attempt(), r.chunkIndex(),
                                r.digest(), r.receivedAtUtc()))
                        .toList();
        final Instant fromBound = from;
        final Instant toBound = to;
        List<TaskIntegrityView.IntegrityDecisionView> decisions =
                integrityRecordRepository.findByTask(taskId).stream()
                        .filter(r -> inRange(r.decidedAtUtc(), fromBound, toBound))
                        .map(r -> new TaskIntegrityView.IntegrityDecisionView(r.attempt(),
                                r.result().name(), r.reason() == null ? null : r.reason().name(),
                                r.receivedCount(), r.requiredCount(), r.computedPackageDigest(),
                                r.expectedPackageDigest(), r.firmwareVersion(), r.decidedAtUtc()))
                        .toList();
        int receivedCount = chunks.size();
        return new TaskIntegrityView(task.id(), task.releaseId(), task.deviceId(), task.status().name(),
                task.attempt(), manifest.chunkCount(), receivedCount,
                manifest.chunkCount() - receivedCount, chunks, decisions);
    }

    private boolean inRange(String decidedAtUtc, Instant from, Instant to) {
        Instant decided = Instant.parse(decidedAtUtc);
        if (from != null && decided.isBefore(from)) {
            return false;
        }
        return to == null || decided.isBefore(to);
    }

    private Instant parseBound(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Instant parsed = UtcTimes.parse(value);
        if (parsed == null) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    name + " 不是合法 UTC ISO-8601 时刻: " + value);
        }
        return parsed;
    }

    private void requirePending(RolloutTask task) {
        switch (task.status()) {
            case PENDING -> {
            }
            case INSTALLABLE -> throw ApiException.conflict("TASK_ALREADY_INSTALLABLE",
                    "任务已通过完整性核验，不再接收分片");
            case INTEGRITY_FAILED -> throw ApiException.conflict("TASK_INTEGRITY_FAILED",
                    "任务分片完整性失败，请重新拉取开启新尝试代次");
            case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_COMPLETED",
                    "任务已终结为 " + task.status() + "，不再接收分片");
            case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，不再接收分片");
        }
    }

    /**
     * 校验提交批次：摘要格式固定、序号在清单范围内、请求内无重复序号。失败返回 422 且不改变状态。
     */
    private void validateSubmission(List<ChunkDigestEntry> sorted, int chunkCount) {
        for (int position = 0; position < sorted.size(); position++) {
            ChunkDigestEntry entry = sorted.get(position);
            if (entry.index() < 0 || entry.index() >= chunkCount) {
                throw ApiException.unprocessable("CHUNK_INDEX_OUT_OF_RANGE",
                        "分片序号超出范围，要求 0~" + (chunkCount - 1) + "，实际: " + entry.index());
            }
            if (position > 0 && sorted.get(position - 1).index() == entry.index()) {
                throw ApiException.unprocessable("CHUNK_DUPLICATE_INDEX",
                        "请求内分片序号重复: " + entry.index());
            }
            if (!Digests.isSha256Hex(entry.digest())) {
                throw ApiException.unprocessable("CHUNK_DIGEST_FORMAT",
                        "分片 " + entry.index() + " 摘要格式不合法，要求 64 位小写十六进制 SHA-256，实际: "
                                + entry.digest());
            }
        }
    }

    /**
     * 落判定记录并迁移任务状态（调用方持有任务行锁），返回提交结果视图。
     */
    private ChunkSubmissionView decide(RolloutTask task, ReleaseOrder order, Manifest manifest,
                                       TaskStatus result, IntegrityFailureReason reason,
                                       int receivedCount, String computedPackageDigest,
                                       String detail, int acceptedCount) {
        integrityRecordRepository.insert(task.id(), task.attempt(), task.releaseId(), order.toVersion(),
                result, reason, receivedCount, manifest.chunkCount(), computedPackageDigest,
                manifest.packageDigest(), UtcTimes.now(clock));
        taskRepository.transitionFromPending(task.id(), result);
        return view(task, result, reason, detail, acceptedCount, receivedCount, manifest,
                computedPackageDigest, result.name());
    }

    private ChunkSubmissionView view(RolloutTask task, TaskStatus status, IntegrityFailureReason reason,
                                     String detail, int acceptedCount, int receivedCount,
                                     Manifest manifest, String computedPackageDigest, String result) {
        return new ChunkSubmissionView(task.id(), task.attempt(), status.name(), acceptedCount,
                receivedCount, manifest.chunkCount(), manifest.chunkCount() - receivedCount,
                result, reason == null ? null : reason.name(), detail,
                computedPackageDigest, manifest.packageDigest());
    }

    private String canonical(List<ChunkDigestEntry> sorted) {
        StringBuilder builder = new StringBuilder();
        for (ChunkDigestEntry entry : sorted) {
            builder.append(entry.index()).append(':').append(entry.digest()).append(';');
        }
        return builder.toString();
    }
}
