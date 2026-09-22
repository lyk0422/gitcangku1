package com.example.starter.airspace.service;

import com.example.starter.airspace.domain.DomainRows.RequestRecordRow;
import com.example.starter.airspace.domain.DomainRows.ReviewRow;
import com.example.starter.airspace.domain.DomainRows.RouteRow;
import com.example.starter.airspace.domain.DomainRows.ZoneRow;
import com.example.starter.airspace.error.ApiException;
import com.example.starter.airspace.geom.Geometry;
import com.example.starter.airspace.geom.Geometry.Point;
import com.example.starter.airspace.geom.Geometry.Rect;
import com.example.starter.airspace.repo.AirspaceRepository;
import com.example.starter.airspace.web.dto.ApiDtos.ReviewResponse;
import com.example.starter.airspace.web.dto.ApiDtos.RouteResponse;
import com.example.starter.airspace.web.dto.ApiDtos.ZoneResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/**
 * 禁飞区域与航线版本审查业务服务。
 *
 * <p>并发控制：写操作与审核均在单事务内先对 airspace_meta 单行加行锁（审核另锁航线行），
 * 保证“提交的空域版本 = 读取有效禁飞区时的版本”，杜绝携带新版本却使用旧区域的结论；
 * 业务变更与幂等记录在同一事务内原子提交（异常整体回滚，失败不占键）。</p>
 *
 * <p>幂等：事务开始即插入 PROCESSING 占位键，同键请求在数据库上串行：先提交者完成后，
 * 后到者读取原成功结果重放（同参）或 409（异参）；先到者回滚则占位键消失，后到者正常执行。</p>
 */
@Service
public class AirspaceService {

    /** 写操作执行结果；重放时 replay=true，rawBody 为原成功响应 JSON。 */
    public record OperationResult(int statusCode, boolean replay, Object response, String rawBody) {
        static OperationResult created(String rawBody) {
            return new OperationResult(201, false, null, rawBody);
        }

        static OperationResult ok(String rawBody) {
            return new OperationResult(200, false, null, rawBody);
        }

        static OperationResult replay(int statusCode, String rawBody) {
            return new OperationResult(statusCode, true, null, rawBody);
        }
    }

    static final int COORD_MIN = -100000;
    static final int COORD_MAX = 100000;
    static final int MIN_POINTS = 2;
    static final int MAX_POINTS = 50;

    static final String OP_ZONE_CREATE = "ZONE_CREATE";
    static final String OP_ZONE_REVOKE = "ZONE_REVOKE";
    static final String OP_ROUTE_CREATE = "ROUTE_CREATE";
    static final String OP_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String OP_REVIEW = "REVIEW";

    static final String CONCLUSION_CLEAR = "CLEAR";
    static final String CONCLUSION_BLOCKED = "BLOCKED";

    static final String STATE_ACTIVE = "ACTIVE";
    static final String STATE_REVOKED = "REVOKED";

    /** 幂等记录占位状态码：0 表示同键前序事务进行中。 */
    private static final int STATUS_PROCESSING = 0;

