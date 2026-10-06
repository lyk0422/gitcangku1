package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RouteLeg;
import com.example.starter.plan.model.RouteSegment;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.repo.RouteGraphRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.RouteGraphReplaceRequest;
import com.example.starter.plan.web.dto.RouteGraphResponse;
import com.example.starter.plan.web.dto.RouteLegView;
import com.example.starter.plan.web.dto.RoutePlanRequest;
import com.example.starter.plan.web.dto.RoutePlanResponse;
import com.example.starter.plan.web.dto.RouteSegmentRequest;
import com.example.starter.plan.web.dto.RouteSegmentView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 线路图寻径与直接发布：图整体替换（版本从 1 起，成功加一）、按起终站寻径并一次发布。
 *
 * <p>一致性约定：寻径发布、图替换与普通发布/取消共用同一全局发布锁，
 * 选路后到提交不会被另一发布或换图抢占；失败整体回滚不留草稿。
 * 幂等身份含操作、操作者、资源与全部参数（含期望版本），以结构化 JSON 规范化后取 SHA-256，
 * 避免分隔符碰撞；仅成功结果写入幂等记录。
 */
@Service
public class RouteService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = PlanService.OPERATION_ZONE;

    private static final String OP_GRAPH_REPLACE = "GRAPH_REPLACE";
    private static final String OP_ROUTE_PUBLISH = "ROUTE_PUBLISH";

    private final RouteGraphRepository graphRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public RouteService(RouteGraphRepository graphRepo, PlanRepository planRepo,
                        IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                        PlatformTransactionManager txManager) {
        this.graphRepo = graphRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 整体替换线路图：发布锁内校验 expectedVersion 等于当前版本（无图为 0），
     * 成功写入新版本（版本加一），站点、区段随版本整体冻结。
     */
    public RouteGraphResponse replaceGraph(RouteGraphReplaceRequest req) {
        validateGraph(req);
        String hash = hashStructured(graphIdentity(req));
        Optional<RouteGraphResponse> replay =
                replayIfPresent(OP_GRAPH_REPLACE, req.requestKey(), hash, RouteGraphResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                int current = graphRepo.currentVersion();
                if (req.expectedVersion() != current) {
                    throw conflict("VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前图版本 " + current + " 不一致");
                }
                int newVersion = current + 1;
                List<RouteSegment> segments = req.segments().stream()
                        .map(s -> new RouteSegment(s.segmentId(), s.fromStation(), s.toStation(),
                                s.minutes()))
                        .toList();
                long now = System.currentTimeMillis();
                graphRepo.insertGraph(newVersion, req.stations(), segments, now);
                RouteGraphResponse response = toGraphResponse(newVersion, req.stations(), segments);
                idemRepo.insert(OP_GRAPH_REPLACE, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_GRAPH_REPLACE, req.requestKey(), hash, RouteGraphResponse.class)
                    .orElseThrow(() -> conflict("VERSION_CONFLICT", "线路图版本并发冲突"));
        }
    }

    /**
     * 查询当前线路图；尚无图返回 404。
     */
    public RouteGraphResponse getCurrentGraph() {
        int current = graphRepo.currentVersion();
        if (current == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GRAPH_NOT_FOUND", "线路图不存在");
        }
        return toGraphResponse(current, graphRepo.findStations(current),
                graphRepo.findSegments(current));
    }

    // ---------- 内部实现：图校验与视图 ----------

    private void validateGraph(RouteGraphReplaceRequest req) {
        Set<String> stations = new HashSet<>(req.stations());
        if (stations.size() != req.stations().size()) {
            throw badRequest("站点存在重复");
        }
        Set<String> segmentIds = new HashSet<>();
        for (RouteSegmentRequest s : req.segments()) {
            if (!segmentIds.add(s.segmentId())) {
                throw badRequest("区段编号重复: " + s.segmentId());
            }
            if (!stations.contains(s.fromStation())) {
                throw badRequest("区段 " + s.segmentId() + " 起点站不在图内: " + s.fromStation());
            }
            if (!stations.contains(s.toStation())) {
                throw badRequest("区段 " + s.segmentId() + " 终点站不在图内: " + s.toStation());
            }
        }
    }

    private RouteGraphResponse toGraphResponse(int version, List<String> stations,
                                               List<RouteSegment> segments) {
        List<RouteSegmentView> views = segments.stream()
                .map(s -> new RouteSegmentView(s.segmentId(), s.fromStation(), s.toStation(),
                        s.minutes()))
                .toList();
        return new RouteGraphResponse(version, List.copyOf(stations), views);
    }

    // ---------- 寻径发布 ----------

    /**
     * 寻径并一次发布：发布锁内核对图版本与站点，枚举全部不重复站点的简单路径，
     * 逐段累加行驶分钟（不中途等待），剔除超出最晚到达、跨 Asia/Shanghai 运营日
     * 或与已发布时隙冲突的路径；在可行路径中选总分钟最少者，同耗时按区段编号序列字典序。
     * 成功一次创建 PUBLISHED 日计划并冻结图版本、路径与各段时刻；无可行路径抛 422，
     * 不建计划、不占幂等键。
     */
    public RoutePlanResponse findAndPublish(RoutePlanRequest req) {
        validateRouteParams(req);
        String hash = hashStructured(routeIdentity(req));
        Optional<RoutePlanResponse> replay =
                replayIfPresent(OP_ROUTE_PUBLISH, req.requestKey(), hash, RoutePlanResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                int current = graphRepo.currentVersion();
                if (current == 0 || req.graphVersion() != current) {
                    throw conflict("GRAPH_VERSION_CONFLICT",
                            "graphVersion=" + req.graphVersion()
                                    + " 与当前图版本 " + current + " 不一致");
                }
                List<String> stations = graphRepo.findStations(current);
                if (!stations.contains(req.fromStation())) {
                    throw notFoundStation(req.fromStation());
                }
                if (!stations.contains(req.toStation())) {
                    throw notFoundStation(req.toStation());
                }
                List<RouteSegment> segments = graphRepo.findSegments(current);
                LocalDate opDate = req.departUtc().atZone(OPERATION_ZONE).toLocalDate();
                List<String> sectionIds = segments.stream()
                        .map(RouteSegment::segmentId)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                        .stream().toList();
                List<PublishedSlot> slots = planRepo.findPublishedSlots(opDate, sectionIds,
                        List.of());
                List<Map<String, Object>> rejections = new ArrayList<>();
                Candidate best = findBestPath(segments, req, opDate, slots, rejections);
                if (best == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_INFEASIBLE",
                            "无可行路径，未创建计划", rejections);
                }
                long now = System.currentTimeMillis();
                String scheduleKey = "RT-" + UUID.randomUUID();
                long planId = planRepo.insertPlan(scheduleKey, opDate, PlanStatus.PUBLISHED,
                        current, now);
                List<RouteLeg> legs = toLegs(planId, best.legs());
                planRepo.insertOccupancies(planId, toOccupancies(planId, req.trainNo(), legs));
                planRepo.insertRouteLegs(planId, legs);
                RoutePlanResponse response = toRoutePlanResponse(scheduleKey, current, opDate,
                        req, legs, best.totalMinutes());
                idemRepo.insert(OP_ROUTE_PUBLISH, req.requestKey(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_ROUTE_PUBLISH, req.requestKey(), hash, RoutePlanResponse.class)
                    .orElseThrow(() -> conflict("REQUEST_CONFLICT", "幂等键并发冲突"));
        }
    }

    // ---------- 内部实现：寻径 ----------

    /** 一条完整路径的候选：途经区段与逐段时刻。 */
    private record LegPlan(RouteSegment segment, Instant startUtc, Instant endUtc) {
    }

    private record Candidate(List<LegPlan> legs, int totalMinutes) {
    }

    private void validateRouteParams(RoutePlanRequest req) {
        if (req.departUtc().getEpochSecond() % 60 != 0) {
            throw badRequest("departUtc 必须为 UTC 整分钟");
        }
        if (!req.arriveByUtc().isAfter(req.departUtc())) {
            throw badRequest("arriveByUtc 必须晚于 departUtc");
        }
        if (req.fromStation().equals(req.toStation())) {
            throw badRequest("起终站必须不同: " + req.fromStation());
        }
    }

    /**
     * DFS 枚举全部简单路径（不重复站点），边按区段编号升序展开保证枚举确定；
     * 以当前最优总分钟与最晚到达剪枝，完整路径再校验运营日与时隙冲突。
     */
    private Candidate findBestPath(List<RouteSegment> segments, RoutePlanRequest req,
                                   LocalDate opDate, List<PublishedSlot> slots,
                                   List<Map<String, Object>> rejections) {
        Map<String, List<RouteSegment>> adjacency = new LinkedHashMap<>();
        for (RouteSegment s : segments) {
            adjacency.computeIfAbsent(s.fromStation(), k -> new ArrayList<>()).add(s);
        }
        Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
        Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
        Candidate[] best = new Candidate[1];
        Deque<RouteSegment> path = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        visited.add(req.fromStation());
        dfs(req.fromStation(), req.toStation(), req.departUtc(), req.departUtc(), req.arriveByUtc(),
                dayStart, dayEnd, adjacency, slots, visited, path, best, rejections);
        return best[0];
    }

    private void dfs(String station, String target, Instant departUtc, Instant cursor,
                     Instant arriveBy, Instant dayStart, Instant dayEnd,
                     Map<String, List<RouteSegment>> adjacency, List<PublishedSlot> slots,
                     Set<String> visited, Deque<RouteSegment> path, Candidate[] best,
                     List<Map<String, Object>> rejections) {
        for (RouteSegment segment : adjacency.getOrDefault(station, List.of())) {
            if (visited.contains(segment.toStation())) {
                continue;
            }
            Instant end = cursor.plusSeconds(segment.minutes() * 60L);
            if (end.isAfter(arriveBy)) {
                continue;
            }
            int total = totalMinutes(path) + segment.minutes();
            if (best[0] != null && total > best[0].totalMinutes()) {
                continue;
            }
            path.addLast(segment);
            if (segment.toStation().equals(target)) {
                Candidate candidate = evaluate(path, total, departUtc, dayStart, dayEnd, slots,
                        rejections);
                if (candidate != null && (best[0] == null
                        || compare(candidate, best[0]) < 0)) {
                    best[0] = candidate;
                }
            } else {
                visited.add(segment.toStation());
                dfs(segment.toStation(), target, departUtc, end, arriveBy, dayStart, dayEnd,
                        adjacency, slots, visited, path, best, rejections);
                visited.remove(segment.toStation());
            }
            path.removeLast();
        }
    }

    /**
     * 校验完整路径：自出发时刻逐段累加行驶分钟，每段占用落在运营日内且不与已发布时隙重叠；
     * 不可行时记录拒绝原因并返回 null。
     */
    private Candidate evaluate(Deque<RouteSegment> path, int total, Instant departUtc,
                               Instant dayStart, Instant dayEnd, List<PublishedSlot> slots,
                               List<Map<String, Object>> rejections) {
        List<LegPlan> legs = new ArrayList<>(path.size());
        Instant cursor = departUtc;
        for (RouteSegment segment : path) {
            Instant end = cursor.plusSeconds(segment.minutes() * 60L);
            legs.add(new LegPlan(segment, cursor, end));
            cursor = end;
        }
        List<String> segmentIds = path.stream().map(RouteSegment::segmentId).toList();
        for (LegPlan leg : legs) {
            if (leg.startUtc().isBefore(dayStart) || leg.endUtc().isAfter(dayEnd)) {
                rejections.add(rejection("ROUTE_CROSS_DAY", segmentIds,
                        "路径跨 Asia/Shanghai 运营日"));
                return null;
            }
            for (PublishedSlot slot : slots) {
                boolean overlap = leg.segment().segmentId().equals(slot.sectionId())
                        && leg.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(leg.endUtc());
                if (overlap) {
                    Map<String, Object> detail = rejection("ROUTE_OCCUPIED", segmentIds,
                            "区段 " + leg.segment().segmentId() + " 已被计划 "
                                    + slot.scheduleKey() + " 占用");
                    detail.put("conflictingScheduleKey", slot.scheduleKey());
                    detail.put("occupiedSegmentId", leg.segment().segmentId());
                    rejections.add(detail);
                    return null;
                }
            }
        }
        return new Candidate(legs, total);
    }

    private Map<String, Object> rejection(String type, List<String> segmentIds, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("segmentIds", segmentIds);
        detail.put("message", message);
        return detail;
    }

    private int totalMinutes(Deque<RouteSegment> path) {
        int total = 0;
        for (RouteSegment s : path) {
            total += s.minutes();
        }
        return total;
    }

    /** 候选比较：总分钟少者优先；同耗时按区段编号序列字典序。 */
    private int compare(Candidate a, Candidate b) {
        if (a.totalMinutes() != b.totalMinutes()) {
            return Integer.compare(a.totalMinutes(), b.totalMinutes());
        }
        int size = Math.min(a.legs().size(), b.legs().size());
        for (int i = 0; i < size; i++) {
            int c = a.legs().get(i).segment().segmentId()
                    .compareTo(b.legs().get(i).segment().segmentId());
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.legs().size(), b.legs().size());
    }

    private List<RouteLeg> toLegs(long planId, List<LegPlan> legPlans) {
        List<RouteLeg> legs = new ArrayList<>(legPlans.size());
        for (int i = 0; i < legPlans.size(); i++) {
            LegPlan lp = legPlans.get(i);
            legs.add(new RouteLeg(0L, planId, i, lp.segment().segmentId(),
                    lp.segment().fromStation(), lp.segment().toStation(),
                    lp.startUtc(), lp.endUtc()));
        }
        return legs;
    }

    private List<Occupancy> toOccupancies(long planId, String trainNo, List<RouteLeg> legs) {
        List<Occupancy> result = new ArrayList<>(legs.size());
        for (RouteLeg leg : legs) {
            result.add(new Occupancy(0L, planId, leg.seq(), trainNo, leg.segmentId(),
                    leg.startUtc(), leg.endUtc()));
        }
        return result;
    }

    private RoutePlanResponse toRoutePlanResponse(String scheduleKey, int graphVersion,
                                                  LocalDate opDate, RoutePlanRequest req,
                                                  List<RouteLeg> legs, int totalMinutes) {
        List<RouteLegView> views = legs.stream()
                .map(l -> new RouteLegView(l.seq(), l.segmentId(), l.fromStation(), l.toStation(),
                        l.startUtc(), l.endUtc()))
                .toList();
        return new RoutePlanResponse(scheduleKey, graphVersion, opDate, PlanStatus.PUBLISHED.name(),
                req.trainNo(), req.fromStation(), req.toStation(), req.departUtc(),
                legs.get(legs.size() - 1).endUtc(), totalMinutes, views);
    }

    // ---------- 内部实现：幂等与哈希 ----------

    private Map<String, Object> graphIdentity(RouteGraphReplaceRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_GRAPH_REPLACE);
        identity.put("operator", req.operator());
        identity.put("resource", "route-graph");
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("stations", req.stations());
        List<Map<String, Object>> segments = new ArrayList<>();
        for (RouteSegmentRequest s : req.segments()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("segmentId", s.segmentId());
            item.put("fromStation", s.fromStation());
            item.put("toStation", s.toStation());
            item.put("minutes", s.minutes());
            segments.add(item);
        }
        identity.put("segments", segments);
        return identity;
    }

    private Map<String, Object> routeIdentity(RoutePlanRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_ROUTE_PUBLISH);
        identity.put("operator", req.operator());
        identity.put("resource", "route-plan");
        identity.put("graphVersion", req.graphVersion());
        identity.put("trainNo", req.trainNo());
        identity.put("fromStation", req.fromStation());
        identity.put("toStation", req.toStation());
        identity.put("departUtc", req.departUtc().toEpochMilli());
        identity.put("arriveByUtc", req.arriveByUtc().toEpochMilli());
        return identity;
    }

    /**
     * 结构化身份规范化（JSON 序列化，键值经 JSON 转义，无分隔符碰撞）后取 SHA-256。
     */
    private String hashStructured(Map<String, Object> identity) {
        try {
            String canonical = objectMapper.writeValueAsString(identity);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("幂等身份序列化失败", e);
        }
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestKey, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestKey).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestKey 已用于其他参数: " + requestKey);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String toJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException notFoundStation(String station) {
        return new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND",
                "站点不存在: " + station);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
