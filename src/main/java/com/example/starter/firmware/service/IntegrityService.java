package com.example.starter.firmware.service;

import com.example.starter.firmware.api.IntegrityEventView;
import com.example.starter.firmware.api.ReceiveShardsRequest;
import com.example.starter.firmware.api.RegisterShardsRequest;
import com.example.starter.firmware.api.ReleaseIntegrityView;
import com.example.starter.firmware.api.ShardManifestView;
import com.example.starter.firmware.api.ShardReceiptView;
import com.example.starter.firmware.api.ShardReceiveResponse;
import com.example.starter.firmware.api.TaskIntegrityView;
import com.example.starter.firmware.domain.Digests;
import com.example.starter.firmware.domain.IntegrityEvent;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseShard;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.ShardReceipt;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.IntegrityEventRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ReleaseShardRepository;
import com.example.starter.firmware.repo.ShardReceiptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 固件分片完整性：发布版本分片清单登记、设备分片接收与完整性判定、证据与事件查询。
 *
 * <p>判定规则：分片序号从0连续；设备按批提交接收摘要，同代次同序号重复、序号越界或
 * 任一摘要不匹配时整批判定 INTEGRITY_FAILED（禁止安装与成功回执，不计设备执行失败率）；
 * 全部分片存在且聚合摘要匹配时任务进入 INSTALLABLE。每次判定固化发布版本、分片摘要、
 * 聚合结果与UTC时刻，证据与事件只增不改。批量接收先校验完整最终集合，再在一个事务提交。
 */
@Service
public class IntegrityService {

