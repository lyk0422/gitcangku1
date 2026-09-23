package com.example.starter.spectrum.service;

import com.example.starter.spectrum.dto.CreateNetworkRequest;
import com.example.starter.spectrum.dto.NetworkStateResponse;
import com.example.starter.spectrum.dto.PlanHistoryResponse;
import com.example.starter.spectrum.dto.PlanResultResponse;
import com.example.starter.spectrum.dto.SubmitPlanRequest;
import com.example.starter.spectrum.exception.SpectrumException;
import com.example.starter.spectrum.repository.SpectrumRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 频率协同业务实现。
 *
 * <p>写操作均在单事务内完成：方案提交先对提交后的完整网络做累计干扰裁决，
 * 全部台站满足预算后才原子替换频道并将版本加一；裁决失败只回滚，
 * 频道、版本、方案记录与请求幂等键都不发生变化。</p>
 */
@Service
public class SpectrumService {

    private static final String KIND_CREATE = "CREATE_NETWORK";

    private static final String KIND_PLAN = "SUBMIT_PLAN";

    private final SpectrumRepository repository;

    private final ObjectMapper objectMapper;

    private final Clock clock;

    public SpectrumService(SpectrumRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建网络：1~20 个唯一台站及 0~1000 预算，有向边 0~1000；
     * 自环、重复边、引用未知台站均为 400。台站初始静默，版本从 1 开始。
     */
    @Transactional
    public NetworkStateResponse createNetwork(String requestId, CreateNetworkRequest request) {
        requireRequestId(requestId);
        String digest = digest(Map.of(
                "networkId", nullToEmpty(request.getNetworkId()),
                "stations", normalizeStations(request),
                "edges", normalizeEdges(request)));
        SpectrumRepository.RequestRow existing = repository.findRequest(requestId);
        if (existing != null) {
            if (!digest.equals(existing.paramDigest())) {
                throw new SpectrumException.Conflict("IDEMPOTENCY_PARAM_CONFLICT",
                        "requestId was already used with different parameters");
            }
            return readJson(existing.responseBody(), NetworkStateResponse.class);
        }

        validateCreateRequest(request);
        if (repository.findNetwork(request.getNetworkId()) != null) {
            throw new SpectrumException.Conflict("NETWORK_ALREADY_EXISTS",
                    "networkId already exists: " + request.getNetworkId());
        }

        long now = clock.millis();
        long networkRef;
        try {
            networkRef = repository.insertNetwork(request.getNetworkId(), now);
            repository.insertStations(networkRef, request.getStations().stream()
                    .map(s -> new SpectrumRepository.StationInput(s.getStationId(), s.getBudget()))
                    .toList());
            repository.insertEdges(networkRef, request.getEdges().stream()
                    .map(e -> new SpectrumRepository.EdgeInput(e.getFrom(), e.getTo(), e.getAmount()))
                    .toList());
        } catch (DuplicateKeyException ex) {
            throw new SpectrumException.Conflict("NETWORK_ALREADY_EXISTS",
                    "networkId already exists: " + request.getNetworkId());
        }

        NetworkStateResponse state = loadState(request.getNetworkId(), 1, networkRef);
        try {
            repository.insertRequest(new SpectrumRepository.RequestInput(
                    requestId, KIND_CREATE, request.getNetworkId(), null,
                    digest, 201, writeJson(state)), now);
        } catch (DuplicateKeyException ex) {
            // 并发下同一 requestId 被其他事务抢先占键：本次回滚并报冲突。
            throw new SpectrumException.Conflict("IDEMPOTENCY_PARAM_CONFLICT",
                    "requestId is being committed by a concurrent request");
        }
        return state;
    }

    /** 查询网络当前状态，不存在 404。 */
    public NetworkStateResponse getNetwork(String networkId) {
        SpectrumRepository.NetworkRow network = requireNetwork(networkId);
        return loadState(network.networkId(), network.version(), network.id());
    }

    /**
     * 提交频率方案：
     * 同一期望版本并发时仅一份成功（行锁串行化 + 版本校验），另一份 409；
     * 超预算 422 且配置完全不变；同 requestId 同参重放返回首次结果，异参 409；
     * planKey 全局唯一，换请求键复用返回 409，失败不占键。
     */
    @Transactional
    public PlanResultResponse submitPlan(String requestId, String networkId, SubmitPlanRequest request) {
        requireRequestId(requestId);
        String digest = digest(Map.of(
                "networkId", nullToEmpty(networkId),
                "expectedVersion", request.getExpectedVersion() == null ? -1 : request.getExpectedVersion(),
                "planKey", nullToEmpty(request.getPlanKey()),
                "changes", normalizeChanges(request)));
        SpectrumRepository.RequestRow existing = repository.findRequest(requestId);
        if (existing != null) {
            if (!digest.equals(existing.paramDigest())) {
                throw new SpectrumException.Conflict("IDEMPOTENCY_PARAM_CONFLICT",
                        "requestId was already used with different parameters");
            }
            return readJson(existing.responseBody(), PlanResultResponse.class);
        }

        // SELECT ... FOR UPDATE：同一网络的并发方案在此串行，不会合并出超预算配置。
        SpectrumRepository.NetworkRow network = repository.lockNetwork(networkId);
        if (network == null) {
            throw new SpectrumException.NotFound("network not found: " + networkId);
        }

        if (repository.findPlanByKey(request.getPlanKey()) != null) {
            throw new SpectrumException.Conflict("PLAN_KEY_USED",
                    "planKey already used by a successful plan: " + request.getPlanKey());
        }

        if (network.version() != request.getExpectedVersion()) {
            throw new SpectrumException.Conflict("VERSION_CONFLICT",
                    "expected version " + request.getExpectedVersion()
                            + " but current version is " + network.version());
        }

        List<SpectrumRepository.StationRow> stationRows = repository.findStations(network.id());
        Map<String, Integer> budgets = new TreeMap<>();
        Map<String, Integer> channelsBefore = new TreeMap<>();
        for (SpectrumRepository.StationRow station : stationRows) {
            budgets.put(station.stationId(), station.budget());
            channelsBefore.put(station.stationId(), station.channel());
        }
        validateChanges(request, channelsBefore);

        // 未列出台站保持原状态；列出的台站整组替换为新值，因此同方案允许交换频道。
        Map<String, Integer> channelsAfter = new TreeMap<>(channelsBefore);
        for (SubmitPlanRequest.ChannelChange change : request.getChanges()) {
            channelsAfter.put(change.getStationId(), change.getChannel());
        }

        Map<InterferenceEvaluator.EdgeKey, Integer> edges = loadEdgeMap(network.id());

        // 先对提交后的完整网络裁决，再写库，杜绝“先逐台修改再校验”。
        List<PlanResultResponse.InterferenceItem> summary =
                InterferenceEvaluator.summarize(channelsAfter, budgets, edges);
        List<SpectrumException.BudgetExceeded.Violation> violations =
                InterferenceEvaluator.findViolations(summary);
        if (!violations.isEmpty()) {
            throw new SpectrumException.BudgetExceeded(violations);
        }

        int versionAfter = network.version() + 1;
        long now = clock.millis();
        List<SpectrumRepository.StationChannel> channelUpdates = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : channelsAfter.entrySet()) {
            channelUpdates.add(new SpectrumRepository.StationChannel(entry.getKey(), entry.getValue()));
        }
        try {
            repository.replaceChannelsAndBumpVersion(network.id(), channelUpdates, versionAfter, now);
            repository.insertPlan(new SpectrumRepository.PlanInput(
                    network.id(), request.getPlanKey(), requestId, versionAfter,
                    writeJson(channelsBefore), writeJson(channelsAfter), writeJson(summary)), now);
        } catch (DuplicateKeyException ex) {
            // 并发下同 planKey 的极端竞争：唯一约束兜底，整事务回滚。
            throw new SpectrumException.Conflict("PLAN_KEY_USED",
                    "planKey already used by a successful plan: " + request.getPlanKey());
        }

        PlanResultResponse result = new PlanResultResponse();
        result.setPlanKey(request.getPlanKey());
        result.setVersionBefore(network.version());
        result.setVersionAfter(versionAfter);
        result.setChannelsBefore(channelsBefore);
        result.setChannelsAfter(channelsAfter);
        result.setInterferenceSummary(summary);

        try {
            repository.insertRequest(new SpectrumRepository.RequestInput(
                    requestId, KIND_PLAN, networkId, request.getPlanKey(),
                    digest, 200, writeJson(result)), now);
        } catch (DuplicateKeyException ex) {
            // 并发下同一 requestId 被其他事务抢先占键：本次回滚并报冲突。
            throw new SpectrumException.Conflict("IDEMPOTENCY_PARAM_CONFLICT",
                    "requestId is being committed by a concurrent request");
        }
        return result;
    }

