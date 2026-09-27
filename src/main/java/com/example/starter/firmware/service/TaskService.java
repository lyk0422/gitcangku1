package com.example.starter.firmware.service;

import com.example.starter.firmware.api.FragmentReceiptRequest;
import com.example.starter.firmware.api.FragmentReceiptResponse;
import com.example.starter.firmware.api.FragmentView;
import com.example.starter.firmware.api.PullResponse;
import com.example.starter.firmware.api.ReceiptRequest;
import com.example.starter.firmware.api.TaskDiagnosticsResponse;
import com.example.starter.firmware.api.TaskListResponse;
import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.IntegrityFailureReason;
import com.example.starter.firmware.domain.ReceiptResult;
import com.example.starter.firmware.domain.ReleaseFragment;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.RolloutTask;
import com.example.starter.firmware.domain.TaskFragmentReceipt;
import com.example.starter.firmware.domain.TaskStatus;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.FragmentRepository;
import com.example.starter.firmware.repo.IntegrityEventRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskFragmentRepository;
import com.example.starter.firmware.repo.TaskRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投放任务：设备拉取（含分片清单与尝试代次）、逐分片/批量接收摘要与完整性裁决、
 * 任务撤销、安装回执与诊断查询。
 *
 * 一致性要点：
 * 1. 所有写操作统一按"发布单行锁 -> 任务行锁"顺序加锁，与扩量/撤回/恢复形成全局一致的提交顺序；
 * 2. 分片接收先在事务内校验完整最终集合（范围、重复、逐片摘要、聚合摘要），再随证据/事件/任务状态同事务提交，
 *    任一失败只产生 INTEGRITY_FAILED，不留下部分可安装状态；
 * 3. 同任务同代次同序号由唯一约束兜底，并发提交最多一笔写入成功；
 * 4. 完整性失败禁止安装与成功回执，且不写入发布单失败率统计；
 * 5. 重新拉取仅为 INTEGRITY_FAILED 任务开启新代次，旧代次接收证据与事件永久保留。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskFragmentRepository taskFragmentRepository;
    private final IntegrityEventRepository integrityEventRepository;
    private final FragmentRepository fragmentRepository;
    private final ReleaseRepository releaseRepository;
    private final DeviceRepository deviceRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public TaskService(TaskRepository taskRepository, TaskFragmentRepository taskFragmentRepository,
                       IntegrityEventRepository integrityEventRepository, FragmentRepository fragmentRepository,
                       ReleaseRepository releaseRepository, DeviceRepository deviceRepository,
                       PauseRecordRepository pauseRecordRepository,
                       DeviceService deviceService, ReleaseService releaseService,
                       IdempotencyService idempotency, Clock clock) {
        this.taskRepository = taskRepository;
        this.taskFragmentRepository = taskFragmentRepository;
        this.integrityEventRepository = integrityEventRepository;
        this.fragmentRepository = fragmentRepository;
        this.releaseRepository = releaseRepository;
        this.deviceRepository = deviceRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /**
     * 设备拉取：已存在任务直接返回（完整性失败的未安装任务开启新尝试代次）；
     * 否则仅当型号与当前版本匹配、分桶号小于比例且发布单 ACTIVE 时创建。
     * PAUSED 时不创建新任务，已有任务仍可查看、续传与回执。响应携带登记分片清单与完整包摘要。
     */
    public PullResponse pull(String deviceId, String requestId) {
        String fingerprint = String.join("|", "task.pull", deviceId);
        return idempotency.execute(requestId, "task.pull", fingerprint, () -> {
            Device device = deviceService.findDevice(deviceId);
            var activeOrder = releaseRepository.findActiveByModel(device.model());
            if (activeOrder.isEmpty()) {
                // 无未终结发布单（如发布单已撤回）：仍返回设备既有任务以便查看，不再下发清单且不改写任何证据。
                return taskRepository.findLatestByDevice(deviceId)
                        .map(task -> new PullResponse(TaskView.of(task, releaseService.findOrder(task.releaseId()))))
                        .orElseGet(() -> new PullResponse(null));
            }
            ReleaseOrder order = releaseRepository.findByIdForUpdate(activeOrder.get().id())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            var existing = taskRepository.findByReleaseAndDevice(order.id(), deviceId);
            if (existing.isPresent()) {
                RolloutTask task = existing.get();
                if (task.status() == TaskStatus.INTEGRITY_FAILED) {
                    // 完整性失败后的重新拉取：乐观开启新代次，并发撤回把任务改为 CANCELLED 时影响行数为0。
                    if (taskRepository.startNewAttempt(task.id(), task.attempt()) == 1) {
                        task = taskRepository.findById(task.id())
                                .orElseThrow(() -> new IllegalStateException("新代次任务读取失败"));
                    } else {
                        task = taskRepository.findById(task.id())
                                .orElseThrow(() -> new IllegalStateException("任务裁决后读取失败"));
                    }
                }
                return new PullResponse(TaskView.of(task, order), manifestOf(order, task.attempt()));
            }
            if (order.status() != ReleaseStatus.ACTIVE
                    || !device.currentVersion().equals(order.fromVersion())
                    || device.bucketNo() >= order.ratio()) {
                return new PullResponse(null);
            }
            long taskId;
            try {
                taskId = taskRepository.insert(order.id(), deviceId);
            } catch (DuplicateKeyException e) {
                RolloutTask task = taskRepository.findByReleaseAndDevice(order.id(), deviceId)
                        .orElseThrow(() -> new IllegalStateException("任务唯一约束冲突后未找到任务"));
                return new PullResponse(TaskView.of(task, order), manifestOf(order, task.attempt()));
            }
            RolloutTask task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalStateException("任务创建后读取失败"));
            return new PullResponse(TaskView.of(task, order), manifestOf(order, task.attempt()));
        }, PullResponse.class);
    }

    /**
     * 分片接收（单片或批量）：在同一事务内固化每条接收证据、校验完整最终集合并给出裁决。
     * 缺失时停留 PENDING；全部分片存在、顺序完整且聚合摘要匹配才转 INSTALLABLE；
     * 重复异摘要或任一摘要不匹配转 INTEGRITY_FAILED。并发同序号冲突由唯一约束抛出 409。
     */
    public FragmentReceiptResponse receiveFragments(long taskId, FragmentReceiptRequest request) {
        String fingerprint = String.join("|", "task.fragment", String.valueOf(taskId),
                String.valueOf(request.fragments().size()),
                request.fragments().stream()
                        .map(fragment -> fragment.seqNo() + ":" + fragment.digest())
                        .sorted().reduce((a, b) -> a + "," + b).orElseThrow());
        return idempotency.execute(request.requestId(), "task.fragment", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));

            return switch (task.status()) {
                case SUCCESS, FAILED -> throw ApiException.conflict("TASK_ALREADY_FINISHED",
                        "任务已安装终结为 " + task.firstResult() + "，不再受理分片");
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已撤销，分片不再受理");
                case INTEGRITY_FAILED -> throw ApiException.conflict("ATTEMPT_FAILED_REPULL_REQUIRED",
                        "当前尝试代次 " + task.attempt() + " 已完整性失败，请重新拉取建立新代次");
                case INSTALLABLE -> throw ApiException.conflict("TASK_ALREADY_VERIFIED",
                        "任务已判定可安装，分片接收已终结；重传请使用原 requestId 重放");
                case PENDING -> acceptPending(task, order, request);
            };
        }, FragmentReceiptResponse.class);
    }

    /**
     * PENDING 代次的接收：校验完整最终集合后随证据、事件、任务状态原子提交。
     */
    private FragmentReceiptResponse acceptPending(RolloutTask task, ReleaseOrder order,
                                                  FragmentReceiptRequest request) {
        int attempt = task.attempt();
        Map<Integer, String> expected = expectedDigestMap(order.id());
        if (expected.size() != order.fragmentCount()) {
            throw new IllegalStateException("登记分片数量与发布单不一致: release=" + order.id());
        }
        String now = UtcTimes.now(clock);

        // 已固化证据（当前代次），只增不改。
        Map<Integer, TaskFragmentReceipt> existing = new LinkedHashMap<>();
        for (TaskFragmentReceipt receipt : taskFragmentRepository.findByTaskAndAttempt(task.id(), attempt)) {
            existing.put(receipt.seqNo(), receipt);
        }

        // 先校验完整最终集合：同批内同序号异摘要即重复失败；序号越界为请求格式错误。
        Map<Integer, String> batchBySeq = new LinkedHashMap<>();
        for (FragmentReceiptRequest.ReceivedFragment fragment : request.fragments()) {
            String previous = batchBySeq.putIfAbsent(fragment.seqNo(), fragment.digest());
            if (previous != null && !previous.equals(fragment.digest())) {
                return failIntegrity(task, order, request, now, IntegrityFailureReason.DUPLICATE_DIGEST,
                        fragment.seqNo(), existing);
            }
        }

        for (FragmentReceiptRequest.ReceivedFragment fragment : request.fragments()) {
            String want = expected.get(fragment.seqNo());
            if (want == null) {
                throw ApiException.badRequest("FRAGMENT_SEQ_OUT_OF_RANGE",
                        "分片序号超出登记范围 0~" + (order.fragmentCount() - 1) + ": " + fragment.seqNo());
            }
            TaskFragmentReceipt prior = existing.get(fragment.seqNo());
            if (prior != null && !prior.receivedDigest().equals(fragment.digest())) {
                return failIntegrity(task, order, request, now, IntegrityFailureReason.DUPLICATE_DIGEST,
                        fragment.seqNo(), existing);
            }
            if (prior != null) {
                // 同序号已由另一笔请求固化：重复提交不产生第二笔成功，重试应复用原 requestId 重放。
                throw ApiException.conflict("FRAGMENT_SEQ_CONFLICT",
                        "分片 " + fragment.seqNo() + " 已被另一笔请求接收，请用原 requestId 重放");
            }
            if (!want.equals(fragment.digest())) {
                return failIntegrity(task, order, request, now, IntegrityFailureReason.MISMATCH_DIGEST,
                        fragment.seqNo(), existing);
            }
        }

        // 校验通过：固化新分片证据与 RECEIVED 事件（同摘要重传不重复落证据）。
        for (FragmentReceiptRequest.ReceivedFragment fragment : request.fragments()) {
            if (existing.containsKey(fragment.seqNo())) {
                continue;
            }
            try {
                taskFragmentRepository.insert(task.id(), attempt, fragment.seqNo(), fragment.digest(),
                        expected.get(fragment.seqNo()), order.version(), now, request.requestId());
            } catch (DuplicateKeyException e) {
                // 并发提交同序号：本事务回滚（不留下部分写入），由唯一约束裁决最多一笔成功。
                throw ApiException.conflict("FRAGMENT_SEQ_CONFLICT",
                        "分片 " + fragment.seqNo() + " 已被并发请求接收");
            }
            integrityEventRepository.insertReceived(task.id(), attempt, order.id(), order.version(),
                    fragment.seqNo(), expected.get(fragment.seqNo()), fragment.digest(), now);
            existing.put(fragment.seqNo(), new TaskFragmentReceipt(0, task.id(), attempt, fragment.seqNo(),
                    fragment.digest(), expected.get(fragment.seqNo()), order.version(), now,
                    request.requestId()));
        }

        // 全部分片到齐才做聚合判定；未收齐停留 PENDING，等待后续提交。
        if (existing.size() < order.fragmentCount()) {
            RolloutTask refreshed = taskRepository.findById(task.id()).orElseThrow();
            return buildReceiptResponse(refreshed, order, null);
        }

        List<String> orderedDigests = new ArrayList<>();
        for (int seq = 0; seq < order.fragmentCount(); seq++) {
            orderedDigests.add(existing.get(seq).receivedDigest());
        }
        String aggregateActual = DigestSupport.aggregate(orderedDigests);
        if (!aggregateActual.equals(order.packageDigest())) {
            return failIntegrityAggregate(task, order, now, aggregateActual);
        }

        String verdictAt = UtcTimes.now(clock);
        taskRepository.markInstallable(task.id(), attempt, verdictAt);
        integrityEventRepository.insertVerdict(task.id(), attempt,
                com.example.starter.firmware.domain.IntegrityEvent.INSTALLABLE,
                order.id(), order.version(), order.packageDigest(), aggregateActual, null, verdictAt);
        RolloutTask installable = taskRepository.findById(task.id()).orElseThrow();
        return buildReceiptResponse(installable, order, aggregateActual);
    }

    /**
     * 完整性失败落库：任务转 INTEGRITY_FAILED、失败事件固化（含期望/实际聚合值与时刻）。
     * 本批提交的每条分片（含触发失败的问题分片，其错误摘要与期望摘要并列留证）都固化接收证据，
     * 但不存在任何可安装状态。
     */
    private FragmentReceiptResponse failIntegrity(RolloutTask task, ReleaseOrder order,
                                                  FragmentReceiptRequest request, String now,
                                                  IntegrityFailureReason reason, int offendingSeq,
                                                  Map<Integer, TaskFragmentReceipt> existingBefore) {
        Map<Integer, String> expected = expectedDigestMap(order.id());
        java.util.Set<Integer> insertedInThisFailure = new java.util.HashSet<>();
        for (FragmentReceiptRequest.ReceivedFragment fragment : request.fragments()) {
            int seq = fragment.seqNo();
            String want = expected.get(seq);
            if (want == null || existingBefore.containsKey(seq) || !insertedInThisFailure.add(seq)) {
                continue;
            }
            try {
                taskFragmentRepository.insert(task.id(), task.attempt(), seq, fragment.digest(),
                        want, order.version(), now, request.requestId());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("FRAGMENT_SEQ_CONFLICT", "分片 " + seq + " 已被并发请求接收");
            }
            integrityEventRepository.insertReceived(task.id(), task.attempt(), order.id(), order.version(),
                    seq, want, fragment.digest(), now);
        }
        String verdictAt = UtcTimes.now(clock);
        taskRepository.markIntegrityFailed(task.id(), task.attempt(), reason.name());
        integrityEventRepository.insertVerdict(task.id(), task.attempt(),
                com.example.starter.firmware.domain.IntegrityEvent.INTEGRITY_FAILED,
                order.id(), order.version(), order.packageDigest(), null,
                reason.name() + "@" + offendingSeq, verdictAt);
        RolloutTask failed = taskRepository.findById(task.id()).orElseThrow();
        return buildReceiptResponse(failed, order, null);
    }

    /**
     * 全量到齐但聚合摘要不匹配：不补写证据（逐片均已落库），只落聚合失败裁决。
     */
    private FragmentReceiptResponse failIntegrityAggregate(RolloutTask task, ReleaseOrder order,
                                                           String now, String aggregateActual) {
        taskRepository.markIntegrityFailed(task.id(), task.attempt(),
                IntegrityFailureReason.AGGREGATE_MISMATCH.name());
        integrityEventRepository.insertVerdict(task.id(), task.attempt(),
                com.example.starter.firmware.domain.IntegrityEvent.INTEGRITY_FAILED,
                order.id(), order.version(), order.packageDigest(), aggregateActual,
                IntegrityFailureReason.AGGREGATE_MISMATCH.name(), now);
        RolloutTask failed = taskRepository.findById(task.id()).orElseThrow();
        return buildReceiptResponse(failed, order, aggregateActual);
    }

    /**
     * 安装回执：仅 INSTALLABLE 受理；首次回执终结任务并计入完成时所在监控轮次，仅 SUCCESS 更新设备版本。
     * PENDING/INTEGRITY_FAILED 一律拒绝（完整性失败不计入失败率）；同结果重复成功，改结果 409；
     * 已撤销任务的后到回执 409 且不更新设备版本。样本越限同事务原子暂停。
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
                case INSTALLABLE -> {
                    taskRepository.complete(taskId, request.result());
                    releaseRepository.incrementRoundStats(snapshot.releaseId(), request.result());
                    if (request.result() == ReceiptResult.SUCCESS) {
                        deviceRepository.updateCurrentVersion(task.deviceId(), lockedOrder.toVersion());
                    }
                    ReleaseOrder updated = releaseRepository.findById(snapshot.releaseId()).orElseThrow();
                    pauseIfThresholdReached(updated, taskId);
                    yield TaskView.of(taskRepository.findById(taskId).orElseThrow(), updated);
                }
                case PENDING -> throw ApiException.conflict("NOT_INSTALLABLE",
                        "分片尚未接收完整，任务未进入可安装状态，不能安装回执");
                case INTEGRITY_FAILED -> throw ApiException.conflict("INTEGRITY_INSTALL_BLOCKED",
                        "任务完整性失败，禁止安装和成功回执（且不计入设备执行失败率）");
                case SUCCESS, FAILED -> {
                    if (task.firstResult() == request.result()) {
                        yield TaskView.of(task, lockedOrder);
                    }
                    throw ApiException.conflict("RECEIPT_RESULT_CONFLICT",
                            "任务已终结为 " + task.firstResult() + "，不能改为 " + request.result());
                }
                case CANCELLED -> throw ApiException.conflict("TASK_CANCELLED", "任务已撤销，回执不再受理");
            };
        }, TaskView.class);
    }

    /**
     * 撤销单个未安装任务：PENDING/INSTALLABLE/INTEGRITY_FAILED 转 CANCELLED，旧证据与事件保留；
     * 安装终态 SUCCESS/FAILED 不可撤销（409）；重复撤销幂等返回。与分片接收/回执按统一锁顺序裁决。
     */
    public TaskView cancelTask(long taskId, String requestId) {
        String fingerprint = String.join("|", "task.cancel", String.valueOf(taskId));
        return idempotency.execute(requestId, "task.cancel", fingerprint, () -> {
            RolloutTask snapshot = taskRepository.findById(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            ReleaseOrder order = releaseRepository.findByIdForUpdate(snapshot.releaseId())
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在"));
            RolloutTask task = taskRepository.findByIdForUpdate(taskId)
                    .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
            if (task.status() == TaskStatus.SUCCESS || task.status() == TaskStatus.FAILED) {
                throw ApiException.conflict("TASK_ALREADY_FINISHED",
                        "任务已安装终结为 " + task.firstResult() + "，不可撤销");
            }
            if (task.status() != TaskStatus.CANCELLED) {
                taskRepository.cancelIfUnfinished(taskId);
            }
            return TaskView.of(taskRepository.findById(taskId).orElseThrow(), order);
        }, TaskView.class);
    }

    /**
     * 任务完整性诊断（只读）：登记清单、各代次接收证据（含期望摘要快照与是否匹配）与全部事件流水。
     */
    public TaskDiagnosticsResponse diagnostics(long taskId) {
        RolloutTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在: " + taskId));
        ReleaseOrder order = releaseService.findOrder(task.releaseId());
        var receipts = taskFragmentRepository.findByTask(taskId);
        var events = integrityEventRepository.findByTask(taskId);

        Map<Integer, List<TaskFragmentReceipt>> byAttempt = new LinkedHashMap<>();
        for (TaskFragmentReceipt receipt : receipts) {
            byAttempt.computeIfAbsent(receipt.attempt(), key -> new ArrayList<>()).add(receipt);
        }
        List<TaskDiagnosticsResponse.AttemptView> attempts = new ArrayList<>();
        for (int attemptNo = 1; attemptNo <= task.attempt(); attemptNo++) {
            List<TaskFragmentReceipt> attemptReceipts = byAttempt.getOrDefault(attemptNo, List.of());
            var fragmentViews = attemptReceipts.stream()
                    .map(TaskDiagnosticsResponse.FragmentEvidenceView::of).toList();
            attempts.add(new TaskDiagnosticsResponse.AttemptView(attemptNo, attemptReceipts.size(),
                    fragmentViews));
        }
        var eventViews = events.stream().map(TaskDiagnosticsResponse.EventView::of).toList();
        return new TaskDiagnosticsResponse(TaskView.of(task, order), order.fragmentCount(),
                order.packageDigest(), attempts, eventViews);
    }

    public TaskListResponse listByRelease(long releaseId, TaskStatus statusFilter) {
        ReleaseOrder order = releaseService.findOrder(releaseId);
        List<TaskView> tasks = taskRepository.findByRelease(releaseId, statusFilter).stream()
                .map(task -> TaskView.of(task, order))
                .toList();
        return new TaskListResponse(tasks);
    }

    /**
     * 样本数达到下限且 FAILED×100 >= 样本数×阈值 时，将仍为 ACTIVE 的发布单原子转为 PAUSED。
     * 调用方持有发布单行锁，并发回执串行通过，每轮至多生成一条暂停记录。
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
                    order.roundSuccess(), order.roundFailed(), UtcTimes.now(clock));
        }
    }

    private Map<Integer, String> expectedDigestMap(long releaseId) {
        Map<Integer, String> expected = new LinkedHashMap<>();
        for (ReleaseFragment fragment : fragmentRepository.findByRelease(releaseId)) {
            expected.put(fragment.seqNo(), fragment.digest());
        }
        return expected;
    }

    private PullResponse.FragmentManifest manifestOf(ReleaseOrder order, int attempt) {
        List<FragmentView> fragments = fragmentRepository.findByRelease(order.id()).stream()
                .map(FragmentView::of).toList();
        return new PullResponse.FragmentManifest(attempt, order.packageDigest(), fragments);
    }

    /**
     * 组装接收响应：实际已收数、要求总数、缺失差额与缺失序号、要求/实际聚合摘要、失败原因代码。
     */
    private FragmentReceiptResponse buildReceiptResponse(RolloutTask task, ReleaseOrder order,
                                                         String aggregateActual) {
        List<TaskFragmentReceipt> receipts =
                taskFragmentRepository.findByTaskAndAttempt(task.id(), task.attempt());
        Map<Integer, TaskFragmentReceipt> received = new LinkedHashMap<>();
        for (TaskFragmentReceipt receipt : receipts) {
            received.put(receipt.seqNo(), receipt);
        }
        List<Integer> missingSeqs = new ArrayList<>();
        for (int seq = 0; seq < order.fragmentCount(); seq++) {
            if (!received.containsKey(seq)) {
                missingSeqs.add(seq);
            }
        }
        String failureCode = task.status() == TaskStatus.INTEGRITY_FAILED ? task.integrityReason() : null;
        String actualAggregate = aggregateActual;
        if (actualAggregate == null && received.size() == order.fragmentCount()) {
            List<String> ordered = new ArrayList<>();
            for (int seq = 0; seq < order.fragmentCount(); seq++) {
                ordered.add(received.get(seq).receivedDigest());
            }
            actualAggregate = DigestSupport.aggregate(ordered);
        }
        return new FragmentReceiptResponse(TaskView.of(task, order), task.attempt(), order.fragmentCount(),
                received.size(), missingSeqs.size(), missingSeqs, order.packageDigest(), actualAggregate,
                failureCode);
    }
}
