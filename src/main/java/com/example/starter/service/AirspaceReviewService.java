package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.ReviewRequest;
import com.example.starter.api.dto.ReviewResultDto;
import com.example.starter.api.dto.RouteCreateRequest;
import com.example.starter.api.dto.RoutePointDto;
import com.example.starter.api.dto.RouteReplaceRequest;
import com.example.starter.api.dto.RouteResult;
import com.example.starter.api.dto.ZoneCreateRequest;
import com.example.starter.api.dto.ZoneResult;
import com.example.starter.api.dto.ZoneRevokeRequest;
import com.example.starter.domain.Point;
import com.example.starter.domain.ZoneStatus;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 禁飞区与航线审查业务服务。
 *
 * <p>所有写操作通过 {@link IdempotentExecutor} 在同一数据库事务内完成业务变更与
 * 幂等去重记录的原子提交；失败（含业务冲突）回滚事务，不占用 requestId。</p>
 *
 * <p>审核事务由 {@link ReviewEngine} 先锁全局空域版本行、再锁航线行：
 * 区域变更事务必须更新版本行，因而与审核互斥，保证审核使用的空域版本号与全部
 * 禁飞区来自同一已提交状态。</p>
 */
@Service
public class AirspaceReviewService {

    static final String KIND_ZONE_CREATE = "ZONE_CREATE";
    static final String KIND_ZONE_REVOKE = "ZONE_REVOKE";
    static final String KIND_ROUTE_CREATE = "ROUTE_CREATE";
    static final String KIND_ROUTE_REPLACE = "ROUTE_REPLACE";
    static final String KIND_REVIEW = "REVIEW";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final IdempotentExecutor idempotent;
    private final ReviewEngine reviewEngine;
    private final TransactionTemplate txTemplate;

    public AirspaceReviewService(AirspaceRepository airspaceRepo,
                                 RouteRepository routeRepo,
                                 ReviewRepository reviewRepo,
                                 IdempotentExecutor idempotent,
                                 ReviewEngine reviewEngine,
                                 PlatformTransactionManager transactionManager) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.idempotent = idempotent;
        this.reviewEngine = reviewEngine;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // ============================ 禁飞区 ============================

    /** 创建禁飞区；同键同参重放原结果，异参 409。 */
    public MutationResponse createZone(ZoneCreateRequest request) {
        return idempotent.execute(request.requestId(), KIND_ZONE_CREATE, request, () -> {
            if (request.xMin() >= request.xMax() || request.yMin() >= request.yMax()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ZONE_RECTANGLE",
                        "禁飞区必须是非退化轴对齐矩形：xMin < xMax 且 yMin < yMax");
            }
            if (airspaceRepo.findZone(request.zoneId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_EXISTS",
                        "禁飞区已存在: " + request.zoneId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            airspaceRepo.insertZone(new ZonePo(request.zoneId(), request.xMin(), request.yMin(),
                    request.xMax(), request.yMax(), ZoneStatus.ACTIVE.name(), newVersion, null));
            return new MutationResponse(request.requestId(), false,
                    new ZoneResult(request.zoneId(), ZoneStatus.ACTIVE.name(), newVersion));
        });
    }

    /** 撤销禁飞区（只能撤销一次，撤销使空域版本加一）。 */
    public MutationResponse revokeZone(ZoneRevokeRequest request) {
        return idempotent.execute(request.requestId(), KIND_ZONE_REVOKE, request, () -> {
            ZonePo zone = airspaceRepo.findZone(request.zoneId());
            if (zone == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ZONE_NOT_FOUND",
                        "禁飞区不存在: " + request.zoneId());
            }
            if (ZoneStatus.REVOKED.name().equals(zone.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ZONE_ALREADY_REVOKED",
                        "禁飞区已撤销: " + request.zoneId());
            }
            long newVersion = airspaceRepo.incrementGlobalVersion();
            airspaceRepo.markRevoked(request.zoneId(), newVersion);
            return new MutationResponse(request.requestId(), false,
                    new ZoneResult(request.zoneId(), ZoneStatus.REVOKED.name(), newVersion));
        });
    }

    // ============================ 航线 ============================

