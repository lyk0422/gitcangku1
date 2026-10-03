package com.example.starter.service;

import com.example.starter.model.GroundStation;
import com.example.starter.model.PassPrediction;
import com.example.starter.model.SlotAllocation;
import com.example.starter.repo.GroundStationRepository;
import com.example.starter.repo.PassPredictionRepository;
import com.example.starter.repo.SlotAllocationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 卫星过境预测服务：发布新版本并在修订时重新校核未开始的分配。
 */
@Service
public class PredictionService {

    private final PassPredictionRepository predictionRepository;
    private final SlotAllocationRepository allocationRepository;
    private final GroundStationRepository stationRepository;
    private final Clock clock;

    public PredictionService(PassPredictionRepository predictionRepository,
            SlotAllocationRepository allocationRepository,
            GroundStationRepository stationRepository, Clock businessClock) {
        this.predictionRepository = predictionRepository;
        this.allocationRepository = allocationRepository;
        this.stationRepository = stationRepository;
        this.clock = businessClock;
    }

    /**
     * 发布某卫星的新版本预测（首次发布为版本 1，再次发布即修订）。
     * 修订后，未开始的已确认分配按新窗口重新校核，不满足者转为待重新分配；
     * 已完成接收的记录保留旧预测证据，不做改动。
     */
    @Transactional
    public PassPrediction publish(String satelliteCode, Instant windowStartUtc, Instant windowEndUtc,
            BigDecimal minElevationDeg, long plannedBytes) {
        if (!windowEndUtc.isAfter(windowStartUtc)) {
            throw BusinessException.conflict(com.example.starter.model.RejectReason.WINDOW_GAP,
                    "可见窗口结束必须晚于开始");
        }
        PassPrediction previous = predictionRepository.lockLatest(satelliteCode).orElse(null);
        int nextVersion = previous == null ? 1 : previous.version() + 1;
        if (previous != null) {
            predictionRepository.markSuperseded(previous.id());
        }
        PassPrediction created = new PassPrediction(0L, satelliteCode, nextVersion,
                windowStartUtc, windowEndUtc, minElevationDeg, plannedBytes, false, Instant.now(clock));
        long id = predictionRepository.insert(created);
        PassPrediction saved = predictionRepository.findById(id).orElseThrow();
        if (previous != null) {
            recheckAllocations(saved);
        }
        return saved;
    }

    /** 查询卫星全部预测版本。 */
    @Transactional(readOnly = true)
    public List<PassPrediction> versions(String satelliteCode) {
        return predictionRepository.findVersions(satelliteCode);
    }

    /**
     * 用新版本预测重新校核该卫星未开始的已确认分配。
     */
    private void recheckAllocations(PassPrediction latest) {
        Instant now = Instant.now(clock);
        for (SlotAllocation allocation
                : allocationRepository.findConfirmedNotStarted(latest.satelliteCode(), now)) {
            if (!fits(latest, allocation)) {
                allocationRepository.markPendingReassign(allocation.id(), now);
            }
        }
    }

    /**
     * 校核已确认分配在新预测下是否仍然成立：
     * 时隙完整落在窗口内、容量快照可容纳计划量快照、新最低仰角达到站点门槛。
     */
    private boolean fits(PassPrediction latest, SlotAllocation allocation) {
        if (allocation.slotStartUtc().isBefore(latest.windowStartUtc())
                || allocation.slotEndUtc().isAfter(latest.windowEndUtc())) {
            return false;
        }
        long minutes = Duration.between(allocation.slotStartUtc(), allocation.slotEndUtc()).toMinutes();
        if (minutes * allocation.capacityBytesPerMin() < allocation.plannedBytes()) {
            return false;
        }
        GroundStation station = stationRepository.findById(allocation.stationId()).orElseThrow();
        return latest.minElevationDeg().compareTo(station.minElevationDeg()) >= 0;
    }
}