package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseTargetView;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.api.TargetExpressionView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.TagExpression;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.DeviceTagRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ReleaseTargetRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TagDictMetaRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 发布单生命周期：创建（版本从1开始，启用目标策略时一次性解析并固化目标设备快照）、
 * 扩量（版本校验+只增不减）、失败率自动暂停、人工恢复（版本加一并开启新监控轮次）、
 * 取消（未终结任务转 CANCELLED）。快照仅在创建时解析一次，标签修订不追溯扩大或缩小目标。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final ReleaseTargetRepository releaseTargetRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceTagRepository deviceTagRepository;
    private final TagDictMetaRepository dictMetaRepository;
    private final IdempotencyService idempotency;
    private final TagPolicy tagPolicy;
    private final Fingerprints fingerprints;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          ReleaseTargetRepository releaseTargetRepository,
                          DeviceRepository deviceRepository, DeviceTagRepository deviceTagRepository,
                          TagDictMetaRepository dictMetaRepository,
                          IdempotencyService idempotency, TagPolicy tagPolicy, Fingerprints fingerprints,
                          ObjectMapper objectMapper, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.releaseTargetRepository = releaseTargetRepository;
        this.deviceRepository = deviceRepository;
        this.deviceTagRepository = deviceTagRepository;
        this.dictMetaRepository = dictMetaRepository;
        this.idempotency = idempotency;
        this.tagPolicy = tagPolicy;
        this.fingerprints = fingerprints;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        // 表达式规范化与语义校验在事务外完成：非法请求 422 且不占幂等键
        TagExpression expression = request.target() == null ? null
                : tagPolicy.normalizeExpression(request.target());

        TreeMap<String, Object> fp = fingerprints.builder("release.create");
        fp.put("model", request.model());
        fp.put("fromVersion", request.fromVersion());
        fp.put("toVersion", request.toVersion());
        fp.put("ratio", request.ratio());
        fp.put("sampleFloor", sampleFloor);
        fp.put("failureThresholdPercent", threshold);
        fp.put("operator", request.operator());
        if (expression != null) {
            TreeMap<String, Object> targetFp = new TreeMap<>();
            targetFp.put(TagExpression.INCLUDE, expression.include());
            targetFp.put(TagExpression.EXCLUDE, expression.exclude());
            targetFp.put(TagExpression.ANY_OF, expression.anyOf());
            fp.put("target", targetFp);
        } else {
            fp.put("target", null);
        }
        String fingerprint = fingerprints.canonical(fp);

        final TagExpression targetExpression = expression;
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            String expressionJson = null;
            Long dictVersion = null;
            String resolvedAtUtc = null;
            List<String> snapshot = null;
            if (targetExpression != null) {
                // 锁字典版本行：与批量标签修改按提交顺序串行，快照只按一个已提交版本解析
                dictVersion = dictMetaRepository.findForUpdate().version();
                snapshot = resolveSnapshot(request.model(), targetExpression);
                if (snapshot.isEmpty()) {
                    throw ApiException.unprocessable("TARGET_EMPTY",
                            "目标标签表达式未匹配到任何设备，发布单不创建: model=" + request.model());
                }
                resolvedAtUtc = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
                expressionJson = writeExpression(targetExpression);
            }
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold, request.operator(),
                        expressionJson, dictVersion, resolvedAtUtc);
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS",
                        "型号已存在未终结发布单: " + request.model());
            }
            if (snapshot != null) {
                releaseTargetRepository.insertSnapshot(id, snapshot);
            }
            ReleaseOrder created = findOrder(id);
            return toView(created, targetExpression, snapshot == null ? null : snapshot.size());
        }, ReleaseView.class);
    }

    /**
     * 按型号读取候选设备与标签，依据表达式在内存解析目标设备；结果按 device_id 字典序排序。
     * 调用方已持有字典版本行锁，解析期间标签表不会被并发批量修改（H2 行锁 + 单一元数据行串行点）。
     */
    private List<String> resolveSnapshot(String model, TagExpression expression) {
        List<Device> devices = deviceRepository.findByModel(model);
        Map<String, List<String>> tagsByDevice = deviceTagRepository.findTagsByModel(model);
        TreeSet<String> matched = new TreeSet<>();
        for (Device device : devices) {
            Set<String> tags = new HashSet<>(tagsByDevice.getOrDefault(device.deviceId(), List.of()));
            if (tagPolicy.matches(tags, expression)) {
                matched.add(device.deviceId());
            }
        }
        return List.copyOf(matched);
    }

    public ReleaseView expand(long releaseId, ExpandReleaseRequest request) {
        TreeMap<String, Object> fp = fingerprints.builder("release.expand");
        fp.put("releaseId", releaseId);
        fp.put("expectedVersion", request.expectedVersion());
        fp.put("ratio", request.ratio());
        String fingerprint = fingerprints.canonical(fp);
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
            return toView(findOrder(releaseId), null, null);
        }, ReleaseView.class);
    }

    /**
     * 人工恢复：仅 PAUSED 可恢复为 ACTIVE；版本加一、开启新监控轮次、本轮统计清零。
     * 与取消并发时按行锁提交顺序决定唯一结果：取消先提交则恢复 409。
     */
    public ReleaseView resume(long releaseId, ResumeReleaseRequest request) {
        TreeMap<String, Object> fp = fingerprints.builder("release.resume");
        fp.put("releaseId", releaseId);
        fp.put("expectedVersion", request.expectedVersion());
        fp.put("reason", request.reason());
        String fingerprint = fingerprints.canonical(fp);
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
            return toView(resumed, null, null);
        }, ReleaseView.class);
    }

    public ReleaseView cancel(long releaseId, String requestId) {
        TreeMap<String, Object> fp = fingerprints.builder("release.cancel");
        fp.put("releaseId", releaseId);
        String fingerprint = fingerprints.canonical(fp);
        return idempotency.execute(requestId, "release.cancel", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.status() == ReleaseStatus.ACTIVE || order.status() == ReleaseStatus.PAUSED) {
                releaseRepository.cancel(releaseId);
                taskRepository.cancelPendingByRelease(releaseId);
            }
            return toView(findOrder(releaseId), null, null);
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
     * 目标设备快照明细与固化策略诊断（只读）。启用策略时返回表达式、字典版本、解析时刻、规范排序成员。
     */
    public ReleaseTargetView target(long releaseId) {
        ReleaseOrder order = findOrder(releaseId);
        if (order.tagExpressionJson() == null) {
            return new ReleaseTargetView(false, null, null, null, null, List.of());
        }
        TagExpression expression = readExpression(order);
        List<String> members = releaseTargetRepository.findMembers(releaseId);
        return new ReleaseTargetView(true, order.tagDictVersion(), order.resolvedAtUtc(),
                TargetExpressionView.of(expression), members.size(), members);
    }

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }

    /**
     * 统一构造完整发布单视图：启用策略时解析固化表达式并附带快照成员数。
     *
     * @param cachedExpression 调用方已持有的表达式可直接复用，避免重复反序列化；null 则按需解析
     * @param cachedSize       调用方已知的快照成员数；null 则按需查询
     */
    private ReleaseView toView(ReleaseOrder order, TagExpression cachedExpression, Integer cachedSize) {
        if (order.tagExpressionJson() == null) {
            return ReleaseView.of(order, null, null);
        }
        TagExpression expression = cachedExpression != null ? cachedExpression : readExpression(order);
        int size = cachedSize != null ? cachedSize
                : (int) releaseTargetRepository.countMembers(order.id());
        return ReleaseView.of(order, TargetExpressionView.of(expression), size);
    }

    private String writeExpression(TagExpression expression) {
        try {
            return objectMapper.writeValueAsString(expression);
        } catch (Exception e) {
            throw new IllegalStateException("固化标签表达式序列化失败", e);
        }
    }

    private TagExpression readExpression(ReleaseOrder order) {
        try {
            return objectMapper.readValue(order.tagExpressionJson(), TagExpression.class);
        } catch (Exception e) {
            throw new IllegalStateException("发布单固化表达式解析失败: " + order.id(), e);
        }
    }
}
