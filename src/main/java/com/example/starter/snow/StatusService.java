package com.example.starter.snow;

import com.example.starter.snow.model.ClosureRec;
import com.example.starter.snow.model.ConfirmationRec;
import com.example.starter.snow.model.DecisionRec;
import com.example.starter.snow.model.ReadingRec;
import com.example.starter.snow.model.ReviewRec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 区域状态查询：当前封闭状态、连续整点读数、最近审核的审核人与失效原因、
 * 封闭/解除时间线。
 */
@Service
public class StatusService {

    private static final int RECENT_READING_LIMIT = 12;

    private final RegionDao regionDao;
    private final ReadingDao readingDao;
    private final ReviewDao reviewDao;

    public StatusService(RegionDao regionDao, ReadingDao readingDao, ReviewDao reviewDao) {
        this.regionDao = regionDao;
        this.readingDao = readingDao;
        this.reviewDao = reviewDao;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(String regionId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("regionId", regionId);
        body.put("status", regionDao.currentStatus(regionId));

        List<ReadingRec> recent = readingDao.findRecent(regionId, RECENT_READING_LIMIT);
        List<Map<String, Object>> readings = new ArrayList<>();
        for (ReadingRec r : recent) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("readingId", r.id());
            item.put("hourUtc", r.hourUtc().toString());
            item.put("depthCm", r.depthCm());
            item.put("newSnowCm", r.newSnowCm());
            item.put("overLimit", r.newSnowCm() > SnowpackService.NEW_SNOW_LIMIT_CM);
            item.put("errata", readingDao.findErrataByReadingId(r.id()).size());
            readings.add(item);
        }
        body.put("recentReadings", readings);
        body.put("consecutiveHourCount", countConsecutiveHours(recent));

        Optional<ReviewRec> latest = reviewDao.findLatest(regionId);
        if (latest.isPresent()) {
            ReviewRec r = latest.get();
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("reviewId", r.id());
            review.put("status", r.status());
            review.put("windowStart", r.windowStart().toString());
            review.put("windowEnd", r.windowEnd().toString());
            review.put("reviewers", reviewDao.findConfirmations(r.id()).stream()
                    .map(ConfirmationRec::reviewer).toList());
            if (r.invalidatedReason() != null) {
                review.put("invalidatedReason", r.invalidatedReason());
            }
            body.put("latestReview", review);
        }

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (ClosureRec c : regionDao.findClosures(regionId)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "CLOSED");
            event.put("at", c.createdAt().toString());
            event.put("source", c.source());
            event.put("reason", c.reason());
            timeline.add(event);
        }
        for (DecisionRec d : reviewDao.findDecisions(regionId)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "UNSEALED");
            event.put("at", d.createdAt().toString());
            event.put("version", d.version());
            event.put("reviewId", d.reviewId());
            timeline.add(event);
        }
        timeline.sort(Comparator.comparing(e -> (String) e.get("at")));
        body.put("timeline", timeline);
        return body;
    }

    /**
     * 从最新读数向前统计连续整点数量。
     */
    private int countConsecutiveHours(List<ReadingRec> recentDesc) {
        if (recentDesc.isEmpty()) {
            return 0;
        }
        int count = 1;
        Instant expected = recentDesc.get(0).hourUtc();
        for (int i = 1; i < recentDesc.size(); i++) {
            expected = expected.minusSeconds(3600L);
            if (!recentDesc.get(i).hourUtc().equals(expected)) {
                break;
            }
            count++;
        }
        return count;
    }
}