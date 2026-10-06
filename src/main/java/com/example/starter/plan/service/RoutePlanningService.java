package com.example.starter.plan.service;

import com.example.starter.plan.model.LineSegment;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RouteLeg;
import com.example.starter.plan.repo.GraphRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.GraphResponse;
import com.example.starter.plan.web.dto.LineSegmentRequest;
import com.example.starter.plan.web.dto.ReplaceGraphRequest;
import com.example.starter.plan.web.dto.RouteLegView;
import com.example.starter.plan.web.dto.RoutePublishRequest;
import com.example.starter.plan.web.dto.RoutePublishResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 线路图寻径发布核心：图整体替换（版本从 1 起，expectedVersion 乐观校验）、
 * 按图寻径并一次发布整张 PUBLISHED 日计划。
 *
 * <p>一致性约定：图替换与寻径发布均先取全局发布锁再读图版本，与普通发布/取消/改签
 * 共享同一一致视图；选路到提交在锁内完成，不会被另一发布抢占；失败整体回滚不留草稿。
 * 幂等身份由操作、操作者、资源与全部参数（含期望版本）结构化序列化后取 SHA-256，
 * 同键同参重放首次响应，异参 409，失败不占键。
 */
@Service
public class RoutePlanningService {

    /** 运营日解释时区。 */
    public static final ZoneId OPERATION_ZONE = PlanService.OPERATION_ZONE;

    private static final String OP_GRAPH_REPLACE = "GRAPH_REPLACE";
    private static final String OP_ROUTE_PUBLISH = "ROUTE_PUBLISH";
    private static final int MAX_STATIONS = 10;

