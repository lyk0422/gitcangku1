package com.example.snowpack.service;

import com.example.snowpack.error.BusinessException;
import com.example.snowpack.repo.ClosureRepository;
import com.example.snowpack.repo.ReadingRepository;
import com.example.snowpack.repo.ReviewRepository;
import com.example.snowpack.repo.Rows.ClosureRow;
import com.example.snowpack.repo.Rows.ErrataRow;
import com.example.snowpack.repo.Rows.ReadingRow;
import com.example.snowpack.repo.Rows.ZoneStateRow;
import com.example.snowpack.repo.ZoneRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 雪况读数上报、勘误追加与封闭命令。所有写操作在区域行锁内串行执行。 */
@Service
public class ReadingService {

    /** 新增积雪超限阈值（厘米），超过即触发封闭并使未完成审核失效。 */
    public static final int NEW_SNOW_LIMIT_CM = 5;

    private final ZoneRepository zones;
    private final ReadingRepository readings;
    private final ClosureRepository closures;
    private final ReviewRepository reviews;
    private final Clock clock;

    public ReadingService(ZoneRepository zones, ReadingRepository readings, ClosureRepository closures,
                          ReviewRepository reviews, Clock clock) {
        this.zones = zones;
        this.readings = readings;
        this.closures = closures;
        this.reviews = reviews;
        this.clock = clock;
    }

    /** 读数上报结果。 */
    public record ReadingResult(long id, String zoneId, Instant hourUtc, int snowDepthCm, int newSnowCm,
                                boolean overLimit, boolean replayed, boolean closureCreated,
                                int reviewsInvalidated) {
    }

    /** 封闭命令结果。 */
    public record ClosureResult(long id, String zoneId, String source, String reason, String status,
                                Instant createdAt, boolean replayed) {
    }

    /** 勘误结果。 */
    public record ErrataResult(long id, long readingId, String requestId, String note,
                               String reviewResult, Instant createdAt, boolean replayed) {
    }

    /**
     * 上报整点雪况读数。幂等：requestId 同参重放返回原记录，异参返回 409。
     * 超限读数自动生成封闭命令（若区域未封闭）并使未完成的双人审核失效。
     */
    @Transactional
    public ReadingResult postReading(String zoneId, String requestId, Instant hourUtc,
                                     int snowDepthCm, int newSnowCm) {
        if (hourUtc.getEpochSecond() % 3600 != 0) {
            throw BusinessException.unprocessable("NON_HOURLY_TIMESTAMP",
                    "hourUtc must be a whole-hour UTC instant");
        }
        Instant now = clock.instant();
        zones.ensureZone(zoneId, now);
        ZoneStateRow zone = zones.lockById(zoneId).orElseThrow();

        var existing = readings.findByRequestId(zoneId, requestId);
        if (existing.isPresent()) {
            ReadingRow row = existing.get();
            if (row.hourUtc().equals(hourUtc) && row.snowDepthCm() == snowDepthCm
                    && row.newSnowCm() == newSnowCm) {
                return new ReadingResult(row.id(), zoneId, row.hourUtc(), row.snowDepthCm(),
                        row.newSnowCm(), row.newSnowCm() > NEW_SNOW_LIMIT_CM, true, false, 0);
            }
            throw BusinessException.conflict("REQUEST_ID_CONFLICT",
                    "requestId already used with different parameters");
        }
        if (readings.findByHour(zoneId, hourUtc).isPresent()) {
            throw BusinessException.conflict("DUPLICATE_HOUR_READING",
                    "reading for this hour already exists; append errata instead");
        }

        long id = readings.insert(zoneId, hourUtc, snowDepthCm, newSnowCm, requestId, now);
        zones.bumpReadingVersion(zoneId, now);

        boolean overLimit = newSnowCm > NEW_SNOW_LIMIT_CM;
        boolean closureCreated = false;
        int invalidated = 0;
        if (overLimit) {
            invalidated = reviews.invalidateInProgress(zoneId, ReviewRepository.REASON_OVER_LIMIT_READING);
            if (!ZoneRepository.STATUS_CLOSED.equals(zone.status())) {
                closures.insert(zoneId, null, ClosureRepository.SOURCE_AUTO,
                        "new snow " + newSnowCm + "cm over limit " + NEW_SNOW_LIMIT_CM + "cm", now);
                zones.updateStatus(zoneId, ZoneRepository.STATUS_CLOSED, now);
                closureCreated = true;
            }
        }
        return new ReadingResult(id, zoneId, hourUtc, snowDepthCm, newSnowCm, overLimit, false,
                closureCreated, invalidated);
    }

    /** 人工下发封闭命令。幂等：requestId 同参重放返回原命令，异参 409。 */
    @Transactional
    public ClosureResult postClosure(String zoneId, String requestId, String reason) {
        Instant now = clock.instant();
        zones.ensureZone(zoneId, now);
        ZoneStateRow zone = zones.lockById(zoneId).orElseThrow();

        var existing = closures.findByRequestId(zoneId, requestId);
        if (existing.isPresent()) {
            ClosureRow row = existing.get();
            if (row.reason().equals(reason)) {
                return new ClosureResult(row.id(), zoneId, row.source(), row.reason(), row.status(),
                        row.createdAt(), true);
            }
            throw BusinessException.conflict("REQUEST_ID_CONFLICT",
                    "requestId already used with different parameters");
        }
        if (ZoneRepository.STATUS_CLOSED.equals(zone.status())) {
            throw BusinessException.conflict("ZONE_ALREADY_CLOSED", "zone is already closed");
        }
        long id = closures.insert(zoneId, requestId, ClosureRepository.SOURCE_MANUAL, reason, now);
        zones.updateStatus(zoneId, ZoneRepository.STATUS_CLOSED, now);
        return new ClosureResult(id, zoneId, ClosureRepository.SOURCE_MANUAL, reason,
                ClosureRepository.STATUS_ACTIVE, now, false);
    }

    /** 为旧读数追加勘误与复核结果；原读数不可修改。幂等：requestId 重放返回原勘误。 */
    @Transactional
    public ErrataResult postErrata(String zoneId, long readingId, String requestId, String note,
                                   String reviewResult) {
        Instant now = clock.instant();
        zones.ensureZone(zoneId, now);
        zones.lockById(zoneId).orElseThrow();

        ReadingRow reading = readings.findById(zoneId, readingId)
                .orElseThrow(() -> BusinessException.notFound("READING_NOT_FOUND",
                        "reading not found in zone"));
        var existing = readings.findErrataByRequestId(reading.id(), requestId);
        if (existing.isPresent()) {
            ErrataRow row = existing.get();
            if (row.note().equals(note) && java.util.Objects.equals(row.reviewResult(), reviewResult)) {
                return new ErrataResult(row.id(), row.readingId(), row.requestId(), row.note(),
                        row.reviewResult(), row.createdAt(), true);
            }
            throw BusinessException.conflict("REQUEST_ID_CONFLICT",
                    "requestId already used with different parameters");
        }
        long id = readings.insertErrata(reading.id(), requestId, note, reviewResult, now);
        return new ErrataResult(id, reading.id(), requestId, note, reviewResult, now, false);
    }
}