    /** 不可变历史查询，按版本升序；网络不存在 404。 */
    public PlanHistoryResponse getHistory(String networkId) {
        SpectrumRepository.NetworkRow network = requireNetwork(networkId);
        PlanHistoryResponse response = new PlanHistoryResponse();
        response.setNetworkId(networkId);
        List<PlanHistoryResponse.PlanRecord> records = new ArrayList<>();
        for (SpectrumRepository.PlanRow row : repository.findPlans(network.id())) {
            PlanHistoryResponse.PlanRecord record = new PlanHistoryResponse.PlanRecord();
            record.setPlanKey(row.planKey());
            record.setRequestId(row.requestId());
            record.setVersion(row.version());
            record.setChannelsBefore(readJson(row.channelsBefore(), new TypeReference<>() {
            }));
            record.setChannelsAfter(readJson(row.channelsAfter(), new TypeReference<>() {
            }));
            record.setInterferenceSummary(
                    readJson(row.summary(), new TypeReference<List<PlanResultResponse.InterferenceItem>>() {
                    }));
            records.add(record);
        }
        response.setPlans(records);
        return response;
    }

    // ------------------------------------------------------------------
    // 内部辅助方法（置于作用域末尾）
    // ------------------------------------------------------------------

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new SpectrumException.BadRequest("missing or blank X-Request-Id header");
        }
    }

    private void validateCreateRequest(CreateNetworkRequest request) {
        List<String> stationIds = request.getStations().stream()
                .map(CreateNetworkRequest.StationSpec::getStationId)
                .sorted()
                .toList();
        for (int i = 1; i < stationIds.size(); i++) {
            if (stationIds.get(i).equals(stationIds.get(i - 1))) {
                throw new SpectrumException.BadRequest("duplicated stationId: " + stationIds.get(i));
            }
        }
        java.util.Set<String> stationSet = new java.util.HashSet<>(stationIds);
        List<String> edgeKeys = new ArrayList<>();
        for (CreateNetworkRequest.EdgeSpec edge : request.getEdges()) {
            if (edge.getFrom().equals(edge.getTo())) {
                throw new SpectrumException.BadRequest("self loop edge is not allowed: " + edge.getFrom());
            }
            if (!stationSet.contains(edge.getFrom()) || !stationSet.contains(edge.getTo())) {
                throw new SpectrumException.BadRequest(
                        "edge references undefined station: " + edge.getFrom() + "->" + edge.getTo());
            }
            edgeKeys.add(edge.getFrom() + "\u0000" + edge.getTo());
        }
        edgeKeys.sort(String::compareTo);
        for (int i = 1; i < edgeKeys.size(); i++) {
            if (edgeKeys.get(i).equals(edgeKeys.get(i - 1))) {
                throw new SpectrumException.BadRequest("duplicated directed edge: " + edgeKeys.get(i));
            }
        }
    }

    private void validateChanges(SubmitPlanRequest request, Map<String, Integer> currentChannels) {
        List<String> changeStations = request.getChanges().stream()
                .map(SubmitPlanRequest.ChannelChange::getStationId)
                .sorted()
                .toList();
        for (int i = 1; i < changeStations.size(); i++) {
            if (changeStations.get(i).equals(changeStations.get(i - 1))) {
                throw new SpectrumException.BadRequest(
                        "duplicated stationId in changes: " + changeStations.get(i));
            }
        }
        for (String stationId : changeStations) {
            if (!currentChannels.containsKey(stationId)) {
                throw new SpectrumException.BadRequest("change references undefined station: " + stationId);
            }
        }
    }

    private Map<InterferenceEvaluator.EdgeKey, Integer> loadEdgeMap(long networkRef) {
        Map<InterferenceEvaluator.EdgeKey, Integer> edges = new LinkedHashMap<>();
        for (SpectrumRepository.EdgeRow edge : repository.findEdges(networkRef)) {
            edges.put(new InterferenceEvaluator.EdgeKey(edge.from(), edge.to()), edge.amount());
        }
        return edges;
    }

    private SpectrumRepository.NetworkRow requireNetwork(String networkId) {
        SpectrumRepository.NetworkRow network = repository.findNetwork(networkId);
        if (network == null) {
            throw new SpectrumException.NotFound("network not found: " + networkId);
        }
        return network;
    }

    private NetworkStateResponse loadState(String networkId, int version, long networkRef) {
        List<SpectrumRepository.StationRow> stationRows = repository.findStations(networkRef);
        List<SpectrumRepository.EdgeRow> edgeRows = repository.findEdges(networkRef);
        NetworkStateResponse state = new NetworkStateResponse();
        state.setNetworkId(networkId);
        state.setVersion(version);
        state.setStations(stationRows.stream()
                .map(s -> new NetworkStateResponse.StationState(s.stationId(), s.budget(), s.channel()))
                .toList());
        state.setEdges(edgeRows.stream()
                .map(e -> new NetworkStateResponse.EdgeView(e.from(), e.to(), e.amount()))
                .toList());
        return state;
    }

    private List<Map<String, Object>> normalizeStations(CreateNetworkRequest request) {
        return request.getStations().stream()
                .sorted(java.util.Comparator.comparing(CreateNetworkRequest.StationSpec::getStationId))
                .map(s -> Map.<String, Object>of(
                        "stationId", nullToEmpty(s.getStationId()),
                        "budget", s.getBudget() == null ? -1 : s.getBudget()))
                .toList();
    }

    private List<Map<String, Object>> normalizeEdges(CreateNetworkRequest request) {
        if (request.getEdges() == null) {
            return List.of();
        }
        return request.getEdges().stream()
                .sorted(java.util.Comparator
                        .comparing((CreateNetworkRequest.EdgeSpec e) -> nullToEmpty(e.getFrom()))
                        .thenComparing(e -> nullToEmpty(e.getTo())))
                .map(e -> Map.<String, Object>of(
                        "from", nullToEmpty(e.getFrom()),
                        "to", nullToEmpty(e.getTo()),
                        "amount", e.getAmount() == null ? -1 : e.getAmount()))
                .toList();
    }

    private List<Map<String, Object>> normalizeChanges(SubmitPlanRequest request) {
        if (request.getChanges() == null) {
            return List.of();
        }
        return request.getChanges().stream()
                .sorted(java.util.Comparator.comparing(
                        (SubmitPlanRequest.ChannelChange c) -> nullToEmpty(c.getStationId())))
                .map(c -> Map.<String, Object>of(
                        "stationId", nullToEmpty(c.getStationId()),
                        "channel", c.getChannel() == null ? -1 : c.getChannel()))
                .toList();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize snapshot", ex);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize snapshot", ex);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to deserialize snapshot", ex);
        }
    }

    private String digest(Object canonical) {
        try {
            ObjectMapper ordered = objectMapper.copy()
                    .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(
                    ordered.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize canonical parameters", ex);
        }
    }
}