    /** 创建航线（初始版本 1）。 */
    public MutationResponse createRoute(RouteCreateRequest request) {
        return idempotent.execute(request.requestId(), KIND_ROUTE_CREATE, request, () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            if (routeRepo.findRoute(request.routeId()) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "ROUTE_ALREADY_EXISTS",
                        "航线已存在: " + request.routeId());
            }
            routeRepo.insertRoute(request.routeId(), points);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), 1));
        });
    }

    /** 替换航线点列，expectedVersion 不匹配返回 409；成功版本加一。 */
    public MutationResponse replaceRoute(RouteReplaceRequest request) {
        return idempotent.execute(request.requestId(), KIND_ROUTE_REPLACE, request, () -> {
            List<Point> points = toPoints(request.points());
            validatePointsDistinct(points);
            RoutePo route = routeRepo.findRoute(request.routeId());
            if (route == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                        "航线不存在: " + request.routeId());
            }
            if (route.version() != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本不匹配：expected=" + request.expectedVersion()
                                + ", current=" + route.version());
            }
            // 条件更新兜底并发替换：更新 0 行说明版本已被其他事务推进
            int updated = routeRepo.compareAndIncrementVersion(
                    request.routeId(), request.expectedVersion());
            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                        "航线版本已变化，请使用最新 expectedVersion 重试");
            }
            routeRepo.deletePoints(request.routeId());
            routeRepo.insertPoints(request.routeId(), points);
            return new MutationResponse(request.requestId(), false,
                    new RouteResult(request.routeId(), request.expectedVersion() + 1));
        });
    }

    // ============================ 审核 ============================

    /** 提交常规审核（不携带时空窗，不裁决应急走廊）：任一指定版本不是当前版本返回 409。 */
    public MutationResponse review(ReviewRequest request) {
        return idempotent.execute(request.requestId(), KIND_REVIEW, request, () -> {
            ReviewPo po = reviewEngine.performReview(request.routeId(), request.routeVersion(),
                    request.airspaceVersion(), ReviewEngine.KIND_REGULAR, request.requestId(), null);
            return new MutationResponse(request.requestId(), false, toDto(po, po.conclusion(), true));
        });
    }

    /**
     * 提交携带 UTC 左闭右开时空窗的常规审核：版本裁决外，命中任一有效应急走廊时
     * 返回 409（CORRIDOR_CONFLICT）并在错误信息中给出走廊标识；结果不可变。
     */
    public MutationResponse reviewWithWindow(String routeId, int routeVersion, long airspaceVersion,
                                             SpaceTimeWindow window, String requestId) {
        return idempotent.execute(requestId, KIND_REVIEW,
                new RegularWindowReviewParams(routeId, routeVersion, airspaceVersion, window, requestId),
                () -> {
                    ReviewPo po = reviewEngine.performReview(routeId, routeVersion, airspaceVersion,
                            ReviewEngine.KIND_REGULAR, requestId, window);
                    return new MutationResponse(requestId, false, toDto(po, po.conclusion(), true));
                });
    }

    /** 按 reviewId 查询历史审核，保留原结论。 */
    public ReviewResultDto getReview(String reviewId) {
        ReviewPo po = reviewRepo.findReview(reviewId);
        if (po == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                    "审核记录不存在: " + reviewId);
        }
        // 历史查询永远返回保存时的原结论，不重新计算
        return toDto(po, po.conclusion(), null);
    }

    /** 查询某航线当前可用结论；任何相关版本不再匹配返回 STALE。 */
    public ReviewResultDto getCurrentReview(String routeId) {
        return txTemplate.execute(status -> {
            ReviewPo latest = reviewRepo.findLatestReview(routeId);
            if (latest == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND",
                        "该航线尚无审核记录: " + routeId);
            }
            RoutePo currentRoute = routeRepo.findRoute(routeId);
            long globalVersion = airspaceRepo.getGlobalVersion();
            boolean current = currentRoute != null
                    && currentRoute.version() == latest.routeVersion()
                    && globalVersion == latest.airspaceVersion();
            // 不匹配时绝不能把旧 CLEAR/BLOCKED 当成当前结论，统一返回 STALE
            String conclusion = current ? latest.conclusion() : "STALE";
            return toDto(latest, conclusion, current);
        });
    }

    // ============================ 工具方法 ============================

    private static List<Point> toPoints(List<RoutePointDto> dtos) {
        List<Point> points = new ArrayList<>(dtos.size());
        for (RoutePointDto dto : dtos) {
            points.add(new Point(dto.x(), dto.y()));
        }
        return points;
    }

    private static void validatePointsDistinct(List<Point> points) {
        Point first = points.get(0);
        for (Point p : points) {
            if (p.x() != first.x() || p.y() != first.y()) {
                return;
            }
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "ROUTE_POINTS_IDENTICAL",
                "航线至少需要两个不同的点");
    }

    /** 将不可变审查记录转为对外 DTO；conclusion 可为 CLEAR/BLOCKED/STALE。 */
    static ReviewResultDto toDto(ReviewPo po, String conclusion, Boolean current) {
        List<RoutePointDto> snapshot = new ArrayList<>(po.pointsSnapshot().size());
        for (Point p : po.pointsSnapshot()) {
            snapshot.add(new RoutePointDto(p.x(), p.y()));
        }
        return new ReviewResultDto(po.reviewId(), po.routeId(), po.routeVersion(),
                po.airspaceVersion(), conclusion, List.copyOf(po.hitZoneIds()), snapshot,
                current, po.reviewKind());
    }

    /** 携带时空窗的常规审核参数载体（仅用于规范化幂等哈希）。 */
    private record RegularWindowReviewParams(String routeId, int routeVersion, long airspaceVersion,
                                             SpaceTimeWindow window, String requestId) {
    }
}
