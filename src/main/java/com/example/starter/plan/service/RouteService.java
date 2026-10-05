package com.example.starter.plan.service;

import com.example.starter.plan.model.GraphSegment;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.PlanStatus;
import com.example.starter.plan.model.PublishedSlot;
import com.example.starter.plan.model.RoutePlanDetail;
import com.example.starter.plan.repo.GraphRepository;
import com.example.starter.plan.repo.IdempotencyRepository;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.web.ApiException;
import com.example.starter.plan.web.dto.GraphResponse;
import com.example.starter.plan.web.dto.GraphSegmentRequest;
import com.example.starter.plan.web.dto.GraphSegmentView;
import com.example.starter.plan.web.dto.ReplaceGraphRequest;
import com.example.starter.plan.web.dto.RoutePlanResponse;
import com.example.starter.plan.web.dto.RoutePublishRequest;
import com.example.starter.plan.web.dto.RouteSegmentView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * 线路图与寻径发布业务：图整体替换（expectedVersion 乐观校验，成功版本加一）、
 * 按当前图寻径并一次创建 PUBLISHED 日计划。
 *
 * <p>并发与幂等约定：图替换与寻径发布同普通发布共用全局发布锁，取同一一致视图，
 * 选路后至提交不会被另一发布抢占；写操作按 (操作类型, requestId) 幂等，
 * 身份哈希为操作类型加请求对象的结构化 JSON 的 SHA-256，避免分隔符碰撞；
 * 仅成功结果写入幂等记录，失败不占键；业务写入与幂等记录同事务提交。
 */
@Service
public class RouteService {

    private static final String OP_GRAPH_REPLACE = "GRAPH_REPLACE";
    private static final String OP_ROUTE_PUBLISH = "ROUTE_PUBLISH";

    /** 线路图站点数上限。 */
    static final int MAX_STATIONS = 10;

