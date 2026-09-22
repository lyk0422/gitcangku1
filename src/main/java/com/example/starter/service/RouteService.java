package com.example.starter.service;

import com.example.starter.api.dto.CreateRouteRequest;
import com.example.starter.api.dto.PointDto;
import com.example.starter.api.dto.ReplaceRouteRequest;
import com.example.starter.api.dto.RouteResponse;
import com.example.starter.dao.RouteDao;
import com.example.starter.domain.Point;
import com.example.starter.domain.RouteRecord;
import com.example.starter.error.BadRequestException;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 航线服务：创建与整体替换点列；替换需携带期望版本，成功后版本加一并使当前审核失效。
 */
@Service
public class RouteService {

    private final RouteDao routeDao;
    private final IdempotentExecutor idempotentExecutor;
    private final Clock clock;

    public RouteService(RouteDao routeDao, IdempotentExecutor idempotentExecutor, Clock clock) {
        this.routeDao = routeDao;
        this.idempotentExecutor = idempotentExecutor;
        this.clock = clock;
    }

    /**
     * 创建航线：routeId 唯一，初始版本为 1。
     */
    public RouteResponse create(CreateRouteRequest request) {
        List<Point> points = validateAndConvert(request.points());
        String fingerprint = "CREATE_ROUTE|" + request.routeId() + "|" + pointsKey(points);
        try {
            return idempotentExecutor.execute(request.requestId(), "CREATE_ROUTE", fingerprint,
                    RouteResponse.class, () -> {
                        if (routeDao.findById(request.routeId()).isPresent()) {
                            throw new ConflictException("ROUTE_EXISTS",
                                    "航线标识已存在：" + request.routeId());
                        }
                        routeDao.insert(request.routeId(), points, Instant.now(clock));
                        return new RouteResponse(request.routeId(), 1);
                    });
        } catch (DuplicateKeyException e) {
            // 并发创建同一 routeId：主键冲突，后提交者按冲突处理。
            throw new ConflictException("ROUTE_EXISTS", "航线标识已存在：" + request.routeId());
        }
    }

    /**
     * 替换航线点列：expectedVersion 必须等于当前版本，成功后版本加一。
     */
    public RouteResponse replace(String routeId, ReplaceRouteRequest request) {
        List<Point> points = validateAndConvert(request.points());
        String fingerprint = "REPLACE_ROUTE|" + routeId + "|" + request.expectedVersion() + "|"
                + pointsKey(points);
        return idempotentExecutor.execute(request.requestId(), "REPLACE_ROUTE", fingerprint,
                RouteResponse.class, () -> {
                    RouteRecord route = routeDao.lockById(routeId)
                            .orElseThrow(() -> new NotFoundException("ROUTE_NOT_FOUND",
                                    "航线不存在：" + routeId));
                    if (route.version() != request.expectedVersion()) {
                        throw new ConflictException("ROUTE_VERSION_MISMATCH",
                                "航线版本不匹配：期望 " + request.expectedVersion()
                                        + "，当前 " + route.version());
                    }
                    routeDao.replacePoints(routeId, points, Instant.now(clock));
                    return new RouteResponse(routeId, route.version() + 1);
                });
    }

    /**
     * 校验点列并转换为领域点：2~50 个点，坐标在闭区间 [-100000,100000]，至少两个点不同。
     */
    private List<Point> validateAndConvert(List<PointDto> dtos) {
        if (dtos == null || dtos.size() < 2 || dtos.size() > 50) {
            throw new BadRequestException("ROUTE_POINTS_SIZE", "航线点列长度须在 2~50 之间");
        }
        List<Point> points = dtos.stream()
                .map(dto -> {
                    if (dto == null || dto.x() == null || dto.y() == null
                            || !Point.inRange(dto.x()) || !Point.inRange(dto.y())) {
                        throw new BadRequestException("POINT_OUT_OF_RANGE",
                                "航线点坐标须在 [-100000,100000] 内");
                    }
                    return new Point(dto.x(), dto.y());
                })
                .toList();
        if (points.stream().distinct().count() < 2) {
            throw new BadRequestException("ROUTE_POINTS_IDENTICAL", "航线至少包含两个不同的点");
        }
        return points;
    }

    private String pointsKey(List<Point> points) {
        return points.stream()
                .map(p -> p.x() + "," + p.y())
                .collect(Collectors.joining(";"));
    }
}