    private final GraphRepository graphRepo;
    private final PlanRepository planRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public RoutePlanningService(GraphRepository graphRepo, PlanRepository planRepo,
                                IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                                PlatformTransactionManager txManager) {
        this.graphRepo = graphRepo;
        this.planRepo = planRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 查询当前线路图（版本与区段清单）；尚未发布任何图时版本为 0、区段为空。
     */
    public GraphResponse getGraph() {
        int version = graphRepo.currentVersion();
        List<LineSegmentRequest> segments = graphRepo.findSegments(version).stream()
                .map(RoutePlanningService::toSegmentRequest)
                .toList();
        return new GraphResponse(version, segments);
    }

    /**
     * 整体替换线路图：发布锁内校验 expectedVersion 等于当前版本，
     * 成功一次版本加一并整体写入新区段；过期版本返回 409。
     */
    public GraphResponse replaceGraph(ReplaceGraphRequest req) {
        validateSegments(req.segments());
        String hash = hashGraphReplace(req);
        Optional<GraphResponse> replay = replayIfPresent(OP_GRAPH_REPLACE, req.requestId(), hash,
                GraphResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                int current = graphRepo.currentVersionForUpdate();
                if (req.expectedVersion() != current) {
                    // 并发下同键请求在本事务等锁期间已由对方提交：重放其首次结果
                    Optional<GraphResponse> committed = replayIfPresent(
                            OP_GRAPH_REPLACE, req.requestId(), hash, GraphResponse.class);
                    if (committed.isPresent()) {
                        return committed.get();
                    }
                    throw conflict("GRAPH_VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前图版本 " + current + " 不一致");
                }
                long now = System.currentTimeMillis();
                int newVersion = current + 1;
                graphRepo.insertSegments(newVersion, req.segments().stream()
                        .map(RoutePlanningService::toSegment)
                        .toList());
                graphRepo.updateVersion(newVersion, now);
                GraphResponse response = new GraphResponse(newVersion, req.segments());
                idemRepo.insert(OP_GRAPH_REPLACE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_GRAPH_REPLACE, req.requestId(), hash, GraphResponse.class)
                    .orElseThrow(() -> conflict("GRAPH_VERSION_CONFLICT", "图版本并发替换冲突"));
        }
    }

    /**
     * 区段参数级校验：站点总数不超过 10、区段编号唯一、起终站不得相同。
     */
    private void validateSegments(List<LineSegmentRequest> segments) {
        Set<String> stations = new HashSet<>();
        Set<String> sectionIds = new HashSet<>();
        for (LineSegmentRequest s : segments) {
            stations.add(s.fromStation());
            stations.add(s.toStation());
            if (s.fromStation().equals(s.toStation())) {
                throw badRequest("区段起终站不得相同: " + s.sectionId());
            }
            if (!sectionIds.add(s.sectionId())) {
                throw badRequest("区段编号重复: " + s.sectionId());
            }
        }
        if (stations.size() > MAX_STATIONS) {
            throw badRequest("站点总数 " + stations.size() + " 超过上限 " + MAX_STATIONS);
        }
    }

    private static LineSegment toSegment(LineSegmentRequest r) {
        return new LineSegment(r.sectionId(), r.fromStation(), r.toStation(), r.travelMinutes());
    }

    private static LineSegmentRequest toSegmentRequest(LineSegment s) {
        return new LineSegmentRequest(s.sectionId(), s.fromStation(), s.toStation(),
                s.travelMinutes());
    }

    /**
     * 寻径发布：发布锁内按当前图枚举全部不重复站点、不中途等待的候选路径，
     * 按（总分钟，区段编号序列字典序）排序后逐条校验截止时刻、运营日与区段占用，
     * 取第一条可行路径一次创建并发布整张日计划；全部不可行返回 422 且不留任何数据。
     */
    public RoutePublishResponse publishRoute(RoutePublishRequest req) {
        validateRouteParams(req);
        String hash = hashRoutePublish(req);
        Optional<RoutePublishResponse> replay = replayIfPresent(OP_ROUTE_PUBLISH, req.requestId(),
                hash, RoutePublishResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                int current = graphRepo.currentVersionForUpdate();
                if (req.graphVersion() != current) {
                    // 并发下同键请求在本事务等锁期间已由对方提交：重放其首次结果
                    Optional<RoutePublishResponse> committed = replayIfPresent(
                            OP_ROUTE_PUBLISH, req.requestId(), hash, RoutePublishResponse.class);
                    if (committed.isPresent()) {
                        return committed.get();
                    }
                    throw conflict("GRAPH_VERSION_CONFLICT",
                            "graphVersion=" + req.graphVersion()
                                    + " 与当前图版本 " + current + " 不一致");
                }
                List<LineSegment> segments = graphRepo.findSegments(current);
                Set<String> stations = new HashSet<>();
                for (LineSegment s : segments) {
                    stations.add(s.fromStation());
                    stations.add(s.toStation());
                }
                if (!stations.contains(req.fromStation()) || !stations.contains(req.toStation())) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND",
                            "未知站点: " + req.fromStation() + " -> " + req.toStation());
                }
                if (planRepo.findByKey(req.scheduleKey()).isPresent()) {
                    // 并发下同键请求在本事务等锁期间已由对方提交：重放其首次结果
                    Optional<RoutePublishResponse> committed = replayIfPresent(
                            OP_ROUTE_PUBLISH, req.requestId(), hash, RoutePublishResponse.class);
                    if (committed.isPresent()) {
                        return committed.get();
                    }
                    throw conflict("SCHEDULE_KEY_EXISTS", "scheduleKey 已存在: " + req.scheduleKey());
                }
                List<Path> candidates = enumeratePaths(segments, req.fromStation(),
                        req.toStation());
                LocalDate opDate = req.departUtc().atZone(OPERATION_ZONE).toLocalDate();
                Instant dayStart = opDate.atStartOfDay(OPERATION_ZONE).toInstant();
                Instant dayEnd = opDate.plusDays(1).atStartOfDay(OPERATION_ZONE).toInstant();
                Path chosen = null;
                List<RouteLeg> chosenLegs = null;
                for (Path path : candidates) {
                    Instant arrival = req.departUtc().plusSeconds(path.totalMinutes() * 60L);
                    if (arrival.isAfter(req.latestArrivalUtc())) {
                        continue;
                    }
                    if (req.departUtc().isBefore(dayStart) || arrival.isAfter(dayEnd)) {
                        continue;
                    }
                    List<RouteLeg> legs = toLegs(0L, current, path, req.departUtc());
                    if (hasSectionConflict(opDate, legs)) {
                        continue;
                    }
                    chosen = path;
                    chosenLegs = legs;
                    break;
                }
                if (chosen == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_FEASIBLE_ROUTE",
                            "无可行路径：全部候选路径均超截止、跨运营日或区段被占用");
                }
                long now = System.currentTimeMillis();
                long planId = planRepo.insertPlan(req.scheduleKey(), opDate, PlanStatus.PUBLISHED,
                        now);
                List<RouteLeg> legs = chosenLegs.stream()
                        .map(leg -> new RouteLeg(planId, leg.graphVersion(), leg.seq(),
                                leg.sectionId(), leg.fromStation(), leg.toStation(),
                                leg.travelMinutes(), leg.startUtc(), leg.endUtc()))
                        .toList();
                List<Occupancy> occupancies = new ArrayList<>(legs.size());
                for (int i = 0; i < legs.size(); i++) {
                    RouteLeg leg = legs.get(i);
                    occupancies.add(new Occupancy(0L, planId, i, req.trainNo(), leg.sectionId(),
                            leg.startUtc(), leg.endUtc()));
                }
                planRepo.insertOccupancies(planId, occupancies);
                planRepo.insertRouteLegs(legs);
                RoutePublishResponse response = toResponse(req, opDate, current, chosen, legs);
                idemRepo.insert(OP_ROUTE_PUBLISH, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_ROUTE_PUBLISH, req.requestId(), hash,
                    RoutePublishResponse.class)
                    .orElseThrow(() -> conflict("SCHEDULE_KEY_EXISTS",
                            "scheduleKey 已存在: " + req.scheduleKey()));
        }
    }

    /**
     * 寻径请求参数级校验：出发必须 UTC 整分钟，最晚到达必须晚于出发，起终站不得相同。
     */
    private void validateRouteParams(RoutePublishRequest req) {
        if (req.departUtc().getEpochSecond() % 60 != 0) {
            throw badRequest("出发时刻必须为 UTC 整分钟: " + req.departUtc());
        }
        if (!req.latestArrivalUtc().isAfter(req.departUtc())) {
            throw badRequest("最晚到达必须晚于出发时刻");
        }
        if (req.fromStation().equals(req.toStation())) {
            throw badRequest("起终站不得相同: " + req.fromStation());
        }
    }

    /**
     * 候选路径：不重复站点的有向简单路径及总分钟。
     */
    private record Path(List<LineSegment> segments, int totalMinutes) {
    }

    /**
     * 枚举全部简单路径，按（总分钟升序，区段编号序列字典序）排序。
     */
    private List<Path> enumeratePaths(List<LineSegment> segments, String from, String to) {
        Map<String, List<LineSegment>> adjacency = new LinkedHashMap<>();
        for (LineSegment s : segments) {
            adjacency.computeIfAbsent(s.fromStation(), k -> new ArrayList<>()).add(s);
        }
        List<Path> paths = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(from);
        dfs(adjacency, from, to, visited, new ArrayList<>(), 0, paths);
        paths.sort(Comparator.comparingInt(Path::totalMinutes)
                .thenComparing(path -> path.segments().stream().map(LineSegment::sectionId).toList(),
                        (a, b) -> {
                            int n = Math.min(a.size(), b.size());
                            for (int i = 0; i < n; i++) {
                                int c = a.get(i).compareTo(b.get(i));
                                if (c != 0) {
                                    return c;
                                }
                            }
                            return Integer.compare(a.size(), b.size());
                        }));
        return paths;
    }

    private void dfs(Map<String, List<LineSegment>> adjacency, String current, String target,
                     Set<String> visited, List<LineSegment> stack, int minutes, List<Path> out) {
        if (current.equals(target)) {
            out.add(new Path(List.copyOf(stack), minutes));
            return;
        }
        for (LineSegment next : adjacency.getOrDefault(current, List.of())) {
            if (visited.contains(next.toStation())) {
                continue;
            }
            visited.add(next.toStation());
            stack.add(next);
            dfs(adjacency, next.toStation(), target, visited, stack,
                    minutes + next.travelMinutes(), out);
            stack.remove(stack.size() - 1);
            visited.remove(next.toStation());
        }
    }

    /**
     * 由候选路径生成逐段时刻（不中途等待，逐段累加行驶分钟）。
     */
    private List<RouteLeg> toLegs(long planId, int graphVersion, Path path, Instant depart) {
        List<RouteLeg> legs = new ArrayList<>(path.segments().size());
        Instant cursor = depart;
        for (int i = 0; i < path.segments().size(); i++) {
            LineSegment s = path.segments().get(i);
            Instant end = cursor.plusSeconds(s.travelMinutes() * 60L);
            legs.add(new RouteLeg(planId, graphVersion, i, s.sectionId(), s.fromStation(),
                    s.toStation(), s.travelMinutes(), cursor, end));
            cursor = end;
        }
        return legs;
    }

    /**
     * 候选路径各段与已发布计划在同运营日、同区段上的重叠检测（左闭右开，相邻合法）。
     */
    private boolean hasSectionConflict(LocalDate opDate, List<RouteLeg> legs) {
        List<String> sectionIds = legs.stream().map(RouteLeg::sectionId).distinct().toList();
        List<PublishedSlot> published = planRepo.findPublishedSlots(opDate, sectionIds, List.of());
        for (RouteLeg leg : legs) {
            for (PublishedSlot slot : published) {
                if (!leg.sectionId().equals(slot.sectionId())) {
                    continue;
                }
                if (leg.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(leg.endUtc())) {
                    return true;
                }
            }
        }
        return false;
    }

    private RoutePublishResponse toResponse(RoutePublishRequest req, LocalDate opDate,
                                            int graphVersion, Path path, List<RouteLeg> legs) {
        List<RouteLegView> views = legs.stream()
                .map(leg -> new RouteLegView(leg.sectionId(), leg.fromStation(), leg.toStation(),
                        leg.travelMinutes(), leg.startUtc(), leg.endUtc()))
                .toList();
        return new RoutePublishResponse(req.scheduleKey(), opDate, 1, PlanStatus.PUBLISHED.name(),
                graphVersion, req.trainNo(), req.fromStation(), req.toStation(), req.departUtc(),
                legs.get(legs.size() - 1).endUtc(), path.totalMinutes(), views);
    }

    /**
     * 幂等重放：存在记录且参数一致返回首次结果；参数不一致抛 409。
     */
    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED",
                        "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    /**
     * 图替换幂等身份：操作、操作者、期望版本与全部区段参数的结构化序列化，避免分隔符碰撞。
     */
    private String hashGraphReplace(ReplaceGraphRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_GRAPH_REPLACE);
        identity.put("operator", req.operator());
        identity.put("expectedVersion", req.expectedVersion());
        identity.put("segments", req.segments().stream()
                .map(s -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("sectionId", s.sectionId());
                    item.put("fromStation", s.fromStation());
                    item.put("toStation", s.toStation());
                    item.put("travelMinutes", s.travelMinutes());
                    return item;
                })
                .toList());
        return sha256(toJson(identity));
    }

    /**
     * 寻径发布幂等身份：操作、操作者、资源与全部参数（含图版本）的结构化序列化。
     */
    private String hashRoutePublish(RoutePublishRequest req) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("op", OP_ROUTE_PUBLISH);
        identity.put("operator", req.operator());
        identity.put("scheduleKey", req.scheduleKey());
        identity.put("graphVersion", req.graphVersion());
        identity.put("trainNo", req.trainNo());
        identity.put("fromStation", req.fromStation());
        identity.put("toStation", req.toStation());
        identity.put("departUtc", req.departUtc().toEpochMilli());
        identity.put("latestArrivalUtc", req.latestArrivalUtc().toEpochMilli());
        return sha256(toJson(identity));
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
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

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
