package com.example.starter.plan.service;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.repo.PlanRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 车底交路链校验：同一车底同一运营日的已发布计划按始发时刻排序构成交路链，
 * 相邻两段要求前段终到站等于后段始发站，且后段始发时刻不早于前段终到时刻加最小周转分钟数。
 * 段的始发/终到时刻由计划占用推导：始发 = 最早占用开始，终到 = 最晚占用结束（UTC）。
 */
@Component
public class ChainValidator {

    /**
     * 交路链中的一段。
     *
     * @param planId        计划 id
     * @param scheduleKey   计划业务键
     * @param originStation 始发站
     * @param destStation   终到站
     * @param departureUtc  始发时刻（最早占用开始），UTC
     * @param arrivalUtc    终到时刻（最晚占用结束），UTC
     */
    public record ChainSegment(long planId, String scheduleKey, String originStation,
                               String destStation, Instant departureUtc, Instant arrivalUtc) {
    }

    private final PlanRepository planRepo;

    public ChainValidator(PlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    /**
     * 由计划及其占用推导链段（占用须已持久化）。
     */
    public ChainSegment segmentOf(DayPlan plan) {
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        if (occupancies.isEmpty()) {
            throw new IllegalStateException("计划无占用，无法推导首末时刻: " + plan.scheduleKey());
        }
        Instant departure = occupancies.stream().map(Occupancy::startUtc).min(Instant::compareTo)
                .orElseThrow();
        Instant arrival = occupancies.stream().map(Occupancy::endUtc).max(Instant::compareTo)
                .orElseThrow();
        return new ChainSegment(plan.id(), plan.scheduleKey(), plan.originStation(),
                plan.destStation(), departure, arrival);
    }

    /**
     * 加载指定车底、指定运营日全部已发布计划的链段，按始发时刻（再按计划 id）升序。
     */
    public List<ChainSegment> loadPublishedSegments(String stockKey, LocalDate opDate) {
        return planRepo.findPublishedByStock(stockKey, opDate).stream()
                .map(this::segmentOf)
                .sorted(Comparator.comparing(ChainSegment::departureUtc)
                        .thenComparing(ChainSegment::planId))
                .toList();
    }

    /**
     * 校验排序后的链段相邻衔接，返回全部违规明细（空列表表示链连续）。
     * 每条明细携带断点前后段、实际间隔分钟数与要求的最小周转分钟数。
     */
    public List<Map<String, Object>> validate(List<ChainSegment> sortedSegments,
                                              int minTurnaroundMinutes) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (int i = 1; i < sortedSegments.size(); i++) {
            ChainSegment prev = sortedSegments.get(i - 1);
            ChainSegment cur = sortedSegments.get(i);
            long actualGap = Duration.between(prev.arrivalUtc(), cur.departureUtc()).toMinutes();
            boolean stationConnected = prev.destStation().equals(cur.originStation());
            if (!stationConnected) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "LINK_BROKEN");
                detail.put("prevScheduleKey", prev.scheduleKey());
                detail.put("nextScheduleKey", cur.scheduleKey());
                detail.put("prevDestStation", prev.destStation());
                detail.put("nextOriginStation", cur.originStation());
                detail.put("actualGapMinutes", actualGap);
                detail.put("requiredMinutes", minTurnaroundMinutes);
                violations.add(detail);
            } else if (actualGap < minTurnaroundMinutes) {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "TURNAROUND_INSUFFICIENT");
                detail.put("prevScheduleKey", prev.scheduleKey());
                detail.put("nextScheduleKey", cur.scheduleKey());
                detail.put("actualGapMinutes", actualGap);
                detail.put("requiredMinutes", minTurnaroundMinutes);
                violations.add(detail);
            }
        }
        return violations;
    }
}
