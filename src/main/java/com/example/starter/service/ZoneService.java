package com.example.starter.service;

import com.example.starter.api.dto.CreateZoneRequest;
import com.example.starter.api.dto.RevokeZoneRequest;
import com.example.starter.api.dto.ZoneResponse;
import com.example.starter.dao.AirspaceDao;
import com.example.starter.dao.ZoneDao;
import com.example.starter.domain.Zone;
import com.example.starter.error.BadRequestException;
import com.example.starter.error.ConflictException;
import com.example.starter.error.NotFoundException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

/**
 * 禁飞区服务：仅支持创建与撤销；每次变更在全局版本行排他锁内完成并使空域版本加一。
 */
@Service
public class ZoneService {

    private final ZoneDao zoneDao;
    private final AirspaceDao airspaceDao;
    private final IdempotentExecutor idempotentExecutor;
    private final Clock clock;

    public ZoneService(ZoneDao zoneDao, AirspaceDao airspaceDao,
                       IdempotentExecutor idempotentExecutor, Clock clock) {
        this.zoneDao = zoneDao;
        this.airspaceDao = airspaceDao;
        this.idempotentExecutor = idempotentExecutor;
        this.clock = clock;
    }

    /**
     * 创建禁飞区：zoneId 全局唯一（含已撤销），矩形须非退化，成功后空域版本加一。
     */
    public ZoneResponse create(CreateZoneRequest request) {
        if (request.minX() >= request.maxX() || request.minY() >= request.maxY()) {
            throw new BadRequestException("DEGENERATE_ZONE", "禁飞区矩形退化：要求 minX<maxX 且 minY<maxY");
        }
        String fingerprint = "CREATE_ZONE|" + request.zoneId() + "|" + request.minX() + "|"
                + request.minY() + "|" + request.maxX() + "|" + request.maxY();
        return idempotentExecutor.execute(request.requestId(), "CREATE_ZONE", fingerprint,
                ZoneResponse.class, () -> {
                    long version = airspaceDao.lockAndGetVersion();
                    if (zoneDao.findById(request.zoneId()).isPresent()) {
                        throw new ConflictException("ZONE_EXISTS", "禁飞区标识已存在：" + request.zoneId());
                    }
                    Zone zone = new Zone(request.zoneId(), request.minX(), request.minY(),
                            request.maxX(), request.maxY(), true);
                    zoneDao.insert(zone, Instant.now(clock));
                    airspaceDao.incrementVersion();
                    return toResponse(zone, version + 1);
                });
    }

    /**
     * 撤销禁飞区：仅有效禁飞区可撤销，成功后空域版本加一；记录保留用于历史审核解释。
     */
    public ZoneResponse revoke(String zoneId, RevokeZoneRequest request) {
        String fingerprint = "REVOKE_ZONE|" + zoneId;
        return idempotentExecutor.execute(request.requestId(), "REVOKE_ZONE", fingerprint,
                ZoneResponse.class, () -> {
                    long version = airspaceDao.lockAndGetVersion();
                    Zone zone = zoneDao.findById(zoneId)
                            .filter(Zone::active)
                            .orElseThrow(() -> new NotFoundException("ZONE_NOT_FOUND",
                                    "有效禁飞区不存在：" + zoneId));
                    zoneDao.revoke(zoneId, Instant.now(clock));
                    airspaceDao.incrementVersion();
                    return toResponse(new Zone(zone.zoneId(), zone.minX(), zone.minY(),
                            zone.maxX(), zone.maxY(), false), version + 1);
                });
    }

    private ZoneResponse toResponse(Zone zone, long airspaceVersion) {
        return new ZoneResponse(zone.zoneId(), zone.minX(), zone.minY(), zone.maxX(), zone.maxY(),
                zone.active(), airspaceVersion);
    }
}
