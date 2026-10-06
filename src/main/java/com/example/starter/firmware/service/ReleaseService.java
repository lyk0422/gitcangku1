package com.example.starter.firmware.service;

import com.example.starter.firmware.api.CreateReleaseRequest;
import com.example.starter.firmware.api.DeviceEdge;
import com.example.starter.firmware.api.ExpandReleaseRequest;
import com.example.starter.firmware.api.MonitorView;
import com.example.starter.firmware.api.PauseRecordView;
import com.example.starter.firmware.api.ReleaseHistoryResponse;
import com.example.starter.firmware.api.ReleaseView;
import com.example.starter.firmware.api.ResumeRecordView;
import com.example.starter.firmware.api.ResumeReleaseRequest;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.ReleaseType;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.PauseRecordRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.ResumeRecordRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.repo.TopologyRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 发布单生命周期：创建（版本从1开始）、扩量（版本校验+只增不减）、失败率自动暂停、
 * 人工恢复（版本加一并开启新监控轮次）、取消（未终结任务转 CANCELLED，拓扑波次停机设备同步释放）。
 */
@Service
public class ReleaseService {

    private final ReleaseRepository releaseRepository;
    private final TaskRepository taskRepository;
    private final PauseRecordRepository pauseRecordRepository;
    private final ResumeRecordRepository resumeRecordRepository;
    private final DeviceRepository deviceRepository;
    private final TopologyRepository topologyRepository;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public ReleaseService(ReleaseRepository releaseRepository, TaskRepository taskRepository,
                          PauseRecordRepository pauseRecordRepository,
                          ResumeRecordRepository resumeRecordRepository,
                          DeviceRepository deviceRepository, TopologyRepository topologyRepository,
                          IdempotencyService idempotency, Clock clock) {
        this.releaseRepository = releaseRepository;
        this.taskRepository = taskRepository;
        this.pauseRecordRepository = pauseRecordRepository;
        this.resumeRecordRepository = resumeRecordRepository;
        this.deviceRepository = deviceRepository;
        this.topologyRepository = topologyRepository;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public ReleaseView create(CreateReleaseRequest request) {
        if (request.fromVersion().equals(request.toVersion())) {
            throw ApiException.badRequest("SAME_VERSION", "目标版本必须与来源版本不同");
        }
        int sampleFloor = request.effectiveSampleFloor();
        int threshold = request.effectiveFailureThresholdPercent();
        ReleaseType type = request.effectiveReleaseType();
        List<String> devices = request.devices() == null ? List.of() : request.devices();
        List<DeviceEdge> edges = request.edges() == null ? List.of() : request.edges();
        if (type == ReleaseType.TOPOLOGY) {
            validateTopology(request.topologyVersion(), devices, edges);
        } else if (request.topologyVersion() != null || !devices.isEmpty() || !edges.isEmpty()) {
            throw ApiException.badRequest("GRAY_WITH_TOPOLOGY", "GRAY 发布单不能携带拓扑字段");
        }
        String fingerprint = idempotency.fingerprint("release.create", request.model(),
                request.fromVersion(), request.toVersion(), request.ratio(), sampleFloor, threshold,
                type.name(), request.topologyVersion(), devices, edges);
        return idempotency.execute(request.requestId(), "release.create", fingerprint, () -> {
            long id;
            try {
                id = releaseRepository.insert(request.model(), request.fromVersion(), request.toVersion(),
                        request.ratio(), sampleFloor, threshold, type, request.topologyVersion());
            } catch (DuplicateKeyException e) {
                throw ApiException.conflict("ACTIVE_RELEASE_EXISTS", "型号已存在未终结发布单: " + request.model());
            }
            if (type == ReleaseType.TOPOLOGY) {
                for (String deviceId : devices) {
                    topologyRepository.insertDevice(id, deviceId);
                }
                for (DeviceEdge edge : edges) {
                    topologyRepository.insertEdge(id, TopologyRepository.Edge.of(edge.deviceA(), edge.deviceB()));
                }
            }
            return ReleaseView.of(findOrder(id));
        }, ReleaseView.class);
    }

    /**
     * 拓扑校验：必须携带拓扑版本；绑定设备1~10个、不重复且已登记；边端点在集合内、无自环、无重复边。
     */
    private void validateTopology(Integer topologyVersion, List<String> devices, List<DeviceEdge> edges) {
        if (topologyVersion == null) {
            throw ApiException.badRequest("TOPOLOGY_VERSION_REQUIRED", "TOPOLOGY 发布单必须携带拓扑版本");
        }
        if (devices.isEmpty()) {
            throw ApiException.badRequest("TOPOLOGY_DEVICES_REQUIRED", "TOPOLOGY 发布单必须绑定至少1台设备");
        }
        Set<String> bound = new HashSet<>(devices);
        if (bound.size() != devices.size()) {
            throw ApiException.badRequest("TOPOLOGY_DEVICE_DUPLICATED", "绑定设备列表存在重复设备");
        }
        for (String deviceId : devices) {
            if (deviceRepository.findById(deviceId).isEmpty()) {
                throw ApiException.badRequest("TOPOLOGY_DEVICE_UNKNOWN", "绑定设备未登记: " + deviceId);
            }
        }
        Set<String> seenEdges = new HashSet<>();
        for (DeviceEdge edge : edges) {
            if (edge.deviceA().equals(edge.deviceB())) {
                throw ApiException.badRequest("TOPOLOGY_EDGE_SELF_LOOP", "拓扑不允许自环: " + edge.deviceA());
            }
            if (!bound.contains(edge.deviceA()) || !bound.contains(edge.deviceB())) {
                throw ApiException.badRequest("TOPOLOGY_EDGE_UNKNOWN_DEVICE",
                        "边端点不在绑定设备集合内: " + edge.deviceA() + "-" + edge.deviceB());
            }
            TopologyRepository.Edge normalized = TopologyRepository.Edge.of(edge.deviceA(), edge.deviceB());
            if (!seenEdges.add(normalized.deviceA() + " " + normalized.deviceB())) {
                throw ApiException.badRequest("TOPOLOGY_EDGE_DUPLICATED",
                        "拓扑存在重复边: " + normalized.deviceA() + "-" + normalized.deviceB());
            }
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
                if (order.releaseType() == ReleaseType.TOPOLOGY) {
                    // 取消待执行任务释放停机；失败设备保持 UNHEALTHY 不自动恢复
                    deviceRepository.releaseDownByRelease(releaseId);
                }
                taskRepository.cancelPendingByRelease(releaseId);
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

    public ReleaseOrder findOrder(long releaseId) {
        return releaseRepository.findById(releaseId)
                .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
    }
}
