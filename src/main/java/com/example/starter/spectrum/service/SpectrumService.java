package com.example.starter.spectrum.service;

import com.example.starter.spectrum.ApiException;
import com.example.starter.spectrum.domain.PlanRecord;
import com.example.starter.spectrum.domain.SpectrumNetwork;
import com.example.starter.spectrum.dto.CreateNetworkRequest;
import com.example.starter.spectrum.dto.NetworkStateView;
import com.example.starter.spectrum.dto.PlanResultView;
import com.example.starter.spectrum.dto.PlanSubmitRequest;
import com.example.starter.spectrum.repo.SpectrumRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 频率协同业务服务。
 *
 * <p>关键语义：
 * <ul>
 *   <li>网络配置创建后不可修改，初始全部静默，版本从1开始；</li>
 *   <li>方案提交先对提交后的完整网络做全量同频干扰累计裁决，全部不超预算才原子写回；</li>
 *   <li>并发提交以网络版本乐观锁裁决：同一期望版本最多一份成功，其余409；</li>
 *   <li>requestId 同键同参返回首次结果、异参409、失败不占键；换 requestId 复用 planKey 一律409。</li>
 * </ul>
 */
@Service
public class SpectrumService {

    private static final String OP_CREATE_NETWORK = "CREATE_NETWORK";
    private static final String OP_SUBMIT_PLAN = "SUBMIT_PLAN";

    private final SpectrumRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SpectrumService(SpectrumRepository repository,
                           ObjectMapper objectMapper,
                           TransactionTemplate transactionTemplate) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 带 HTTP 状态的服务返回体：幂等重放创建请求时需还原首次的 201。
     */
    public record OperationResult<T>(HttpStatus status, T body) {
    }

    // ============================ 建网 ============================

    public OperationResult<NetworkStateView> createNetwork(CreateNetworkRequest request, String requestId) {
        requireRequestId(requestId);

        List<CreateNetworkRequest.StationInput> stations =
                request.stations() == null ? List.of() : request.stations();
        List<CreateNetworkRequest.EdgeInput> edges =
                request.edges() == null ? List.of() : request.edges();
        validateNetworkDefinition(stations, edges);

        String paramsHash = sha256(canonicalCreate(request));

        Optional<SpectrumRepository.IdempotentRecord> replay = repository.findRequest(requestId);
        if (replay.isPresent()) {
            return replayCreate(replay.get(), paramsHash);
        }

        if (repository.networkExists(request.networkId())) {
            throw ApiException.conflict("NETWORK_EXISTS", "网络已存在: " + request.networkId());
        }

        List<SpectrumNetwork.Station> domainStations = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            CreateNetworkRequest.StationInput input = stations.get(i);
            // 初始全部静默（频道0）
            domainStations.add(new SpectrumNetwork.Station(
                    input.stationId(), i, input.interferenceBudget(), 0));
        }
        List<SpectrumNetwork.Edge> domainEdges = edges.stream()
                .map(e -> new SpectrumNetwork.Edge(e.fromStationId(), e.toStationId(), e.interference()))
                .toList();
        SpectrumNetwork network = new SpectrumNetwork(
                request.networkId(), request.name(), 1, domainStations, domainEdges);
        NetworkStateView view = toView(network);

