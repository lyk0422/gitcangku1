package com.example.starter.firmware.service;

import com.example.starter.firmware.api.TaskView;
import com.example.starter.firmware.api.WaveClaimRequest;
import com.example.starter.firmware.api.WaveView;
import com.example.starter.firmware.domain.Device;
import com.example.starter.firmware.domain.HealthStatus;
import com.example.starter.firmware.domain.ReleaseOrder;
import com.example.starter.firmware.domain.ReleaseStatus;
import com.example.starter.firmware.domain.ReleaseType;
import com.example.starter.firmware.error.ApiException;
import com.example.starter.firmware.repo.DeviceRepository;
import com.example.starter.firmware.repo.ReleaseRepository;
import com.example.starter.firmware.repo.TaskRepository;
import com.example.starter.firmware.repo.TopologyRepository;
import com.example.starter.firmware.repo.WaveRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 拓扑波次领取：服务端在当前未领且型号桶合规的设备中选择最大非相邻集合（同大小按设备编号
 * 序列字典序，最多5项），整体创建任务并将成员标为停机。没有可选者返回 422 且不创建任何任务。
 * 选择集合与图在领取成功时冻结，重放不按当前健康重选。
 */
@Service
public class WaveService {

    /**
     * 单个波次最多成员数。
     */
    public static final int MAX_WAVE_MEMBERS = 5;

    private final ReleaseRepository releaseRepository;
    private final TopologyRepository topologyRepository;
    private final WaveRepository waveRepository;
    private final TaskRepository taskRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;

    public WaveService(ReleaseRepository releaseRepository, TopologyRepository topologyRepository,
                       WaveRepository waveRepository, TaskRepository taskRepository,
                       DeviceRepository deviceRepository, IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.topologyRepository = topologyRepository;
        this.waveRepository = waveRepository;
        this.taskRepository = taskRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
    }

    public WaveView claim(long releaseId, WaveClaimRequest request) {
        String fingerprint = idempotency.fingerprint("wave.claim", releaseId, request.operator(),
                request.expectedVersion());
        return idempotency.execute(request.requestId(), "wave.claim", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.releaseType() != ReleaseType.TOPOLOGY) {
                throw ApiException.badRequest("RELEASE_NOT_TOPOLOGY", "发布单不是拓扑波次类型: " + releaseId);
            }
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能领取波次");
            }
            if (order.version() != request.expectedVersion()) {
                throw ApiException.conflict("VERSION_CONFLICT",
                        "expectedVersion 与当前版本不一致: " + order.version());
            }

            List<String> boundIds = topologyRepository.findDeviceIds(releaseId);
            Map<String, Device> devices = deviceRepository.findByIdsForUpdate(boundIds).stream()
                    .collect(Collectors.toMap(Device::deviceId, Function.identity()));
            Map<String, Set<String>> adjacency = adjacencyOf(topologyRepository.findEdges(releaseId));
            Set<String> claimed = new HashSet<>(taskRepository.findDeviceIdsByRelease(releaseId));
            Set<String> activeInstalls = new HashSet<>(taskRepository.findPendingDeviceIdsByRelease(releaseId));

            List<String> candidates = new ArrayList<>();
            for (String deviceId : boundIds) {
                if (isSelectable(deviceId, order, devices, adjacency, claimed, activeInstalls)) {
                    candidates.add(deviceId);
                }
            }
            if (candidates.isEmpty()) {
                throw ApiException.unprocessable("NO_WAVE_CANDIDATES",
                        "当前没有可选入波次的设备: " + releaseId);
            }
            List<String> members = selectMaxIndependentSet(candidates, adjacency, MAX_WAVE_MEMBERS);

            int waveNo = waveRepository.countByRelease(releaseId) + 1;
            long waveId = waveRepository.insert(releaseId, waveNo, members.size());
            List<TaskView> tasks = new ArrayList<>();
            for (String deviceId : members) {
                waveRepository.insertMember(waveId, deviceId);
                long taskId = taskRepository.insert(releaseId, deviceId, waveId);
                deviceRepository.updateHealthStatus(deviceId, HealthStatus.DOWN);
                tasks.add(TaskView.of(taskRepository.findById(taskId).orElseThrow(), order));
            }
            return new WaveView(waveId, releaseId, waveNo, members, tasks);
        }, WaveView.class);
    }

    /**
     * 候选判定：当前未领、型号与当前版本匹配、分桶号小于比例、本机健康，
     * 且所有相邻节点健康并无活跃安装（不健康设备及其邻居、活跃安装邻居均被阻断）。
     */
    private boolean isSelectable(String deviceId, ReleaseOrder order, Map<String, Device> devices,
                                 Map<String, Set<String>> adjacency, Set<String> claimed,
                                 Set<String> activeInstalls) {
        if (claimed.contains(deviceId)) {
            return false;
        }
        Device device = devices.get(deviceId);
        if (device == null
                || !device.model().equals(order.model())
                || !device.currentVersion().equals(order.fromVersion())
                || device.bucketNo() >= order.ratio()
                || device.healthStatus() != HealthStatus.HEALTHY) {
            return false;
        }
        for (String neighbor : adjacency.getOrDefault(deviceId, Set.of())) {
            if (activeInstalls.contains(neighbor)) {
                return false;
            }
            Device neighborDevice = devices.get(neighbor);
            if (neighborDevice != null && neighborDevice.healthStatus() != HealthStatus.HEALTHY) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在候选集合上求最大非相邻集合：先取最大基数（ capped 到 maxSize ），同大小按设备编号
     * 序列字典序最小。候选按设备ID升序输入，子集枚举保持升序，直接字典序比较。
     */
    static List<String> selectMaxIndependentSet(List<String> candidates,
                                                Map<String, Set<String>> adjacency, int maxSize) {
        int n = candidates.size();
        List<String> best = null;
        for (int mask = 1; mask < (1 << n); mask++) {
            int size = Integer.bitCount(mask);
            if (size > maxSize || (best != null && size < best.size())) {
                continue;
            }
            List<String> subset = new ArrayList<>(size);
            boolean independent = true;
            for (int i = 0; i < n && independent; i++) {
                if ((mask & (1 << i)) == 0) {
                    continue;
                }
                String member = candidates.get(i);
                for (String chosen : subset) {
                    if (adjacency.getOrDefault(member, Set.of()).contains(chosen)) {
                        independent = false;
                        break;
                    }
                }
                if (independent) {
                    subset.add(member);
                }
            }
            if (!independent) {
                continue;
            }
            if (best == null || size > best.size() || compareLexicographically(subset, best) < 0) {
                best = subset;
            }
        }
        if (best == null) {
            throw new IllegalStateException("候选集合非空时必存在非空非相邻集合");
        }
        return best;
    }

    private static int compareLexicographically(List<String> left, List<String> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int cmp = left.get(i).compareTo(right.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static Map<String, Set<String>> adjacencyOf(List<TopologyRepository.Edge> edges) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (TopologyRepository.Edge edge : edges) {
            adjacency.computeIfAbsent(edge.deviceA(), k -> new HashSet<>()).add(edge.deviceB());
            adjacency.computeIfAbsent(edge.deviceB(), k -> new HashSet<>()).add(edge.deviceA());
        }
        return adjacency;
    }
}