    private final AirspaceRepository repo;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AirspaceService(AirspaceRepository repo,
                           PlatformTransactionManager txManager,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.repo = repo;
        this.tx = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------- 禁飞区 ----------

    /** 创建禁飞区（非退化闭矩形）；成功后全局空域版本加一。 */
    public OperationResult createZone(String requestId, String zoneId,
                                      Integer xMin, Integer yMin, Integer xMax, Integer yMax) {
        validateZone(xMin, yMin, xMax, yMax);
        String fp = fingerprint(List.of(OP_ZONE_CREATE, zoneId, xMin, yMin, xMax, yMax));
        return tx.execute(status -> withIdempotency(requestId, OP_ZONE_CREATE, fp, () -> {
            repo.lockGlobalVersion();
            ZoneRow existing = repo.findZone(zoneId);
            if (existing != null) {
                throw ApiException.conflict("ZONE_ALREADY_EXISTS",
                        "禁飞区已存在且不可重建: " + zoneId);
            }
            int newVersion = repo.incrementGlobalVersion();
            ZoneRow row = new ZoneRow(zoneId, xMin, yMin, xMax, yMax, true,
                    newVersion, null, clock.instant());
            repo.insertZone(row);
            return OperationResult.created(toJson(toZoneResponse(row)));
        }));
    }

    /** 撤销禁飞区；成功后全局空域版本加一，已撤销不可再次撤销。 */
    public OperationResult revokeZone(String requestId, String zoneId) {
        String fp = fingerprint(List.of(OP_ZONE_REVOKE, zoneId));
        return tx.execute(status -> withIdempotency(requestId, OP_ZONE_REVOKE, fp, () -> {
            repo.lockGlobalVersion();
            ZoneRow existing = repo.findZone(zoneId);
            if (existing == null) {
                throw ApiException.notFound("禁飞区不存在: " + zoneId);
            }
            if (!existing.active()) {
                throw ApiException.conflict("ZONE_ALREADY_REVOKED",
                        "禁飞区已撤销，不能重复撤销: " + zoneId);
            }
            int newVersion = repo.incrementGlobalVersion();
            repo.revokeZone(zoneId, newVersion, clock.instant());
            ZoneRow updated = new ZoneRow(existing.zoneId(), existing.xMin(), existing.yMin(),
                    existing.xMax(), existing.yMax(), false, existing.createdVersion(),
                    newVersion, existing.createdAt());
            return OperationResult.ok(toJson(toZoneResponse(updated)));
        }));
    }

    // ---------- 航线 ----------

    /** 创建航线（版本1）。 */
    public OperationResult createRoute(String requestId, String routeId, List<Point> points) {
        List<Point> valid = validatePoints(points);
        String fp = fingerprint(List.of(OP_ROUTE_CREATE, routeId, pointsToJson(valid)));
        return tx.execute(status -> withIdempotency(requestId, OP_ROUTE_CREATE, fp, () -> {
            RouteRow existing = repo.findRoute(routeId);
            if (existing != null) {
                throw ApiException.conflict("ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + routeId);
            }
            var now = clock.instant();
            repo.insertRoute(routeId, valid, now);
            RouteResponse response = new RouteResponse(routeId, 1, valid.size());
            return OperationResult.created(toJson(response));
        }));
    }

    /** 替换航线点列，expectedVersion 不匹配返回 409；成功后航线版本加一。 */
    public OperationResult replaceRoute(String requestId, String routeId,
                                        int expectedVersion, List<Point> points) {
        List<Point> valid = validatePoints(points);
        String fp = fingerprint(
                List.of(OP_ROUTE_REPLACE, routeId, expectedVersion, pointsToJson(valid)));
        return tx.execute(status -> withIdempotency(requestId, OP_ROUTE_REPLACE, fp, () -> {
            RouteRow row = repo.lockRoute(routeId);
            if (row == null) {
                throw ApiException.notFound("航线不存在: " + routeId);
            }
            if (row.version() != expectedVersion) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "expectedVersion=" + expectedVersion + " 不是当前航线版本 "
                                + row.version());
            }
            boolean replaced = repo.replaceRoutePoints(routeId, expectedVersion, valid,
                    clock.instant());
            if (!replaced) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "expectedVersion=" + expectedVersion + " 与当前航线版本不一致");
            }
            RouteResponse response = new RouteResponse(routeId, expectedVersion + 1, valid.size());
            return OperationResult.ok(toJson(response));
        }));
    }

    // ---------- 审核 ----------

    /**
     * 提交审核：routeVersion/airspaceVersion 任一非当前版本返回 409；
     * 持空域行锁与航线行锁读取一致状态并保存不可变结论。
     */
    public OperationResult submitReview(String requestId, String routeId,
                                        int routeVersion, int airspaceVersion) {
        if (routeVersion < 1 || airspaceVersion < 0) {
            throw ApiException.badRequest("版本号非法");
        }
        String fp = fingerprint(
                List.of(OP_REVIEW, routeId, routeVersion, airspaceVersion));
        return tx.execute(status -> withIdempotency(requestId, OP_REVIEW, fp, () -> {
            // 先锁空域版本行：与一切禁飞区变更互斥
            int globalVersion = repo.lockGlobalVersion();
            if (globalVersion != airspaceVersion) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "airspaceVersion=" + airspaceVersion + " 不是当前空域版本 "
                                + globalVersion);
            }
            // 再锁航线行：与航线替换互斥；固定加锁顺序（空域→航线）避免死锁
            RouteRow route = repo.lockRoute(routeId);
            if (route == null) {
                throw ApiException.notFound("航线不存在: " + routeId);
            }
            if (route.version() != routeVersion) {
                throw ApiException.conflict("VERSION_MISMATCH",
                        "routeVersion=" + routeVersion + " 不是当前航线版本 "
                                + route.version());
            }
            List<Point> points = repo.findRoutePoints(routeId, routeVersion);
            List<ZoneRow> zones = repo.findActiveZones();

            TreeSet<String> hits = new TreeSet<>();
            for (ZoneRow zone : zones) {
                Rect rect = new Rect(zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax());
                if (Geometry.polylineIntersects(points, rect)) {
                    hits.add(zone.zoneId());
                }
            }
            String conclusion = hits.isEmpty() ? CONCLUSION_CLEAR : CONCLUSION_BLOCKED;
            ReviewRow review = new ReviewRow(
                    "rv-" + java.util.UUID.randomUUID(),
                    routeId, routeVersion, airspaceVersion, conclusion,
                    List.copyOf(hits), clock.instant());
            repo.insertReview(review);
            return OperationResult.created(toJson(toReviewResponse(review)));
        }));
    }

    // ---------- 查询 ----------

    /** 查询某航线全部历史审核结果（永不改写原结论）。 */
    public List<ReviewResponse> listReviewHistory(String routeId) {
        if (repo.findRoute(routeId) == null) {
            throw ApiException.notFound("航线不存在: " + routeId);
        }
        return repo.findReviewsByRoute(routeId).stream().map(this::toReviewResponse).toList();
    }

    /** 查询当前可用结论视图：最新结论版本任一不匹配当前版本即 STALE。 */
    public CurrentView getCurrentReview(String routeId) {
        RouteRow route = repo.findRoute(routeId);
        if (route == null) {
            throw ApiException.notFound("航线不存在: " + routeId);
        }
        List<ReviewRow> reviews = repo.findReviewsByRoute(routeId);
        if (reviews.isEmpty()) {
            throw ApiException.notFound("该航线尚无审核结论: " + routeId);
        }
        ReviewRow latest = reviews.get(reviews.size() - 1);
        int globalVersion = repo.getGlobalVersion();
        boolean current = latest.routeVersion() == route.version()
                && latest.airspaceVersion() == globalVersion;
        return new CurrentView(current ? "CURRENT" : "STALE", toReviewResponse(latest));
    }

    /** 当前结论视图。 */
    public record CurrentView(String status, ReviewResponse review) {
    }

    // ---------- 校验 ----------

    void validateZone(Integer xMin, Integer yMin, Integer xMax, Integer yMax) {
        if (xMin == null || yMin == null || xMax == null || yMax == null) {
            throw ApiException.badRequest("矩形坐标不能为空");
        }
        checkCoord(xMin, "xMin");
        checkCoord(yMin, "yMin");
        checkCoord(xMax, "xMax");
        checkCoord(yMax, "yMax");
        if (xMin >= xMax || yMin >= yMax) {
            throw ApiException.unprocessable(
                    "禁飞区必须为非退化矩形，要求 xMin<xMax 且 yMin<yMax");
        }
    }

    List<Point> validatePoints(List<Point> points) {
        if (points == null || points.size() < MIN_POINTS || points.size() > MAX_POINTS) {
            throw ApiException.unprocessable(
                    "航线点数量必须在 " + MIN_POINTS + "~" + MAX_POINTS + " 个之间");
        }
        List<Point> result = new ArrayList<>(points.size());
        boolean anyDifferent = false;
        Point first = points.get(0);
        for (Point p : points) {
            if (p == null) {
                throw ApiException.badRequest("航线点不能为空");
            }
            checkCoord(p.x(), "x");
            checkCoord(p.y(), "y");
            result.add(p);
            if (p.x() != first.x() || p.y() != first.y()) {
                anyDifferent = true;
            }
        }
        if (!anyDifferent) {
            throw ApiException.unprocessable("航线至少两个点不同");
        }
        return result;
    }

    private void checkCoord(int value, String name) {
        if (value < COORD_MIN || value > COORD_MAX) {
            throw ApiException.unprocessable(
                    "坐标 " + name + "=" + value + " 超出范围 [" + COORD_MIN + "," + COORD_MAX + "]");
        }
    }

    // ---------- 幂等 ----------

    /**
     * 幂等包装：先占 PROCESSING 键。同键已完成则同参重放、异参 409；
     * 业务异常随事务回滚，占位键一并消失（失败不占键）；成功时把原响应原子写入键记录。
     */
    private OperationResult withIdempotency(String requestId, String operation,
                                            String fingerprint,
                                            java.util.function.Supplier<OperationResult> action) {
        RequestRecordRow existing = acquireDedupSlot(requestId, operation, fingerprint);
        if (existing != null) {
            return OperationResult.replay(existing.statusCode(), existing.responseBody());
        }
        OperationResult result = action.get();
        repo.completeRequestRecord(requestId, result.statusCode(), result.rawBody());
        return result;
    }

    /**
     * 插入 PROCESSING 占位键，返回 null 表示本事务获得执行权；
     * 返回已完成记录表示应重放原成功结果。同键事务并发时阻塞等待先到事务结束：
     * 先到者提交则重放（同参）/409（异参）；先到者回滚则占位键消失，本请求获得执行权。
     */
    private RequestRecordRow acquireDedupSlot(String requestId, String operation,
                                              String fingerprint) {
        long deadline = System.currentTimeMillis() + 10_000L;
        boolean inserted = false;
        while (!inserted) {
            try {
                repo.insertRequestRecord(new RequestRecordRow(
                        requestId, operation, fingerprint, STATUS_PROCESSING,
                        "", clock.instant()));
                inserted = true;
            } catch (DuplicateKeyException duplicate) {
                RequestRecordRow row = repo.findRequestRecord(requestId);
                if (row == null) {
                    // 先到事务刚好回滚（失败不占键）：重试占位
                    continue;
                }
                if (!row.fingerprint().equals(fingerprint)
                        || !row.operation().equals(operation)) {
                    throw ApiException.conflict("IDEMPOTENCY_PARAM_MISMATCH",
                            "requestId=" + requestId + " 已用于不同参数的成功请求");
                }
                if (row.statusCode() != STATUS_PROCESSING) {
                    return row;
                }
                if (System.currentTimeMillis() > deadline) {
                    throw ApiException.conflict("REQUEST_IN_PROGRESS",
                            "requestId=" + requestId + " 的前序请求仍在处理中");
                }
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw ApiException.conflict("REQUEST_IN_PROGRESS",
                            "等待同键前序请求时被中断");
                }
            }
        }
        return null;
    }

    // ---------- 辅助 ----------

    String fingerprint(List<Object> parts) {
        String raw = toJson(parts);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    List<List<Integer>> pointsToJson(List<Point> points) {
        return points.stream().map(p -> List.of(p.x(), p.y())).toList();
    }

    ZoneResponse toZoneResponse(ZoneRow row) {
        return new ZoneResponse(row.zoneId(),
                row.active() ? STATE_ACTIVE : STATE_REVOKED,
                row.active() ? row.createdVersion() : row.revokedVersion(),
                row.xMin(), row.yMin(), row.xMax(), row.yMax());
    }

    ReviewResponse toReviewResponse(ReviewRow row) {
        return new ReviewResponse(row.reviewId(), row.routeId(), row.routeVersion(),
                row.airspaceVersion(), row.conclusion(), row.hitZoneIds(),
                DateTimeFormatter.ISO_INSTANT.format(row.createdAt()));
    }
}