        try {
            // 幂等记录与网络配置同一事务：建网失败（含并发冲突回滚）不占 requestId。
            transactionTemplate.executeWithoutResult(status -> {
                repository.insertNetwork(network);
                repository.insertRequest(new SpectrumRepository.IdempotentRecord(
                        requestId, OP_CREATE_NETWORK, request.networkId(), paramsHash,
                        HttpStatus.CREATED.value(), writeJson(view)));
            });
        } catch (DuplicateKeyException ex) {
            Optional<SpectrumRepository.IdempotentRecord> concurrent = repository.findRequest(requestId);
            if (concurrent.isPresent()) {
                return replayCreate(concurrent.get(), paramsHash);
            }
            throw ApiException.conflict("NETWORK_EXISTS", "网络已存在: " + request.networkId());
        }
        return new OperationResult<>(HttpStatus.CREATED, view);
    }

    private OperationResult<NetworkStateView> replayCreate(
            SpectrumRepository.IdempotentRecord record, String paramsHash) {
        if (!OP_CREATE_NETWORK.equals(record.operation()) || !record.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("REQUEST_CONFLICT",
                    "requestId 已用于参数不同的写操作，请求冲突");
        }
        return new OperationResult<>(
                HttpStatus.valueOf(record.resultStatus()),
                deserialize(record.resultBody(), NetworkStateView.class));
    }

    private void validateNetworkDefinition(List<CreateNetworkRequest.StationInput> stations,
                                           List<CreateNetworkRequest.EdgeInput> edges) {
        if (stations.isEmpty() || stations.size() > 20) {
            throw ApiException.badRequest("INVALID_PARAMETER", "stations 数量范围为1~20");
        }
        Set<String> stationIds = new LinkedHashSet<>();
        for (CreateNetworkRequest.StationInput station : stations) {
            if (!stationIds.add(station.stationId())) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "stationId 重复: " + station.stationId());
            }
        }
        Set<EdgeKey> edgeKeys = new LinkedHashSet<>();
        for (CreateNetworkRequest.EdgeInput edge : edges) {
            if (!stationIds.contains(edge.fromStationId()) || !stationIds.contains(edge.toStationId())) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "边引用了未定义的台站: " + edge.fromStationId() + "->" + edge.toStationId());
            }
            if (edge.fromStationId().equals(edge.toStationId())) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "禁止自环边: " + edge.fromStationId());
            }
            if (!edgeKeys.add(new EdgeKey(edge.fromStationId(), edge.toStationId()))) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "重复边: " + edge.fromStationId() + "->" + edge.toStationId());
            }
        }
    }

    /**
     * 有向边去重键（比字符串拼接更安全，不存在撞键问题）。
     */
    private record EdgeKey(String from, String to) {
    }

    // ============================ 状态查询 ============================

    public NetworkStateView getNetwork(String networkId) {
        return toView(loadNetwork(networkId));
    }

    // ============================ 方案提交 ============================

    public OperationResult<PlanResultView> submitPlan(String networkId,
                                                      PlanSubmitRequest request,
                                                      String requestId) {
        requireRequestId(requestId);
        validateAssignmentShape(request);

        String paramsHash = sha256(canonicalPlan(networkId, request));

        // requestId 幂等优先：同键同参返回首次结果，异参409。
        Optional<SpectrumRepository.IdempotentRecord> replay = repository.findRequest(requestId);
        if (replay.isPresent()) {
            return new OperationResult<>(HttpStatus.OK, replayPlan(replay.get(), paramsHash));
        }

        SpectrumNetwork network = loadNetwork(networkId);
        validateAssignmentReferences(network, request);

        // 换请求键复用 planKey：无论参数是否相同一律 409；失败提交从不写 plan，故命中必为已成功方案。
        if (repository.findPlanByKey(request.planKey()).isPresent()) {
            throw ApiException.conflict("PLAN_KEY_CONFLICT",
                    "planKey 已被其他请求使用: " + request.planKey());
        }

        // 期望版本不匹配（含被并发方案推进）：409，不做任何修改。
        if (network.version() != request.expectedVersion()) {
            throw ApiException.conflict("VERSION_CONFLICT",
                    "expectedVersion=" + request.expectedVersion()
                            + " 与当前版本 " + network.version() + " 不一致");
        }

        // 对提交后的完整网络计算候选频道并全量裁决（先校验后写，不做逐台半成品修改）。
        Map<String, Integer> candidate = InterferenceEvaluator.currentChannels(network);
        for (PlanSubmitRequest.AssignmentInput assignment : request.assignments()) {
            candidate.put(assignment.stationId(), assignment.channel());
        }
        List<InterferenceEvaluator.StationResult> evaluation =
                InterferenceEvaluator.evaluate(network, candidate);
        List<InterferenceEvaluator.StationResult> violations =
                InterferenceEvaluator.violationsSortedById(evaluation);
        if (!violations.isEmpty()) {
            // 超预算：频道、版本、方案记录完全不变；requestId 与 planKey 均不占用。
            List<Map<String, Object>> details = violations.stream()
                    .map(v -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("stationId", v.stationId());
                        item.put("actualInterference", v.total());
                        item.put("interferenceBudget", v.budget());
                        return item;
                    })
                    .toList();
            throw ApiException.unprocessable("INTERFERENCE_BUDGET_EXCEEDED",
                    "存在累计同频干扰超预算的台站", details);
        }

        NetworkStateView beforeView = toView(network);
        int versionFrom = network.version();
        int versionTo = versionFrom + 1;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        NetworkStateView afterView = new NetworkStateView(
                network.networkId(), network.name(), versionTo,
                network.stations().stream()
                        .map(s -> new NetworkStateView.StationView(
                                s.stationId(), s.position(), s.interferenceBudget(),
                                candidate.getOrDefault(s.stationId(), s.channel())))
                        .toList(),
                beforeView.edges());
        List<PlanResultView.StationInterference> summary = toSummary(network, evaluation);

        String normalizedRequest = canonicalPlan(networkId, request);
        PlanRecord record = new PlanRecord(
                null, networkId, request.planKey(), requestId,
                versionFrom, versionTo,
                normalizedRequest,
                writeJson(beforeView), writeJson(afterView), writeJson(summary),
                now);
        String resultJson = writeJson(new PlanResultView(
                request.planKey(), requestId, versionFrom, versionTo,
                beforeView, afterView, summary, now));

        try {
            transactionTemplate.executeWithoutResult(status -> {
                // 幂等记录同一事务写入：提交回滚时 requestId 不被占用。
                repository.insertRequest(new SpectrumRepository.IdempotentRecord(
                        requestId, OP_SUBMIT_PLAN, networkId, paramsHash,
                        HttpStatus.OK.value(), resultJson));
                // 乐观锁：同一 expectedVersion 并发提交最多一份成功。
                int updated = repository.compareAndSetVersion(networkId, versionFrom, versionTo);
                if (updated == 0) {
                    throw ApiException.conflict("VERSION_CONFLICT",
                            "网络版本已变化，expectedVersion=" + versionFrom + " 已过期");
                }
                for (PlanSubmitRequest.AssignmentInput assignment : request.assignments()) {
                    repository.updateChannel(networkId, assignment.stationId(), assignment.channel());
                }
                repository.insertPlan(record);
            });
        } catch (DuplicateKeyException ex) {
            // 并发冲突分类：requestId 命中（同参回放/异参冲突）或 planKey 被并发占用（409）。
            Optional<SpectrumRepository.IdempotentRecord> concurrent = repository.findRequest(requestId);
            if (concurrent.isPresent()) {
                return new OperationResult<>(HttpStatus.OK, replayPlan(concurrent.get(), paramsHash));
            }
            if (repository.findPlanByKey(request.planKey()).isPresent()) {
                throw ApiException.conflict("PLAN_KEY_CONFLICT",
                        "planKey 已被其他请求使用: " + request.planKey());
            }
            throw ex;
        }
        return new OperationResult<>(HttpStatus.OK, deserialize(resultJson, PlanResultView.class));
    }

    private PlanResultView replayPlan(SpectrumRepository.IdempotentRecord record, String paramsHash) {
        if (!OP_SUBMIT_PLAN.equals(record.operation()) || !record.paramsHash().equals(paramsHash)) {
            throw ApiException.conflict("REQUEST_CONFLICT",
                    "requestId 已用于参数不同的写操作，请求冲突");
        }
        return deserialize(record.resultBody(), PlanResultView.class);
    }

    private void validateAssignmentShape(PlanSubmitRequest request) {
        if (request.assignments() == null || request.assignments().isEmpty()) {
            throw ApiException.badRequest("INVALID_PARAMETER", "assignments 至少包含1个台站");
        }
        if (request.assignments().size() > 20) {
            throw ApiException.badRequest("INVALID_PARAMETER", "assignments 数量范围为1~20");
        }
        Set<String> assigned = new LinkedHashSet<>();
        for (PlanSubmitRequest.AssignmentInput assignment : request.assignments()) {
            if (!assigned.add(assignment.stationId())) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "assignments 中台站重复: " + assignment.stationId());
            }
            if (assignment.channel() == null || assignment.channel() < 0 || assignment.channel() > 8) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "channel 范围为0~8，0表示静默");
            }
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 1) {
            throw ApiException.badRequest("INVALID_PARAMETER", "expectedVersion 必须为正整数");
        }
        if (request.planKey() == null || request.planKey().isBlank() || request.planKey().length() > 64) {
            throw ApiException.badRequest("INVALID_PARAMETER", "planKey 不能为空且长度不超过64");
        }
    }

    private void validateAssignmentReferences(SpectrumNetwork network, PlanSubmitRequest request) {
        Set<String> known = new LinkedHashSet<>();
        network.stations().forEach(s -> known.add(s.stationId()));
        for (PlanSubmitRequest.AssignmentInput assignment : request.assignments()) {
            if (!known.contains(assignment.stationId())) {
                throw ApiException.badRequest("INVALID_PARAMETER",
                        "assignments 引用了网络中不存在的台站: " + assignment.stationId());
            }
        }
    }

    // ============================ 历史查询 ============================

    public List<PlanResultView> listPlans(String networkId) {
        loadNetwork(networkId);
        return repository.findPlans(networkId).stream()
                .map(this::toPlanResultView)
                .toList();
    }

    private PlanResultView toPlanResultView(PlanRecord record) {
        return new PlanResultView(
                record.planKey(),
                record.requestId(),
                record.versionFrom(),
                record.versionTo(),
                deserialize(record.beforeConfig(), NetworkStateView.class),
                deserialize(record.afterConfig(), NetworkStateView.class),
                deserializeList(record.interferenceSummary()),
                record.createdAt());
    }

    private List<PlanResultView.StationInterference> deserializeList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory()
                            .constructCollectionType(List.class, PlanResultView.StationInterference.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("历史干扰汇总反序列化失败", e);
        }
    }

    // ============================ 辅助 ============================

    private SpectrumNetwork loadNetwork(String networkId) {
        return repository.findNetwork(networkId)
                .orElseThrow(() -> ApiException.notFound("网络不存在: " + networkId));
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("INVALID_PARAMETER", "缺少必需请求头 X-Request-Id");
        }
        if (requestId.length() > 64) {
            throw ApiException.badRequest("INVALID_PARAMETER", "X-Request-Id 长度不能超过64");
        }
    }

    private List<PlanResultView.StationInterference> toSummary(
            SpectrumNetwork network, List<InterferenceEvaluator.StationResult> evaluation) {
        Map<String, InterferenceEvaluator.StationResult> byId = new LinkedHashMap<>();
        evaluation.forEach(r -> byId.put(r.stationId(), r));
        return network.stations().stream()
                .map(s -> {
                    InterferenceEvaluator.StationResult r = byId.get(s.stationId());
                    return new PlanResultView.StationInterference(
                            r.stationId(), r.channel(), r.total(), r.budget(), r.withinBudget());
                })
                .toList();
    }

    private NetworkStateView toView(SpectrumNetwork network) {
        return new NetworkStateView(
                network.networkId(),
                network.name(),
                network.version(),
                network.stations().stream()
                        .map(s -> new NetworkStateView.StationView(
                                s.stationId(), s.position(), s.interferenceBudget(), s.channel()))
                        .toList(),
                network.edges().stream()
                        .map(e -> new NetworkStateView.EdgeView(
                                e.fromStationId(), e.toStationId(), e.interference()))
                        .toList());
    }

    /**
     * 规范化建网参数：台站按 ID 排序、边按有向对排序，集合换序视为同参。
     */
    private String canonicalCreate(CreateNetworkRequest request) {
        Map<String, Object> root = new TreeMap<>();
        root.put("networkId", request.networkId());
        root.put("name", request.name());
        root.put("stations", request.stations().stream()
                .sorted(Comparator.comparing(CreateNetworkRequest.StationInput::stationId))
                .map(s -> {
                    Map<String, Object> m = new TreeMap<>();
                    m.put("stationId", s.stationId());
                    m.put("interferenceBudget", s.interferenceBudget());
                    return m;
                })
                .toList());
        List<CreateNetworkRequest.EdgeInput> edges =
                request.edges() == null ? List.of() : request.edges();
        root.put("edges", edges.stream()
                .sorted(Comparator.comparing(CreateNetworkRequest.EdgeInput::fromStationId)
                        .thenComparing(CreateNetworkRequest.EdgeInput::toStationId))
                .map(e -> {
                    Map<String, Object> m = new TreeMap<>();
                    m.put("fromStationId", e.fromStationId());
                    m.put("toStationId", e.toStationId());
                    m.put("interference", e.interference());
                    return m;
                })
                .toList());
        return writeJson(root);
    }

    /**
     * 规范化方案参数：指派集合按台站 ID 排序，集合换序视为同参。
     */
    private String canonicalPlan(String networkId, PlanSubmitRequest request) {
        Map<String, Object> root = new TreeMap<>();
        root.put("networkId", networkId);
        root.put("expectedVersion", request.expectedVersion());
        root.put("planKey", request.planKey());
        root.put("assignments", request.assignments().stream()
                .sorted(Comparator.comparing(PlanSubmitRequest.AssignmentInput::stationId))
                .map(a -> {
                    Map<String, Object> m = new TreeMap<>();
                    m.put("stationId", a.stationId());
                    m.put("channel", a.channel());
                    return m;
                })
                .toList());
        return writeJson(root);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 反序列化失败", e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