    private final ReleaseShardRepository shardRepository;
    private final ShardReceiptRepository receiptRepository;
    private final IntegrityEventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public IntegrityService(ReleaseShardRepository shardRepository,
                            ShardReceiptRepository receiptRepository,
                            IntegrityEventRepository eventRepository,
                            TaskRepository taskRepository, ReleaseRepository releaseRepository,
                            IdempotencyService idempotency, Clock clock) {
        this.shardRepository = shardRepository;
        this.receiptRepository = receiptRepository;
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 登记发布版本分片清单：重复序号、序号缺口或聚合摘要不匹配返回422；
     * 已有任务拉取后清单不可修改（409），尚无任务时允许整体替换重登。
     */
    public ShardManifestView registerShards(long releaseId, RegisterShardsRequest request) {
        List<RegisterShardsRequest.ShardDigestInput> sorted = request.shards().stream()
                .sorted(Comparator.comparingInt(RegisterShardsRequest.ShardDigestInput::shardNo))
                .toList();
        validateManifest(sorted, request.fullDigest());
        String canonical = sorted.stream()
                .map(s -> s.shardNo() + ":" + s.digest())
                .collect(Collectors.joining(","));
        String fingerprint = String.join("|", "release.shards", String.valueOf(releaseId),
                request.fullDigest(), canonical);
        return idempotency.execute(request.requestId(), "release.shards", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.shardCount() != null && taskRepository.countByRelease(releaseId) > 0) {
                throw ApiException.conflict("SHARD_MANIFEST_LOCKED",
                        "分片清单已有任务拉取，不可修改：当前分片数 " + order.shardCount());
            }
            if (order.shardCount() != null) {
                shardRepository.deleteByRelease(releaseId);
            }
            for (RegisterShardsRequest.ShardDigestInput shard : sorted) {
                shardRepository.insert(releaseId, shard.shardNo(), shard.digest());
            }
            releaseRepository.updateShardManifest(releaseId, sorted.size(), request.fullDigest());
            return ShardManifestView.of(releaseId, request.fullDigest(),
                    shardRepository.findByRelease(releaseId));
        }, ShardManifestView.class);
    }

    /**
     * 清单静态校验：重复序号、序号缺口（要求0..n-1连续）、聚合摘要不匹配均返回422，
     * 响应携带实际值与要求值，且不写入任何数据。
     */
    private void validateManifest(List<RegisterShardsRequest.ShardDigestInput> sorted, String fullDigest) {
        List<Integer> duplicates = new ArrayList<>();
        Map<Integer, String> seen = new LinkedHashMap<>();
        for (RegisterShardsRequest.ShardDigestInput shard : sorted) {
            if (seen.putIfAbsent(shard.shardNo(), shard.digest()) != null) {
                duplicates.add(shard.shardNo());
            }
        }
        if (!duplicates.isEmpty()) {
            throw ApiException.unprocessable("SHARD_DUPLICATE",
                    "分片序号重复: " + duplicates + "，要求每个序号唯一");
        }
        List<Integer> missing = new ArrayList<>();
        List<Integer> outOfRange = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            if (!seen.containsKey(i)) {
                missing.add(i);
            }
        }
        for (Integer shardNo : seen.keySet()) {
            if (shardNo < 0 || shardNo >= sorted.size()) {
                outOfRange.add(shardNo);
            }
        }
        if (!missing.isEmpty() || !outOfRange.isEmpty()) {
            throw ApiException.unprocessable("SHARD_GAP",
                    "分片序号必须从0连续：要求 0.." + (sorted.size() - 1) + "（共 " + sorted.size()
                            + " 个），缺失 " + missing + "，越界 " + outOfRange);
        }
        String actual = Digests.aggregateHex(seen.values());
        if (!actual.equals(fullDigest)) {
            throw ApiException.unprocessable("FULL_DIGEST_MISMATCH",
                    "完整包聚合摘要不匹配：登记值 " + fullDigest + "，按分片聚合实际值 " + actual);
        }
    }

    /**
     * 设备批量提交分片接收摘要。同任务同代次按任务行锁串行裁决：
     * 序号越界、同代次重复或摘要不匹配时整批判定 INTEGRITY_FAILED 并固化证据与事件；
     * 全部集齐且聚合摘要匹配时任务原子转为 INSTALLABLE。未集齐则保持 PENDING 继续接收。
     */
    public ShardReceiveResponse receiveShards(long taskId, ReceiveShardsRequest request) {
        Map<Integer, String> batch = new LinkedHashMap<>();
        List<Integer> batchDuplicates = new ArrayList<>();
        for (ReceiveShardsRequest.ReceivedShard shard : request.shards()) {
            if (batch.putIfAbsent(shard.shardNo(), shard.digest()) != null) {
                batchDuplicates.add(shard.shardNo());
            }
        }
        if (!batchDuplicates.isEmpty()) {
            throw ApiException.unprocessable("SHARD_DUPLICATE",
                    "同一批次分片序号重复: " + batchDuplicates + "，要求每批内序号唯一");
        }
        String canonical = request.shards().stream()
                .map(s -> s.shardNo() + ":" + s.digest())
                .collect(Collectors.joining(","));
        String fingerprint = String.join("|", "task.shards", String.valueOf(taskId), canonical);
        return idempotency.execute(request.requestId(), "task.shards", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            switch (task.status()) {
                case PENDING -> {
                }
                case INSTALLABLE -> throw ApiException.conflict("TASK_ALREADY_INSTALLABLE",
                        "本代次已核验通过，分片接收已关闭");
                case INTEGRITY_FAILED -> throw ApiException.conflict("TASK_INTEGRITY_FAILED",
                        "本代次已判定完整性失败，请重新拉取建立新尝试代次");
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_COMPLETED",
                        "任务已终结为 " + task.status() + "，分片接收已关闭");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已取消，分片接收已关闭");
                default -> throw new IllegalStateException("未知任务状态: " + task.status());
            }
            List<ReleaseShard> manifest = shardRepository.findByRelease(order.id());
            if (manifest.isEmpty()) {
                throw ApiException.conflict("SHARDS_NOT_REGISTERED",
                        "发布单未登记分片清单，无法接收分片: " + order.id());
            }
            Map<Integer, String> manifestDigests = manifest.stream()
                    .collect(Collectors.toMap(ReleaseShard::shardNo, ReleaseShard::shardDigest));
            List<ShardReceipt> existing = receiptRepository.findByTaskAndAttempt(taskId, task.attemptNo());
            Map<Integer, String> received = new TreeMap<>();
            for (ShardReceipt receipt : existing) {
                received.put(receipt.shardNo(), receipt.shardDigest());
            }

            String now = Instant.now(clock).toString();
            List<Integer> outOfRange = new ArrayList<>();
            List<Integer> duplicates = new ArrayList<>();
            List<Integer> mismatched = new ArrayList<>();
            List<ShardReceipt> toInsert = new ArrayList<>();
            for (Map.Entry<Integer, String> entry : batch.entrySet()) {
                int shardNo = entry.getKey();
                String digest = entry.getValue();
                if (!manifestDigests.containsKey(shardNo)) {
                    outOfRange.add(shardNo);
                }
                if (received.containsKey(shardNo)) {
                    duplicates.add(shardNo);
                    continue;
                }
                if (manifestDigests.containsKey(shardNo) && !manifestDigests.get(shardNo).equals(digest)) {
                    mismatched.add(shardNo);
                }
                toInsert.add(new ShardReceipt(0, taskId, task.attemptNo(), order.id(), order.version(),
                        shardNo, digest, now, request.requestId()));
            }

            if (!outOfRange.isEmpty() || !duplicates.isEmpty() || !mismatched.isEmpty()) {
                for (ShardReceipt receipt : toInsert) {
                    receiptRepository.insert(receipt.taskId(), receipt.attemptNo(), receipt.releaseId(),
                            receipt.releaseVersion(), receipt.shardNo(), receipt.shardDigest(),
                            receipt.receivedAtUtc(), receipt.requestId());
                }
                String reason = !mismatched.isEmpty() ? "SHARD_DIGEST_MISMATCH"
                        : !duplicates.isEmpty() ? "DUPLICATE_SHARD" : "SHARD_OUT_OF_RANGE";
                return failAttempt(task, order, manifest.size(), received, toInsert, reason,
                        duplicates, mismatched, outOfRange, null, now);
            }

            for (ShardReceipt receipt : toInsert) {
                receiptRepository.insert(receipt.taskId(), receipt.attemptNo(), receipt.releaseId(),
                        receipt.releaseVersion(), receipt.shardNo(), receipt.shardDigest(),
                        receipt.receivedAtUtc(), receipt.requestId());
            }
            Map<Integer, String> all = new TreeMap<>(received);
            all.putAll(batch);
            int required = manifest.size();
            if (all.size() < required) {
                List<Integer> missing = missingShards(manifestDigests, all);
                return new ShardReceiveResponse(taskId, task.attemptNo(), "PENDING", null,
                        required, all.size(), missing.size(), missing, List.of(), List.of(), List.of(),
                        null, null);
            }
            String aggregate = Digests.aggregateHex(all.values());
            if (!aggregate.equals(order.fullDigest())) {
                return failAttempt(task, order, required, received, toInsert, "FULL_DIGEST_MISMATCH",
                        List.of(), List.of(), List.of(), aggregate, now);
            }
            insertEvent(task, order, "INSTALLABLE", "OK", required, all.size(), List.of(),
                    List.of(), List.of(), order.fullDigest(), aggregate, now);
            taskRepository.markInstallable(taskId, aggregate);
            return new ShardReceiveResponse(taskId, task.attemptNo(), "INSTALLABLE", "OK",
                    required, all.size(), 0, List.of(), List.of(), List.of(), List.of(),
                    aggregate, now);
        }, ShardReceiveResponse.class);
    }

    /**
     * 整批判定失败：固化接收证据、判定事件与任务状态（INTEGRITY_FAILED），同一事务提交。
     */
    private ShardReceiveResponse failAttempt(RolloutTask task, ReleaseOrder order, int required,
                                             Map<Integer, String> receivedBefore, List<ShardReceipt> toInsert,
                                             String reason, List<Integer> duplicates, List<Integer> mismatched,
                                             List<Integer> outOfRange, String actualFullDigest, String now) {
        Map<Integer, String> covered = new TreeMap<>(receivedBefore);
        for (ShardReceipt receipt : toInsert) {
            covered.put(receipt.shardNo(), receipt.shardDigest());
        }
        Map<Integer, String> manifestDigests = new TreeMap<>();
        for (ReleaseShard shard : shardRepository.findByRelease(order.id())) {
            manifestDigests.put(shard.shardNo(), shard.shardDigest());
        }
        List<Integer> missing = missingShards(manifestDigests, covered);
        insertEvent(task, order, "INTEGRITY_FAILED", reason, required, covered.size(), missing,
                duplicates, mismatched, order.fullDigest(), actualFullDigest, now);
        taskRepository.markIntegrityFailed(task.id());
        return new ShardReceiveResponse(task.id(), task.attemptNo(), "INTEGRITY_FAILED", reason,
                required, covered.size(), missing.size(), missing, duplicates, mismatched, outOfRange,
                actualFullDigest, now);
    }

    private List<Integer> missingShards(Map<Integer, String> manifestDigests, Map<Integer, String> covered) {
        List<Integer> missing = new ArrayList<>();
        for (Integer shardNo : manifestDigests.keySet()) {
            if (!covered.containsKey(shardNo)) {
                missing.add(shardNo);
            }
        }
        return missing;
    }

    private void insertEvent(RolloutTask task, ReleaseOrder order, String result, String reason,
                             int required, int receivedCount, List<Integer> missing,
                             List<Integer> duplicates, List<Integer> mismatched,
                             String expectedFullDigest, String actualFullDigest, String now) {
        eventRepository.insert(new IntegrityEvent(0, task.id(), task.attemptNo(), order.id(),
                order.version(), result, reason, required, receivedCount, missing.size(),
                joinInts(missing), joinInts(duplicates), joinInts(mismatched),
                expectedFullDigest, actualFullDigest, now));
    }

    private String joinInts(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * 发布单分片清单明细（只读）。未登记时 shards 为空、fullDigest 为 null。
     */
    public ShardManifestView manifest(long releaseId) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        return ShardManifestView.of(releaseId, order.fullDigest(), shardRepository.findByRelease(releaseId));
    }

    /**
     * 任务完整性明细：全部代次的接收证据与判定事件（只读，历史不改写）。
     */
    public TaskIntegrityView taskIntegrity(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        List<ShardReceiptView> receipts = receiptRepository.findByTask(taskId).stream()
                .map(ShardReceiptView::of).toList();
        List<IntegrityEventView> events = eventRepository.findByTask(taskId).stream()
                .map(IntegrityEventView::of).toList();
        return new TaskIntegrityView(task.id(), task.releaseId(), task.deviceId(), task.attemptNo(),
                task.status().name(), task.aggregateDigest(), receipts, events);
    }

    /**
     * 发布单完整性诊断：分片清单与全部判定事件（只读）。
     */
    public ReleaseIntegrityView releaseIntegrity(long releaseId) {
        ReleaseOrder order = releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
        List<ShardManifestView.ShardDigestView> shards = shardRepository.findByRelease(releaseId).stream()
                .map(s -> new ShardManifestView.ShardDigestView(s.shardNo(), s.shardDigest()))
                .toList();
        List<IntegrityEventView> events = eventRepository.findByRelease(releaseId).stream()
                .map(IntegrityEventView::of).toList();
        return new ReleaseIntegrityView(releaseId, order.shardCount(), order.fullDigest(), shards, events);
    }
}
