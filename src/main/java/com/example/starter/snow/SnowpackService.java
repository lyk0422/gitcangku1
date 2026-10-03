package com.example.starter.snow;

import com.example.starter.snow.dto.ClosureRequest;
import com.example.starter.snow.dto.ErrataRequest;
import com.example.starter.snow.dto.ReadingRequest;
import com.example.starter.snow.model.ClosureRec;
import com.example.starter.snow.model.ErrataRec;
import com.example.starter.snow.model.ReadingRec;
import com.example.starter.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 雪况上报、封闭命令与读数勘误的业务逻辑。
 * 所有写操作在事务内先锁定控制区状态行，保证与解除审核并发时的一致性。
 */
@Service
public class SnowpackService {

    /** 新增积雪超限阈值（厘米）：大于该值视为超限。 */
    public static final int NEW_SNOW_LIMIT_CM = 5;

    private final RegionDao regionDao;
    private final ReadingDao readingDao;
    private final ReviewDao reviewDao;
    private final Clock clock;

    public SnowpackService(RegionDao regionDao, ReadingDao readingDao, ReviewDao reviewDao, Clock clock) {
        this.regionDao = regionDao;
        this.readingDao = readingDao;
        this.reviewDao = reviewDao;
        this.clock = clock;
    }

    /**
     * 上报某一 UTC 整点的合成雪况读数；幂等键同参重放返回原结果，异参返回 409。
     * 超限读数会使未完成审核失效，并在区域开放时自动生成封闭命令。
     */
    @Transactional
    public Map<String, Object> reportReading(String regionId, ReadingRequest req) {
        Instant hour = parseHour(req.hourUtc());
        Instant now = clock.instant();
        regionDao.ensureRegion(regionId, now);
        String status = regionDao.lockRegionStatus(regionId);

        Optional<ReadingRec> existing = readingDao.findByRequestId(regionId, req.requestId());
        if (existing.isPresent()) {
            ReadingRec r = existing.get();
            if (r.hourUtc().equals(hour) && r.depthCm() == req.depthCm()
                    && r.newSnowCm() == req.newSnowCm()) {
                return readingView(r, r.newSnowCm() > NEW_SNOW_LIMIT_CM, null, 0, status);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已存在但参数不同: " + req.requestId());
        }
        if (readingDao.findByHour(regionId, hour).isPresent()) {
            throw ApiException.conflict("READING_ALREADY_EXISTS",
                    "该整点已有读数，旧读数仅可追加勘误: " + hour);
        }

        long id = readingDao.insertReading(regionId, hour, req.depthCm(), req.newSnowCm(),
                req.requestId(), now);
        ReadingRec saved = readingDao.findById(id).orElseThrow();

        boolean overLimit = req.newSnowCm() > NEW_SNOW_LIMIT_CM;
        int invalidated = 0;
        Long closureId = null;
        String newStatus = status;
        if (overLimit) {
            invalidated = reviewDao.invalidatePending(regionId,
                    "OVER_LIMIT_READING at " + hour, now);
            if (RegionDao.OPEN.equals(status)) {
                closureId = regionDao.insertClosure(regionId, ClosureRec.AUTO,
                        "new snow " + req.newSnowCm() + "cm exceeds limit "
                                + NEW_SNOW_LIMIT_CM + "cm at " + hour, null, now);
                regionDao.updateStatus(regionId, RegionDao.CLOSED, now);
                newStatus = RegionDao.CLOSED;
            }
        }
        return readingView(saved, overLimit, closureId, invalidated, newStatus);
    }

    /**
     * 人工下发封闭命令；区域已封闭时返回 409。
     */
    @Transactional
    public Map<String, Object> createClosure(String regionId, ClosureRequest req) {
        Instant now = clock.instant();
        regionDao.ensureRegion(regionId, now);
        String status = regionDao.lockRegionStatus(regionId);

        Optional<ClosureRec> existing = regionDao.findClosureByRequestId(req.requestId());
        if (existing.isPresent()) {
            ClosureRec c = existing.get();
            if (c.regionId().equals(regionId) && c.reason().equals(req.reason())) {
                return closureView(c, status);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已存在但参数不同: " + req.requestId());
        }
        if (RegionDao.CLOSED.equals(status)) {
            throw ApiException.conflict("REGION_ALREADY_CLOSED", "区域已处于封闭状态: " + regionId);
        }
        long id = regionDao.insertClosure(regionId, ClosureRec.MANUAL, req.reason(), req.requestId(), now);
        regionDao.updateStatus(regionId, RegionDao.CLOSED, now);
        return closureView(regionDao.findClosures(regionId).stream()
                .filter(c -> c.id() == id).findFirst().orElseThrow(), RegionDao.CLOSED);
    }

    /**
     * 对旧读数追加勘误与复核结论；不修改原读数。
     */
    @Transactional
    public Map<String, Object> appendErrata(long readingId, ErrataRequest req) {
        Optional<ErrataRec> existing = readingDao.findErrataByRequestId(req.requestId());
        if (existing.isPresent()) {
            ErrataRec e = existing.get();
            if (e.readingId() == readingId && e.reviewer().equals(req.reviewer())
                    && e.note().equals(req.note())) {
                return errataView(e);
            }
            throw ApiException.conflict("REQUEST_ID_CONFLICT",
                    "requestId 已存在但参数不同: " + req.requestId());
        }
        readingDao.findById(readingId)
                .orElseThrow(() -> ApiException.notFound("READING_NOT_FOUND", "读数不存在: " + readingId));
        long id = readingDao.insertErrata(readingId, req.reviewer(), req.note(), req.requestId(),
                clock.instant());
        ErrataRec saved = readingDao.findErrataByRequestId(req.requestId()).orElseThrow();
        return errataView(saved);
    }

    static Instant parseHour(String hourUtc) {
        final Instant hour;
        try {
            hour = Instant.parse(hourUtc);
        } catch (Exception ex) {
            throw ApiException.unprocessable("INVALID_HOUR",
                    "hourUtc 必须是 ISO-8601 UTC 时间: " + hourUtc);
        }
        if (hour.getEpochSecond() % 3600 != 0) {
            throw ApiException.unprocessable("INVALID_HOUR",
                    "hourUtc 必须是整点（分钟秒为 0）: " + hourUtc);
        }
        return hour;
    }

    static Instant currentHour(Clock clock) {
        return clock.instant().truncatedTo(ChronoUnit.HOURS);
    }

    private Map<String, Object> readingView(ReadingRec r, boolean overLimit, Long closureId,
                                            int invalidated, String regionStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("readingId", r.id());
        body.put("regionId", r.regionId());
        body.put("hourUtc", r.hourUtc().toString());
        body.put("depthCm", r.depthCm());
        body.put("newSnowCm", r.newSnowCm());
        body.put("overLimit", overLimit);
        body.put("invalidatedReviews", invalidated);
        body.put("regionStatus", regionStatus);
        if (closureId != null) {
            body.put("closureCommandId", closureId);
        }
        return body;
    }

    private Map<String, Object> closureView(ClosureRec c, String regionStatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("closureCommandId", c.id());
        body.put("regionId", c.regionId());
        body.put("source", c.source());
        body.put("reason", c.reason());
        body.put("createdAt", c.createdAt().toString());
        body.put("regionStatus", regionStatus);
        return body;
    }

    private Map<String, Object> errataView(ErrataRec e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errataId", e.id());
        body.put("readingId", e.readingId());
        body.put("reviewer", e.reviewer());
        body.put("note", e.note());
        body.put("createdAt", e.createdAt().toString());
        return body;
    }
}