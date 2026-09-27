package com.example.starter.firmware.service;

import com.example.starter.firmware.api.AttemptHistoryResponse;
import com.example.starter.firmware.api.AttemptView;
import com.example.starter.firmware.api.ChunkBatchRequest;
import com.example.starter.firmware.api.ChunkDigestBody;
import com.example.starter.firmware.api.IntegrityView;
import com.example.starter.firmware.api.ManifestView;
import com.example.starter.firmware.api.ReceiptView;
import com.example.starter.firmware.api.RegisterManifestRequest;
import com.example.starter.firmware.domain.ChunkReceipt;
import com.example.starter.firmware.domain.ManifestChunk;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskAttempt;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.AttemptRepository;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.ManifestRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 固件分片完整性：发布版本分片清单登记、批量接收核验、重新拉取代次与只读诊断。
 * 接收与判定在任务行锁内串行；判定证据（发布版本、聚合结果、时刻）写入后不再改写。
 * 批量接收先校验合并后的完整最终集合，任一失败整体判定 INTEGRITY_FAILED，不留下部分可安装状态。
 */
@Service
public class IntegrityService {

    private final ManifestRepository manifestRepository;
    private final AttemptRepository attemptRepository;
    private final ChunkReceiptRepository chunkReceiptRepository;
    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IntegrityService(ManifestRepository manifestRepository, AttemptRepository attemptRepository,
                            ChunkReceiptRepository chunkReceiptRepository, TaskRepository taskRepository,
                            ReleaseRepository releaseRepository, IdempotencyService idempotency, Clock clock) {
        this.manifestRepository = manifestRepository;
        this.attemptRepository = attemptRepository;
        this.chunkReceiptRepository = chunkReceiptRepository;
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 按序号升序拼接分片摘要后计算SHA-256（64位小写十六进制）。
     */
    public static String aggregateOf(List<String> digestsInOrder) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        for (String digest : digestsInOrder) {
            md.update(digest.getBytes(StandardCharsets.UTF_8));
        }
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 登记发布版本分片清单：序号从0连续、无重复无缺口、聚合摘要匹配，否则422。
     * 发布单已有任务拉取后清单不可修改（409）；无任务时允许覆盖重登记。
     */
    public ManifestView registerManifest(long releaseId, RegisterManifestRequest request) {
        List<ChunkDigestBody> sorted = request.chunks().stream()
                .sorted(Comparator.comparingInt(ChunkDigestBody::index))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            int index = sorted.get(i).index();
            if (i > 0 && sorted.get(i - 1).index() == index) {
                throw ApiException.unprocessable("CHUNK_INDEX_DUPLICATE", "分片序号重复: " + index);
            }
            if (index != i) {
                throw ApiException.unprocessable("CHUNK_INDEX_GAP",
                        "分片序号存在缺口: 要求序号 " + i + "，实际序号 " + index);
            }
        }
        String computed = aggregateOf(sorted.stream().map(ChunkDigestBody::digest).toList());
        if (!computed.equals(request.aggregateDigest())) {
            throw ApiException.unprocessable("AGGREGATE_MISMATCH",
                    "聚合摘要不匹配: 要求 " + request.aggregateDigest() + "，实际 " + computed);
        }
        String fingerprint = String.join("|", "release.manifest", String.valueOf(releaseId),
                aggregateOf(List.of(request.aggregateDigest() + ";"
                        + String.join(",", sorted.stream().map(c -> c.index() + ":" + c.digest()).toList()))));
        return idempotency.execute(request.requestId(), "release.manifest", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.CANCELLED) {
                throw ApiException.conflict("RELEASE_CANCELLED", "发布单已取消，不能登记分片清单");
            }
            if (taskRepository.countByRelease(releaseId) > 0) {
                throw ApiException.conflict("MANIFEST_LOCKED", "已有任务拉取，发布版本分片清单不可修改");
            }
            String now = Instant.now(clock).toString();
            manifestRepository.deleteManifest(releaseId);
            manifestRepository.insertManifest(releaseId, sorted.size(), request.aggregateDigest(), now);
            for (ChunkDigestBody chunk : sorted) {
                manifestRepository.insertChunk(releaseId, chunk.index(), chunk.digest());
            }
            return new ManifestView(releaseId, sorted.size(), request.aggregateDigest(), now,
                    sorted.stream().map(c -> new ManifestView.ChunkItem(c.index(), c.digest())).toList());
        }, ManifestView.class);
    }

    /**
     * 分片清单明细查询（只读，不改变状态）。
     */
    public ManifestView getManifest(long releaseId) {
        releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        var manifest = manifestRepository.findManifest(releaseId)
                .orElseThrow(() -> ApiException.notFound("MANIFEST_NOT_FOUND", "发布单未登记分片清单: " + releaseId));
        return ManifestView.of(manifest, manifestRepository.findChunks(releaseId));
    }

    /**
     * 批量接收分片：与已接收证据合并校验完整最终集合。
     * 重复、越界或任一摘要不匹配时任务转 INTEGRITY_FAILED 并固化证据；
     * 集合完整时判定聚合，匹配转 INSTALLABLE，不匹配转 INTEGRITY_FAILED。
     */
    public IntegrityView receiveChunks(long taskId, ChunkBatchRequest request) {
        String fingerprint = String.join("|", "task.chunks", String.valueOf(taskId),
                aggregateOf(List.of(String.join(",",
                        request.chunks().stream().map(c -> c.index() + ":" + c.digest()).toList()))));
        return idempotency.execute(request.requestId(), "task.chunks", fingerprint, () -> {
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (task.status() != TaskStatus.PENDING) {
                throw ApiException.conflict("TASK_NOT_RECEIVING",
                        "任务状态为 " + task.status() + "，不能接收分片");
            }
            var manifest = manifestRepository.findManifest(task.releaseId())
                    .orElseThrow(() -> ApiException.conflict("MANIFEST_NOT_REGISTERED",
                            "发布单未登记分片清单，不能接收分片"));
            Map<Integer, String> expected = new HashMap<>();
            for (ManifestChunk chunk : manifestRepository.findChunks(task.releaseId())) {
                expected.put(chunk.chunkIndex(), chunk.digest());
            }
            int attemptNo = task.currentAttempt();
            List<ChunkReceipt> existing = chunkReceiptRepository.findByAttempt(taskId, attemptNo);
            Set<Integer> receivedIndices = new HashSet<>();
            for (ChunkReceipt receipt : existing) {
                receivedIndices.add(receipt.chunkIndex());
            }

            String failure = validateBatch(request.chunks(), manifest.chunkCount(), receivedIndices, expected);
            String now = Instant.now(clock).toString();
            List<ChunkReceipt> inserted = new ArrayList<>();
            Set<Integer> batchSeen = new HashSet<>();
            for (ChunkDigestBody chunk : request.chunks()) {
                String expectedDigest = expected.get(chunk.index());
                boolean insertable = expectedDigest != null
                        && !receivedIndices.contains(chunk.index())
                        && batchSeen.add(chunk.index());
                if (!insertable) {
                    continue;
                }
                boolean matched = expectedDigest.equals(chunk.digest());
                chunkReceiptRepository.insert(taskId, attemptNo, chunk.index(), chunk.digest(),
                        expectedDigest, matched, now);
                attemptRepository.incrementReceived(taskId, attemptNo);
                inserted.add(new ChunkReceipt(0, taskId, attemptNo, chunk.index(), chunk.digest(),
                        expectedDigest, matched, now));
            }

            if (failure != null) {
                taskRepository.updateStatus(taskId, TaskStatus.INTEGRITY_FAILED);
                attemptRepository.decide(taskId, attemptNo, TaskStatus.INTEGRITY_FAILED.name(),
                        failure, null, now);
                return currentView(taskId);
            }

            Set<Integer> merged = new HashSet<>(receivedIndices);
            request.chunks().forEach(c -> merged.add(c.index()));
            if (merged.size() == manifest.chunkCount()) {
                List<ChunkReceipt> all = new ArrayList<>(existing);
                all.addAll(inserted);
                all.sort(Comparator.comparingInt(ChunkReceipt::chunkIndex));
                String actual = aggregateOf(all.stream().map(ChunkReceipt::digest).toList());
                if (actual.equals(manifest.aggregateDigest())) {
                    taskRepository.updateStatus(taskId, TaskStatus.INSTALLABLE);
                    attemptRepository.decide(taskId, attemptNo, TaskStatus.INSTALLABLE.name(),
                            "全部分片接收完整且聚合摘要匹配", actual, now);
                } else {
                    taskRepository.updateStatus(taskId, TaskStatus.INTEGRITY_FAILED);
                    attemptRepository.decide(taskId, attemptNo, TaskStatus.INTEGRITY_FAILED.name(),
                            "聚合摘要不匹配: 要求 " + manifest.aggregateDigest() + "，实际 " + actual,
                            actual, now);
                }
            }
            return currentView(taskId);
        }, IntegrityView.class);
    }

    /**
     * 校验批量提交与已接收证据合并后的最终集合，返回首个失败原因；null 表示通过。
     */
    private String validateBatch(List<ChunkDigestBody> chunks, int chunkCount,
                                 Set<Integer> receivedIndices, Map<Integer, String> expected) {
        Set<Integer> batchSeen = new HashSet<>();
        for (ChunkDigestBody chunk : chunks) {
            if (!batchSeen.add(chunk.index())) {
                return "重复序号: " + chunk.index();
            }
        }
        for (ChunkDigestBody chunk : chunks) {
            if (chunk.index() >= chunkCount) {
                return "序号越界: " + chunk.index() + "，要求范围 0~" + (chunkCount - 1);
            }
        }
        for (ChunkDigestBody chunk : chunks) {
            if (receivedIndices.contains(chunk.index())) {
                return "重复接收序号: " + chunk.index();
            }
        }
        for (ChunkDigestBody chunk : chunks) {
            String expectedDigest = expected.get(chunk.index());
            if (expectedDigest != null && !expectedDigest.equals(chunk.digest())) {
                return "序号 " + chunk.index() + " 摘要不匹配: 要求 " + expectedDigest
                        + "，实际 " + chunk.digest();
            }
        }
        return null;
    }

    /**
     * 重新拉取：仅未安装任务（PENDING/INSTALLABLE/INTEGRITY_FAILED）可建立新尝试代次，
     * 任务回到 PENDING，旧代次失败记录保留。
     */
    public IntegrityView repull(long taskId, String requestId) {
        String fingerprint = String.join("|", "task.repull", String.valueOf(taskId));
        return idempotency.execute(requestId, "task.repull", fingerprint, () -> {
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            switch (task.status()) {
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_FINISHED",
                        "任务已终结为 " + task.status() + "，不能重新拉取");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，不能重新拉取");
                default -> {
                }
            }
            ReleaseOrder order = releaseRepository.findById(task.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            int newAttempt = task.currentAttempt() + 1;
            taskRepository.startNewAttempt(taskId, newAttempt);
            var manifest = manifestRepository.findManifest(task.releaseId());
            attemptRepository.insert(taskId, newAttempt, order.id(), order.toVersion(),
                    manifest.map(ManifestRepository.ManifestRow::aggregateDigest).orElse(null),
                    manifest.map(ManifestRepository.ManifestRow::chunkCount).orElse(0),
                    Instant.now(clock).toString());
            return currentView(taskId);
        }, IntegrityView.class);
    }

    /**
     * 当前代次完整性诊断（只读，不改变状态）。
     */
    public IntegrityView diagnose(long taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        return currentView(taskId);
    }

    /**
     * 全部尝试代次历史（只读，不改变状态），含固化的判定证据与分片接收明细。
     */
    public AttemptHistoryResponse history(long taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        List<ChunkReceipt> receipts = chunkReceiptRepository.findByTask(taskId);
        List<AttemptView> attempts = attemptRepository.findByTask(taskId).stream()
                .map(attempt -> AttemptView.of(attempt, receipts.stream()
                        .filter(r -> r.attemptNo() == attempt.attemptNo())
                        .map(ReceiptView::of)
                        .toList()))
                .toList();
        return new AttemptHistoryResponse(taskId, attempts);
    }

    /**
     * 读取任务当前代次并组装诊断视图；数量字段返回实际值、要求值与差额。
     */
    private IntegrityView currentView(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        TaskAttempt attempt = attemptRepository.find(taskId, task.currentAttempt())
                .orElseThrow(() -> new IllegalStateException("尝试代次记录缺失: " + taskId));
        List<ChunkReceipt> receipts = chunkReceiptRepository.findByAttempt(taskId, task.currentAttempt());
        Set<Integer> received = new HashSet<>();
        for (ChunkReceipt receipt : receipts) {
            received.add(receipt.chunkIndex());
        }
        List<Integer> missing = new ArrayList<>(new TreeSet<>(java.util.stream.IntStream
                .range(0, attempt.requiredCount()).boxed().filter(i -> !received.contains(i)).toList()));
        return new IntegrityView(task.id(), attempt.attemptNo(), task.status().name(),
                received.size(), attempt.requiredCount(), missing.size(), missing,
                attempt.verdict(), attempt.verdictReason(), attempt.expectedAggregate(),
                attempt.actualAggregate(), attempt.decidedAtUtc());
    }
}
