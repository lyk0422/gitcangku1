package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ClaimWaveRequest;
import com.example.starter.firmware.api.TopologyEdgeRequest;
import com.example.starter.firmware.api.TopologyRequest;
import com.example.starter.firmware.api.WaveMemberView;
import com.example.starter.firmware.api.WaveView;
import com.example.starter.firmware.domain.Device;
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
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 拓扑波次：发布创建时校验并冻结设备无向图；领取波次时在未领取且型号桶合规的设备中
 * 选择最大非相邻集合（同大小按设备编号序列字典序取小），单波次最多5台，
 * 整体创建任务并将成员标为停机。不健康设备及其相邻节点不可入选；无可选者返回422且不建任务。
 */
@Service
public class TopologyWaveService {

    /**
     * 单波次最大成员数。
     */
    static final int MAX_WAVE_SIZE = 5;

    private final ReleaseRepository releaseRepository;
    private final TopologyRepository topologyRepository;
    private final WaveRepository waveRepository;
    private final TaskRepository taskRepository;
    private final DeviceRepository deviceRepository;
    private final IdempotencyService idempotency;

    public TopologyWaveService(ReleaseRepository releaseRepository, TopologyRepository topologyRepository,
                               WaveRepository waveRepository, TaskRepository taskRepository,
                               DeviceRepository deviceRepository, IdempotencyService idempotency) {
        this.releaseRepository = releaseRepository;
        this.topologyRepository = topologyRepository;
        this.waveRepository = waveRepository;
        this.taskRepository = taskRepository;
        this.deviceRepository = deviceRepository;
        this.idempotency = idempotency;
    }

    /**
     * 创建拓扑发布单时校验并冻结图：最多10台设备、无自环、无重复边、端点必须在节点内、
     * 设备必须已登记且型号与发布单一致。校验失败抛业务异常，发布单插入随同事务回滚。
     */
    public void freezeTopology(long releaseId, String model, TopologyRequest topology) {
        List<String> deviceIds = topology.deviceIds();
        Set<String> nodeSet = new HashSet<>(deviceIds);
        if (nodeSet.size() != deviceIds.size()) {
            throw ApiException.badRequest("TOPOLOGY_DUPLICATE_DEVICE", "拓扑节点存在重复设备");
        }
        Map<String, Device> devices = deviceRepository.findByIds(deviceIds).stream()
                .collect(Collectors.toMap(Device::deviceId, Function.identity()));
        for (String deviceId : deviceIds) {
            Device device = devices.get(deviceId);
            if (device == null) {
                throw ApiException.badRequest("TOPOLOGY_DEVICE_UNKNOWN", "拓扑节点设备未登记: " + deviceId);
            }
            if (!device.model().equals(model)) {
                throw ApiException.badRequest("TOPOLOGY_MODEL_MISMATCH",
                        "拓扑节点设备型号与发布单不一致: " + deviceId);
            }
        }
        Set<String> seenEdges = new HashSet<>();
        List<String[]> normalized = new ArrayList<>();
        for (TopologyEdgeRequest edge : topology.edges()) {
            if (edge.a().equals(edge.b())) {
                throw ApiException.badRequest("TOPOLOGY_SELF_LOOP", "拓扑不允许自环: " + edge.a());
            }
            if (!nodeSet.contains(edge.a()) || !nodeSet.contains(edge.b())) {
                throw ApiException.badRequest("TOPOLOGY_EDGE_UNKNOWN_DEVICE",
                        "拓扑边端点不在节点集合内: " + edge.a() + "-" + edge.b());
            }
            String a = edge.a().compareTo(edge.b()) < 0 ? edge.a() : edge.b();
            String b = edge.a().compareTo(edge.b()) < 0 ? edge.b() : edge.a();
            if (!seenEdges.add(a + "|" + b)) {
                throw ApiException.badRequest("TOPOLOGY_DUPLICATE_EDGE", "拓扑存在重复边: " + a + "-" + b);
            }
            normalized.add(new String[]{a, b});
        }
        for (String deviceId : deviceIds) {
            topologyRepository.insertNode(releaseId, deviceId);
        }
        for (String[] edge : normalized) {
            topologyRepository.insertEdge(releaseId, edge[0], edge[1]);
        }
    }

