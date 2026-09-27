package com.example.starter.firmware.service;

import com.example.starter.firmware.api.AttemptView;
import com.example.starter.firmware.api.ChunkReceiptView;
import com.example.starter.firmware.api.IntegrityDetailResponse;
import com.example.starter.firmware.api.IntegrityDiagnosticsResponse;
import com.example.starter.firmware.api.IntegrityEventView;
import com.example.starter.firmware.api.IntegrityHistoryResponse;
import com.example.starter.firmware.domain.ChunkEntry;
import com.example.starter.firmware.domain.ChunkReceipt;
import com.example.starter.firmware.domain.IntegrityEvent;
import com.example.starter.firmware.domain.IntegrityEventType;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskAttempt;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.ChunkManifestRepository;
import com.example.starter.firmware.repo.ChunkReceiptRepository;
import com.example.starter.firmware.repo.IntegrityEventRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskAttemptRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片完整性明细、历史与诊断查询。全部为只读操作，不改变任何业务状态；
 * 历史记录按尝试代次、分片序号、事件ID稳定排序，字段与时间精度保持写入时语义。
 */
@Service
public class IntegrityQueryService {

    private final TaskRepository taskRepository;
    private final ReleaseRepository releaseRepository;
    private final ChunkManifestRepository manifestRepository;
    private final ChunkReceiptRepository receiptRepository;
    private final TaskAttemptRepository attemptRepository;
    private final IntegrityEventRepository eventRepository;

    public IntegrityQueryService(TaskRepository taskRepository, ReleaseRepository releaseRepository,
                                 ChunkManifestRepository manifestRepository,
                                 ChunkReceiptRepository receiptRepository,
                                 TaskAttemptRepository attemptRepository,
                                 IntegrityEventRepository eventRepository) {
        this.taskRepository = taskRepository;
        this.releaseRepository = releaseRepository;
        this.manifestRepository = manifestRepository;
        this.receiptRepository = receiptRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * 当前尝试代次的逐分片核验明细。
     */
    public IntegrityDetailResponse detail(long taskId) {
        RolloutTask task = findTask(taskId);
        ReleaseOrder order = findManifestOrder(task);
        TaskAttempt attempt = currentAttempt(taskId);
        Map<Integer, ChunkReceipt> received = receiptsByIndex(taskId, attempt.attemptNo());
        List<IntegrityDetailResponse.ChunkStatusView> chunks =
                manifestRepository.findByRelease(order.id()).stream()
                        .map(entry -> {
                            ChunkReceipt receipt = received.get(entry.chunkIndex());
                            return new IntegrityDetailResponse.ChunkStatusView(entry.chunkIndex(),
                                    entry.digest(),
                                    receipt == null ? null : receipt.digest(),
                                    receipt == null ? null : receipt.receivedAtUtc());
                        })
                        .toList();
        return new IntegrityDetailResponse(task.id(), order.id(), task.deviceId(), task.status().name(),
                attempt.attemptNo(), order.chunkCount(), received.size(),
                order.chunkCount() - received.size(), order.packageDigest(), chunks);
    }

    /**
     * 全部尝试代次的接收证据与判定事件历史。from/to 为 UTC ISO-8601，按左闭右开过滤事件时刻，
     * 缺省不过滤；历史记录只读，后续操作不改写。
     */
    public IntegrityHistoryResponse history(long taskId, String fromUtc, String toUtc) {
        RolloutTask task = findTask(taskId);
        ReleaseOrder order = releaseRepository.findById(task.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        Instant from = parseBoundary(fromUtc, "from");
        Instant to = parseBoundary(toUtc, "to");
        List<AttemptView> attempts = attemptRepository.findByTask(taskId).stream()
                .map(AttemptView::of).toList();
        List<ChunkReceiptView> receipts = receiptRepository.findByTask(taskId).stream()
                .map(ChunkReceiptView::of).toList();
        List<IntegrityEventView> events = eventRepository.findByTask(taskId).stream()
                .filter(event -> inHalfOpenRange(event.occurredAtUtc(), from, to))
                .map(IntegrityEventView::of).toList();
        return new IntegrityHistoryResponse(task.id(), order.id(), task.status().name(),
                attempts, receipts, events);
    }

    /**
     * 当前尝试代次诊断：进度差额、聚合结果与事件计数及明细。
     */
    public IntegrityDiagnosticsResponse diagnostics(long taskId) {
        RolloutTask task = findTask(taskId);
        ReleaseOrder order = findManifestOrder(task);
        TaskAttempt attempt = currentAttempt(taskId);
        List<ChunkReceipt> receipts = receiptRepository.findByTaskAndAttempt(taskId, attempt.attemptNo());
        String aggregate = receipts.isEmpty() ? null
                : Digests.aggregateOf(receipts.stream().map(ChunkReceipt::digest).toList());
        List<IntegrityEvent> events = eventRepository.findByTaskAndAttempt(taskId, attempt.attemptNo());
        int accepted = 0;
        int failed = 0;
        int installable = 0;
        for (IntegrityEvent event : events) {
            if (event.eventType() == IntegrityEventType.CHUNK_ACCEPTED) {
                accepted++;
            } else if (event.eventType() == IntegrityEventType.INTEGRITY_FAILED) {
                failed++;
            } else if (event.eventType() == IntegrityEventType.INSTALLABLE) {
                installable++;
            }
        }
        return new IntegrityDiagnosticsResponse(task.id(), order.id(), task.status().name(),
                attempt.attemptNo(), order.chunkCount(), receipts.size(),
                order.chunkCount() - receipts.size(), order.packageDigest(), aggregate,
                accepted, failed, installable,
                events.stream().map(IntegrityEventView::of).toList());
    }

    private RolloutTask findTask(long taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
    }

    private ReleaseOrder findManifestOrder(RolloutTask task) {
        ReleaseOrder order = releaseRepository.findById(task.releaseId())
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
        if (!order.hasManifest()) {
            throw ApiException.conflict("MANIFEST_NOT_REGISTERED",
                    "发布单未登记分片清单，无完整性核验数据: " + order.id());
        }
        return order;
    }

    private TaskAttempt currentAttempt(long taskId) {
        return attemptRepository.findCurrent(taskId)
                .orElseThrow(() -> new IllegalStateException("清单任务缺少尝试代次: " + taskId));
    }

    private Map<Integer, ChunkReceipt> receiptsByIndex(long taskId, int attemptNo) {
        Map<Integer, ChunkReceipt> byIndex = new HashMap<>();
        receiptRepository.findByTaskAndAttempt(taskId, attemptNo)
                .forEach(receipt -> byIndex.put(receipt.chunkIndex(), receipt));
        return byIndex;
    }

    private static Instant parseBoundary(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_TIME_RANGE",
                    name + " 必须是 UTC ISO-8601 时刻（如 2026-09-27T00:00:00Z），实际: " + value);
        }
    }

    /**
     * 左闭右开区间判断：[from, to)。
     */
    private static boolean inHalfOpenRange(String occurredAtUtc, Instant from, Instant to) {
        Instant occurred = Instant.parse(occurredAtUtc);
        if (from != null && occurred.isBefore(from)) {
            return false;
        }
        return to == null || occurred.isBefore(to);
    }
}
