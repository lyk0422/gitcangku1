package com.example.starter.prediction;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.allocation.AllocationRepository;
import com.example.starter.allocation.SlotAllocation;
import com.example.starter.common.ApiException;
import com.example.starter.common.ErrorReason;
import com.example.starter.prediction.dto.PredictionRequest;
import com.example.starter.station.GroundStation;
import com.example.starter.station.StationRepository;

/**
 * 过境预测服务：版本创建、修订及修订后的分配重新校核。
 */
@Service
public class PredictionService {

    private final PredictionRepository predictionRepository;
    private final AllocationRepository allocationRepository;
    private final StationRepository stationRepository;
    private final Clock clock;

    public PredictionService(PredictionRepository predictionRepository,
            AllocationRepository allocationRepository,
            StationRepository stationRepository,
            Clock clock) {
        this.predictionRepository = predictionRepository;
        this.allocationRepository = allocationRepository;
        this.stationRepository = stationRepository;
        this.clock = clock;
    }

    /**
     * 创建预测首个版本；业务键已存在时拒绝。
     */
    @Transactional
    public PassPrediction create(String predictionKey, PredictionRequest request) {
        validateWindow(request);
        if (predictionRepository.lockSeriesCurrentVersion(predictionKey).isPresent()) {
            throw ApiException.conflict(ErrorReason.INVALID_STATE,
                    "预测业务键已存在，请使用修订接口");
        }
        predictionRepository.insertSeries(predictionKey);
        PassPrediction prediction = new PassPrediction(0L, predictionKey, 1, request.satelliteId(),
                request.windowStartUtc(), request.windowEndUtc(), request.minElevationDeg(),
                request.plannedDataMb(), PassPrediction.STATUS_CURRENT, Instant.now(clock));
        long id = predictionRepository.insert(prediction);
        return predictionRepository.findById(id).orElseThrow();
    }

    /**
     * 修订预测：产生新版本，未开始且不再满足新窗口的分配转为待重新分配，
     * 已完成接收保留旧预测证据不受影响。
     */
    @Transactional
    public PassPrediction revise(String predictionKey, PredictionRequest request) {
        validateWindow(request);
        int currentVersion = predictionRepository.lockSeriesCurrentVersion(predictionKey)
                .orElseThrow(() -> ApiException.notFound("预测业务键不存在"));
        predictionRepository.supersedeCurrent(predictionKey);
        int newVersion = currentVersion + 1;
        predictionRepository.advanceSeries(predictionKey, newVersion);
        PassPrediction prediction = new PassPrediction(0L, predictionKey, newVersion, request.satelliteId(),
                request.windowStartUtc(), request.windowEndUtc(), request.minElevationDeg(),
                request.plannedDataMb(), PassPrediction.STATUS_CURRENT, Instant.now(clock));
        long id = predictionRepository.insert(prediction);
        PassPrediction saved = predictionRepository.findById(id).orElseThrow();
        revalidateAllocations(saved);
        return saved;
    }

    /**
     * 列出某业务键的全部版本。
     */
    @Transactional(readOnly = true)
    public List<PassPrediction> listVersions(String predictionKey) {
        if (!predictionRepository.seriesExists(predictionKey)) {
            throw ApiException.notFound("预测业务键不存在");
        }
        return predictionRepository.listVersions(predictionKey);
    }

    private void revalidateAllocations(PassPrediction current) {
        Instant now = Instant.now(clock);
        for (SlotAllocation allocation
                : allocationRepository.findConfirmedNotStarted(current.predictionKey(), now)) {
            GroundStation station = stationRepository.findById(allocation.stationId()).orElseThrow();
            boolean windowCovers = !allocation.slotStartUtc().isBefore(current.windowStartUtc())
                    && !allocation.slotEndUtc().isAfter(current.windowEndUtc());
            boolean elevationOk = current.minElevationDeg().compareTo(station.minElevationDeg()) >= 0;
            boolean capacityOk = fitsCapacity(allocation, station);
            if (!windowCovers || !elevationOk || !capacityOk) {
                allocationRepository.markPendingReassignment(allocation.id(), now);
            }
        }
    }

    private boolean fitsCapacity(SlotAllocation allocation, GroundStation station) {
        long durationSeconds = allocation.slotEndUtc().getEpochSecond()
                - allocation.slotStartUtc().getEpochSecond();
        return allocation.plannedDataMb() * 60L
                <= (long) station.capacityMbPerMin() * durationSeconds;
    }

    private void validateWindow(PredictionRequest request) {
        if (!request.windowEndUtc().isAfter(request.windowStartUtc())) {
            throw ApiException.unprocessable(ErrorReason.WINDOW_GAP,
                    "窗口终点必须晚于起点");
        }
    }
}