    /**
     * 领取波次：持有发布单行锁串行化并发领取；成功即冻结成员集合与任务，
     * 同 requestId 重放返回首次快照，不按当前健康重选。
     */
    public WaveView claim(long releaseId, ClaimWaveRequest request) {
        String fingerprint = String.join("|", "wave.claim", request.operator(), String.valueOf(releaseId));
        return idempotency.execute(request.requestId(), "wave.claim", fingerprint, () -> {
            ReleaseOrder order = releaseRepository.findByIdForUpdate(releaseId)
                    .orElseThrow(() -> ApiException.notFound("RELEASE_NOT_FOUND", "发布单不存在: " + releaseId));
            if (order.releaseType() != ReleaseType.TOPOLOGY) {
                throw ApiException.badRequest("NOT_TOPOLOGY_RELEASE", "发布单不是拓扑波次类型: " + releaseId);
            }
            if (order.status() != ReleaseStatus.ACTIVE) {
                throw ApiException.conflict("RELEASE_NOT_ACTIVE",
                        "发布单状态为 " + order.status() + "，不能领取波次");
            }
            List<String> nodes = topologyRepository.findNodes(releaseId);
            Map<String, Set<String>> adjacency = buildAdjacency(nodes, topologyRepository.findEdges(releaseId));
            Map<String, Device> devices = deviceRepository.findByIds(nodes).stream()
                    .collect(Collectors.toMap(Device::deviceId, Function.identity()));
            Set<String> claimed = new HashSet<>(taskRepository.findDeviceIdsByRelease(releaseId));
            List<String> eligible = nodes.stream()
                    .filter(id -> isEligible(id, order, devices.get(id), claimed, adjacency, devices))
                    .sorted()
                    .toList();
            List<String> selected = selectMaxIndependent(eligible, adjacency);
            if (selected.isEmpty()) {
                throw ApiException.unprocessable("NO_WAVE_CANDIDATE", "当前无可领取的非相邻设备集合");
            }
            int waveSeq = waveRepository.nextWaveSeq(releaseId);
            long waveId = waveRepository.insert(releaseId, waveSeq, selected.size());
            List<WaveMemberView> members = new ArrayList<>();
            for (String deviceId : selected) {
                waveRepository.insertMember(waveId, deviceId);
                long taskId = taskRepository.insert(releaseId, deviceId);
                deviceRepository.updateDown(deviceId, true);
                members.add(new WaveMemberView(deviceId, taskId));
            }
            return new WaveView(waveId, releaseId, waveSeq, members);
        }, WaveView.class);
    }

    /**
     * 可选条件：未领取、型号桶合规（当前版本匹配来源版本且分桶号小于比例）、
     * 自身健康，且所有相邻节点健康（无停机/活跃安装阻断）。
     */
    private boolean isEligible(String deviceId, ReleaseOrder order, Device device, Set<String> claimed,
                               Map<String, Set<String>> adjacency, Map<String, Device> devices) {
        if (claimed.contains(deviceId) || device.down()) {
            return false;
        }
        if (!device.currentVersion().equals(order.fromVersion()) || device.bucketNo() >= order.ratio()) {
            return false;
        }
        for (String neighbor : adjacency.getOrDefault(deviceId, Set.of())) {
            if (devices.get(neighbor).down()) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Set<String>> buildAdjacency(List<String> nodes,
                                                    List<TopologyRepository.TopologyEdge> edges) {
        Map<String, Set<String>> adjacency = new HashMap<>();
        for (String node : nodes) {
            adjacency.put(node, new HashSet<>());
        }
        for (TopologyRepository.TopologyEdge edge : edges) {
            adjacency.get(edge.deviceA()).add(edge.deviceB());
            adjacency.get(edge.deviceB()).add(edge.deviceA());
        }
        return adjacency;
    }

    /**
     * 在可选设备中枚举独立集（图最多10节点），取最大集合；同大小按设备编号序列字典序取小；
     * 单波次最多 {@value #MAX_WAVE_SIZE} 台。
     */
    private List<String> selectMaxIndependent(List<String> eligible, Map<String, Set<String>> adjacency) {
        int n = eligible.size();
        List<String> best = List.of();
        for (int mask = 1; mask < (1 << n); mask++) {
            int size = Integer.bitCount(mask);
            if (size > MAX_WAVE_SIZE || size < best.size()) {
                continue;
            }
            List<String> subset = new ArrayList<>(size);
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    subset.add(eligible.get(i));
                }
            }
            if (!isIndependent(subset, adjacency)) {
                continue;
            }
            if (size > best.size() || compareLexicographic(subset, best) < 0) {
                best = subset;
            }
        }
        return best;
    }

    private boolean isIndependent(List<String> subset, Map<String, Set<String>> adjacency) {
        Set<String> members = new HashSet<>(subset);
        for (String deviceId : subset) {
            for (String neighbor : adjacency.getOrDefault(deviceId, Set.of())) {
                if (members.contains(neighbor)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int compareLexicographic(List<String> left, List<String> right) {
        for (int i = 0; i < Math.min(left.size(), right.size()); i++) {
            int cmp = left.get(i).compareTo(right.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    /**
     * 拓扑指纹：拓扑版本按值、设备编号与边文本原样，边按无向规范化后排序，结构化防碰撞。
     */
    public static String topologyFingerprint(TopologyRequest topology) {
        if (topology == null) {
            return "none";
        }
        Set<String> edges = new TreeSet<>();
        for (TopologyEdgeRequest edge : topology.edges()) {
            String a = edge.a().compareTo(edge.b()) < 0 ? edge.a() : edge.b();
            String b = edge.a().compareTo(edge.b()) < 0 ? edge.b() : edge.a();
            edges.add(a + "-" + b);
        }
        return topology.topologyVersion() + ":"
                + String.join(",", topology.deviceIds()) + ":"
                + String.join(",", edges);
    }
}