    private final PlanRepository planRepo;
    private final GraphRepository graphRepo;
    private final IdempotencyRepository idemRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public RouteService(PlanRepository planRepo, GraphRepository graphRepo,
                        IdempotencyRepository idemRepo, ObjectMapper objectMapper,
                        PlatformTransactionManager txManager) {
        this.planRepo = planRepo;
        this.graphRepo = graphRepo;
        this.idemRepo = idemRepo;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 查询当前线路图；尚未建图时版本为 0、区段为空。
     */
    public GraphResponse getGraph() {
        return toGraphResponse(graphRepo.findVersion().orElse(0), graphRepo.findSegments());
    }

    /**
     * 整体替换线路图：发布锁内校验 expectedVersion 等于当前版本，成功版本加一。
     */
    public GraphResponse replaceGraph(ReplaceGraphRequest req) {
        validateSegments(req.segments());
        String hash = hashRequest(OP_GRAPH_REPLACE, req);
        Optional<GraphResponse> replay =
                replayIfPresent(OP_GRAPH_REPLACE, req.requestId(), hash, GraphResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复查：并发同键请求在锁前未见到记录，此处重放首次结果
                Optional<GraphResponse> lockedReplay =
                        replayIfPresent(OP_GRAPH_REPLACE, req.requestId(), hash,
                                GraphResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                int current = graphRepo.findVersion().orElse(0);
                if (req.expectedVersion() != current) {
                    throw conflict("GRAPH_VERSION_CONFLICT",
                            "expectedVersion=" + req.expectedVersion()
                                    + " 与当前图版本 " + current + " 不一致");
                }
                int newVersion = current + 1;
                long now = System.currentTimeMillis();
                List<GraphSegment> segments = req.segments().stream()
                        .map(s -> new GraphSegment(s.segmentId(), s.fromStation(), s.toStation(),
                                s.minutes()))
                        .toList();
                graphRepo.replaceGraph(newVersion, segments, now);
                GraphResponse response = toGraphResponse(newVersion, segments);
                idemRepo.insert(OP_GRAPH_REPLACE, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_GRAPH_REPLACE, req.requestId(), hash, GraphResponse.class)
                    .orElseThrow(() -> conflict("GRAPH_VERSION_CONFLICT", "线路图替换冲突"));
        }
    }

    /**
     * 寻径发布：发布锁内按当前图枚举无重复站点的候选路径，按（总分钟，区段编号字典序）
     * 优选顺序依次过滤（最晚到达、不跨 Asia/Shanghai 运营日、区段时隙互斥），
     * 选中后同一事务创建 PUBLISHED 日计划、占用与冻结快照；无可行路径抛 422 且不建任何数据。
     */
    public RoutePlanResponse publishRoute(RoutePublishRequest req) {
        validateDepart(req);
        String hash = hashRequest(OP_ROUTE_PUBLISH, req);
        Optional<RoutePlanResponse> replay =
                replayIfPresent(OP_ROUTE_PUBLISH, req.requestId(), hash, RoutePlanResponse.class);
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return tx.execute(status -> {
                planRepo.acquirePublishLock();
                // 锁内复查：并发同键请求在锁前未见到记录，此处重放首次结果
                Optional<RoutePlanResponse> lockedReplay =
                        replayIfPresent(OP_ROUTE_PUBLISH, req.requestId(), hash,
                                RoutePlanResponse.class);
                if (lockedReplay.isPresent()) {
                    return lockedReplay.get();
                }
                int graphVersion = graphRepo.findVersion().orElse(0);
                if (req.graphVersion() != graphVersion) {
                    throw conflict("GRAPH_VERSION_CONFLICT",
                            "graphVersion=" + req.graphVersion()
                                    + " 与当前图版本 " + graphVersion + " 不一致");
                }
                List<GraphSegment> segments = graphRepo.findSegments();
                Set<String> stations = new HashSet<>();
                segments.forEach(s -> {
                    stations.add(s.fromStation());
                    stations.add(s.toStation());
                });
                if (!stations.contains(req.fromStation())) {
                    throw notFoundStation(req.fromStation());
                }
                if (!stations.contains(req.toStation())) {
                    throw notFoundStation(req.toStation());
                }
                if (req.fromStation().equals(req.toStation())) {
                    throw badRequest("起终站必须不同: " + req.fromStation());
                }
                LocalDate opDate = req.departUtc().atZone(PlanService.OPERATION_ZONE).toLocalDate();
                Instant dayEnd = opDate.plusDays(1)
                        .atStartOfDay(PlanService.OPERATION_ZONE).toInstant();
                List<PublishedSlot> published = planRepo.findPublishedSlots(opDate,
                        segments.stream().map(GraphSegment::segmentId).toList(), List.of());
                List<PathFinder.Path> candidates =
                        PathFinder.findPaths(segments, req.fromStation(), req.toStation());
                List<RouteSegmentView> chosenViews = null;
                int chosenMinutes = 0;
                for (PathFinder.Path path : candidates) {
                    Instant arrive = req.departUtc().plusSeconds(path.totalMinutes() * 60L);
                    if (arrive.isAfter(req.latestArrivalUtc()) || arrive.isAfter(dayEnd)) {
                        continue;
                    }
                    List<RouteSegmentView> views = toSegmentViews(path, req.departUtc());
                    if (!hasConflict(views, published)) {
                        chosenViews = views;
                        chosenMinutes = path.totalMinutes();
                        break;
                    }
                }
                if (chosenViews == null) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ROUTE_INFEASIBLE",
                            "无可行路径：候选路径均超最晚到达、跨运营日或区段被占用", List.of());
                }
                long now = System.currentTimeMillis();
                String scheduleKey = "RTE-" + UUID.randomUUID();
                long planId = planRepo.insertPlan(scheduleKey, opDate, PlanStatus.PUBLISHED, now);
                List<Occupancy> occupancies = new ArrayList<>(chosenViews.size());
                for (int i = 0; i < chosenViews.size(); i++) {
                    RouteSegmentView v = chosenViews.get(i);
                    occupancies.add(new Occupancy(0L, planId, i, req.trainNo(), v.segmentId(),
                            v.startUtc(), v.endUtc()));
                }
                planRepo.insertOccupancies(planId, occupancies);
                Instant arriveUtc = chosenViews.get(chosenViews.size() - 1).endUtc();
                List<String> segmentIds = chosenViews.stream()
                        .map(RouteSegmentView::segmentId).toList();
                planRepo.insertRouteDetail(new RoutePlanDetail(planId, graphVersion, req.trainNo(),
                        req.fromStation(), req.toStation(), req.departUtc(), arriveUtc,
                        chosenMinutes, toJson(segmentIds)), now);
                RoutePlanResponse response = new RoutePlanResponse(scheduleKey, opDate, 1,
                        PlanStatus.PUBLISHED.name(), graphVersion, req.trainNo(), req.fromStation(),
                        req.toStation(), req.departUtc(), arriveUtc, chosenMinutes, chosenViews);
                idemRepo.insert(OP_ROUTE_PUBLISH, req.requestId(), hash, toJson(response), now);
                return response;
            });
        } catch (DuplicateKeyException e) {
            return replayIfPresent(OP_ROUTE_PUBLISH, req.requestId(), hash, RoutePlanResponse.class)
                    .orElseThrow(() -> conflict("ROUTE_PUBLISH_CONFLICT", "寻径发布冲突，请重试"));
        }
    }

    private void validateDepart(RoutePublishRequest req) {
        Instant depart = req.departUtc();
        if (depart.getEpochSecond() % 60 != 0 || depart.getNano() != 0) {
            throw badRequest("出发时刻必须为 UTC 整分钟: " + depart);
        }
        if (!req.latestArrivalUtc().isAfter(depart)) {
            throw badRequest("最晚到达必须晚于出发时刻");
        }
    }

    /**
     * 逐段累加行驶时间生成各段时刻（不中途等待，区间左闭右开）。
     */
    private List<RouteSegmentView> toSegmentViews(PathFinder.Path path, Instant departUtc) {
        List<RouteSegmentView> views = new ArrayList<>(path.segments().size());
        Instant cursor = departUtc;
        for (GraphSegment segment : path.segments()) {
            Instant end = cursor.plusSeconds(segment.minutes() * 60L);
            views.add(new RouteSegmentView(segment.segmentId(), segment.fromStation(),
                    segment.toStation(), cursor, end));
            cursor = end;
        }
        return views;
    }

    /**
     * 候选路径各段与已发布时隙的重叠检测（左闭右开，相邻合法）。
     */
    private boolean hasConflict(List<RouteSegmentView> views, List<PublishedSlot> published) {
        for (RouteSegmentView view : views) {
            for (PublishedSlot slot : published) {
                if (!view.segmentId().equals(slot.sectionId())) {
                    continue;
                }
                if (view.startUtc().isBefore(slot.endUtc())
                        && slot.startUtc().isBefore(view.endUtc())) {
                    return true;
                }
            }
        }
        return false;
    }

    private ApiException notFoundStation(String station) {
        return new ApiException(HttpStatus.NOT_FOUND, "STATION_NOT_FOUND", "站点不存在: " + station);
    }

    // ---------- 内部实现 ----------

    private void validateSegments(List<GraphSegmentRequest> segments) {
        Set<String> ids = new HashSet<>();
        Set<String> stations = new HashSet<>();
        for (GraphSegmentRequest s : segments) {
            if (!ids.add(s.segmentId())) {
                throw badRequest("区段编号重复: " + s.segmentId());
            }
            if (s.fromStation().equals(s.toStation())) {
                throw badRequest("区段起终站必须不同: " + s.segmentId());
            }
            stations.add(s.fromStation());
            stations.add(s.toStation());
        }
        if (stations.size() > MAX_STATIONS) {
            throw badRequest("站点数超过上限 " + MAX_STATIONS + ": " + stations.size());
        }
    }

    private GraphResponse toGraphResponse(int version, List<GraphSegment> segments) {
        Set<String> stations = new TreeSet<>();
        List<GraphSegmentView> views = segments.stream()
                .map(s -> {
                    stations.add(s.fromStation());
                    stations.add(s.toStation());
                    return new GraphSegmentView(s.segmentId(), s.fromStation(), s.toStation(),
                            s.minutes());
                })
                .toList();
        return new GraphResponse(version, List.copyOf(stations), views);
    }

    /**
     * 幂等身份：操作类型 + 请求对象结构化 JSON 的 SHA-256，避免分隔符碰撞。
     */
    private String hashRequest(String opType, Object req) {
        return sha256(opType + '\n' + toJson(req));
    }

    private <T> Optional<T> replayIfPresent(String opType, String requestId, String hash,
                                            Class<T> type) {
        return idemRepo.find(opType, requestId).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw conflict("IDEMPOTENT_KEY_REUSED", "requestId 已用于其他参数: " + requestId);
            }
            return fromJson(record.responseJson(), type);
        });
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
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

    private ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", message);
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
