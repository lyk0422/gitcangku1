package com.example.starter.service;

import com.example.starter.api.ApiException;
import com.example.starter.domain.Geometry;
import com.example.starter.domain.ReviewConclusion;
import com.example.starter.repo.AirspaceRepository;
import com.example.starter.repo.CorridorPo;
import com.example.starter.repo.CorridorRepository;
import com.example.starter.repo.ReviewPo;
import com.example.starter.repo.ReviewRepository;
import com.example.starter.repo.RoutePo;
import com.example.starter.repo.RouteRepository;
import com.example.starter.repo.ZonePo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 禁飞审查引擎：在调用方事务内锁全局空域版本行与航线行，
 * 固化航线版本、空域版本、命中禁飞区与航点快照，生成不可变审查记录。
 *
 * <p>REGULAR 常规审查携带时空窗时，还会裁决有效应急走廊：
 * 航线在该时间/高度窗内命中任一有效走廊则返回 409 并给出走廊标识，
 * 走廊不能成为常规航线通过审查的通道。</p>
 *
 * <p>EMERGENCY 应急审查只做禁飞区裁决，不能以走廊绕过禁区；
 * 走廊命中关系在占用登记时单独裁决。</p>
 */
@Component
public class ReviewEngine {

    /** 常规审查种类。 */
    public static final String KIND_REGULAR = "REGULAR";
    /** 应急审查种类。 */
    public static final String KIND_EMERGENCY = "EMERGENCY";

    private final AirspaceRepository airspaceRepo;
    private final RouteRepository routeRepo;
    private final ReviewRepository reviewRepo;
    private final CorridorRepository corridorRepo;
    private final Clock clock;

    public ReviewEngine(AirspaceRepository airspaceRepo,
                        RouteRepository routeRepo,
                        ReviewRepository reviewRepo,
                        CorridorRepository corridorRepo,
                        Clock clock) {
        this.airspaceRepo = airspaceRepo;
        this.routeRepo = routeRepo;
        this.reviewRepo = reviewRepo;
        this.corridorRepo = corridorRepo;
        this.clock = clock;
    }

    /**
     * 执行一次不可变审查并落库（必须在调用方事务内执行）。
     *
     * @param routeId         航线标识
     * @param routeVersion    提交的航线版本
     * @param airspaceVersion 提交的空域版本
     * @param reviewKind      REGULAR / EMERGENCY
     * @param requestId       写操作请求标识
     * @param window          常规审查的时空窗；null 表示不做走廊裁决
     */
    public ReviewPo performReview(String routeId, int routeVersion, long airspaceVersion,
                                  String reviewKind, String requestId, SpaceTimeWindow window) {
        // 先锁空域版本行：与任何区域创建/撤销事务互斥
        long globalVersion = airspaceRepo.getGlobalVersionForUpdate();
        // 再锁航线行：与航线替换事务互斥，保证版本号与点列一致
        RoutePo route = routeRepo.findRouteForUpdate(routeId);
        if (route == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "航线不存在: " + routeId);
        }
        if (route.version() != routeVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "航线版本不是当前版本：submitted=" + routeVersion
                            + ", current=" + route.version());
        }
        if (globalVersion != airspaceVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT",
                    "空域版本不是当前版本：submitted=" + airspaceVersion
                            + ", current=" + globalVersion);
        }
        // 常规审查携带时空窗时：命中有效走廊直接 409 并给出走廊标识
        if (KIND_REGULAR.equals(reviewKind) && window != null) {
            String hitCorridor = findBlockingCorridor(route, window);
            if (hitCorridor != null) {
                throw new ApiException(HttpStatus.CONFLICT, "CORRIDOR_CONFLICT",
                        "常规航线在指定时空窗内命中有效应急走廊: " + hitCorridor);
            }
        }
        // 持锁状态下读取全部有效禁飞区，与上面的版本号同属一个一致状态；
        // 应急审查同样必须通过禁飞审查，不能绕过禁区
        List<ZonePo> activeZones = airspaceRepo.findActiveZones();
        Set<String> hits = new TreeSet<>();
        for (ZonePo zone : activeZones) {
            if (Geometry.polylineHitsRectangle(route.points(),
                    zone.xMin(), zone.yMin(), zone.xMax(), zone.yMax())) {
                hits.add(zone.zoneId());
            }
        }
        String conclusion = hits.isEmpty()
                ? ReviewConclusion.CLEAR.name()
                : ReviewConclusion.BLOCKED.name();
        String reviewId = "rv_" + UUID.randomUUID();
        ReviewPo po = new ReviewPo(reviewId, routeId, route.version(), globalVersion,
                conclusion, new ArrayList<>(hits), List.copyOf(route.points()),
                requestId, clock.instant().toEpochMilli(), reviewKind);
        reviewRepo.insertReview(po);
        return po;
    }

    /**
     * 返回航线在给定时空窗内命中的任一有效应急走廊标识；未命中返回 null。
     * 有效指未撤销且在裁决时刻尚未自然到期；时间与高度均为左闭右开。
     */
    String findBlockingCorridor(RoutePo route, SpaceTimeWindow window) {
        long now = clock.instant().toEpochMilli();
        for (CorridorPo corridor : corridorRepo.findActiveCorridors()) {
            if (corridor.endMs() <= now) {
                // 已自然到期：只影响后续占用，不再阻断
                continue;
            }
            if (Geometry.polylineHitsCorridor(route.points(),
                    corridor.xMin(), corridor.yMin(), corridor.xMax(), corridor.yMax(),
                    window.startMs(), window.endMs(), window.altMin(), window.altMax(),
                    corridor.startMs(), corridor.endMs(), corridor.altMin(), corridor.altMax())) {
                return corridor.corridorId();
            }
        }
        return null;
    }
}
