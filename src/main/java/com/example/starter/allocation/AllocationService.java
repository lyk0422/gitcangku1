package com.example.starter.allocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.allocation.dto.AllocationRequest;
import com.example.starter.allocation.dto.CancelRequest;
import com.example.starter.allocation.dto.CompletionRequest;
import com.example.starter.common.ApiException;
import com.example.starter.common.ErrorReason;
import com.example.starter.prediction.PassPrediction;
import com.example.starter.prediction.PredictionRepository;
import com.example.starter.station.GroundStation;
import com.example.starter.station.StationRepository;

/**
 * 接收时隙分配服务：幂等分配、完成回报、取消与查询。
 */
@Service
public class AllocationService {

    /** 计划数据量单位 MB 与完成回报字节数之间的换算基数。 */
    public static final long BYTES_PER_MB = 1024L * 1024L;

    private final AllocationRepository allocationRepository;
    private final PredictionRepository predictionRepository;
    private final StationRepository stationRepository;
    private final Clock clock;

    public AllocationService(AllocationRepository allocationRepository,
            PredictionRepository predictionRepository,
            StationRepository stationRepository,
            Clock clock) {
        this.allocationRepository = allocationRepository;
        this.predictionRepository = predictionRepository;
        this.stationRepository = stationRepository;
        this.clock = clock;
    }

    /**
     * 申请接收时隙：同 requestId 同参重放返回原记录，异参返回 409；
     * 仰角、窗口、容量、天线占用任一不满足即整体拒绝，不占用部分时隙。
     */
    @Transactional
    public SlotAllocation allocate(AllocationRequest request) {
        String fingerprint = fingerprint(request);
        var existing = allocationRepository.findByRequestId(request.requestId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), fingerprint);
        }
        if (!request.slotEndUtc().isAfter(request.slotStartUtc())) {
            throw ApiException.unprocessable(ErrorReason.WINDOW_GAP, "时隙终点必须晚于起点");
        }
        predictionRepository.lockSeriesCurrentVersion(request.predictionKey())
                .orElseThrow(() -> ApiException.notFound("预测业务键不存在"));
        // 序列行锁已串行化并发申请，此处再次核对幂等键，拦截同键并发重放
        var raced = allocationRepository.findByRequestId(request.requestId());
        if (raced.isPresent()) {
            return replayOrConflict(raced.get(), fingerprint);
        }
        PassPrediction prediction = predictionRepository.findCurrent(request.predictionKey()).orElseThrow();
        if (request.expectedVersion() != null && request.expectedVersion() != prediction.version()) {
            throw ApiException.conflict(ErrorReason.STALE_PREDICTION,
                    "预测已修订到版本 " + prediction.version() + "，不能凭过期版本锁定天线");
        }
        GroundStation station = stationRepository.findByCode(request.stationCode())
                .orElseThrow(() -> ApiException.notFound("站点不存在"));
        station = stationRepository.findByIdForUpdate(station.id()).orElseThrow();
        if (request.slotStartUtc().isBefore(prediction.windowStartUtc())
                || request.slotEndUtc().isAfter(prediction.windowEndUtc())) {
            throw ApiException.conflict(ErrorReason.WINDOW_GAP, "申请时隙超出预测可见窗口");
        }
        if (prediction.minElevationDeg().compareTo(station.minElevationDeg()) < 0) {
            throw ApiException.conflict(ErrorReason.ELEVATION_BELOW_THRESHOLD, "预测最低仰角低于站点门槛");
        }
        long durationSeconds = request.slotEndUtc().getEpochSecond()
                - request.slotStartUtc().getEpochSecond();
        if (prediction.plannedDataMb() * 60L > (long) station.capacityMbPerMin() * durationSeconds) {
            throw ApiException.conflict(ErrorReason.CAPACITY_EXCEEDED, "时隙内容量容纳不下计划数据量");
        }
        if (!allocationRepository.findOccupyingOverlap(station.id(),
                request.slotStartUtc(), request.slotEndUtc()).isEmpty()) {
            throw ApiException.conflict(ErrorReason.ANTENNA_OVERLAP, "同站天线在该时段已被占用");
        }
        Instant now = Instant.now(clock);
        SlotAllocation allocation = new SlotAllocation(0L, request.requestId(), fingerprint,
                prediction.id(), prediction.predictionKey(), prediction.version(), station.id(),
                request.slotStartUtc(), request.slotEndUtc(), station.capacityMbPerMin(),
                prediction.plannedDataMb(), AllocationStatus.CONFIRMED, null, null, null, now, now);
        try {
            long id = allocationRepository.insert(allocation);
            return allocationRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            return replayOrConflict(allocationRepository.findByRequestId(request.requestId())
                    .orElseThrow(() -> e), fingerprint);
        }
    }

    /**
     * 完成回报：仅已确认记录可完成；实际字节数不得超过计划量；
     * 与取消并发时由状态守卫保证只有一个终态。
     */
    @Transactional
    public SlotAllocation complete(long id, CompletionRequest request) {
        SlotAllocation allocation = allocationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("分配记录不存在"));
        if (allocation.status() == AllocationStatus.COMPLETED) {
            return replayCompletionOrConflict(allocation, request);
        }
        if (request.actualBytes() > allocation.plannedDataMb() * BYTES_PER_MB) {
            throw ApiException.unprocessable(ErrorReason.ACTUAL_EXCEEDS_PLANNED,
                    "实际字节数超过计划数据量");
        }
        int updated = allocationRepository.completeIfConfirmed(id, request.requestId(),
                request.actualBytes(), Instant.now(clock));
        if (updated == 1) {
            return allocationRepository.findById(id).orElseThrow();
        }
        SlotAllocation reloaded = allocationRepository.findById(id).orElseThrow();
        if (reloaded.status() == AllocationStatus.COMPLETED) {
            return replayCompletionOrConflict(reloaded, request);
        }
        throw ApiException.conflict(ErrorReason.INVALID_STATE,
                "记录当前状态为 " + reloaded.status() + "，无法完成");
    }

    /**
     * 取消分配：已确认或待重新分配可取消并释放天线；已完成记录不可取消。
     */
    @Transactional
    public SlotAllocation cancel(long id, CancelRequest request) {
        SlotAllocation allocation = allocationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("分配记录不存在"));
        if (allocation.status() == AllocationStatus.CANCELLED) {
            return allocation;
        }
        int updated = allocationRepository.cancelIfActive(id, request.reason(), Instant.now(clock));
        if (updated == 1) {
            return allocationRepository.findById(id).orElseThrow();
        }
        SlotAllocation reloaded = allocationRepository.findById(id).orElseThrow();
        if (reloaded.status() == AllocationStatus.CANCELLED) {
            return reloaded;
        }
        throw ApiException.conflict(ErrorReason.INVALID_STATE,
                "记录当前状态为 " + reloaded.status() + "，无法取消");
    }

    /**
     * 站点时隙时间线。
     */
    @Transactional(readOnly = true)
    public List<SlotAllocation> timeline(String stationCode, Instant from, Instant to) {
        GroundStation station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> ApiException.notFound("站点不存在"));
        return allocationRepository.findTimeline(station.id(), from, to);
    }

    /**
     * 待重新分配列表。
     */
    @Transactional(readOnly = true)
    public List<SlotAllocation> pendingReassignment() {
        return allocationRepository.findPendingReassignment();
    }

    /**
     * 站点接收实绩。
     */
    @Transactional(readOnly = true)
    public List<SlotAllocation> receptions(String stationCode) {
        GroundStation station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> ApiException.notFound("站点不存在"));
        return allocationRepository.findCompletedByStation(station.id());
    }

    /**
     * 按幂等键查询，用于判断重放。
     */
    @Transactional(readOnly = true)
    public java.util.Optional<SlotAllocation> findByRequestId(String requestId) {
        return allocationRepository.findByRequestId(requestId);
    }

    /**
     * 按主键查询。
     */
    @Transactional(readOnly = true)
    public SlotAllocation findById(long id) {
        return allocationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("分配记录不存在"));
    }

    private SlotAllocation replayOrConflict(SlotAllocation existing, String fingerprint) {
        if (!existing.requestFingerprint().equals(fingerprint)) {
            throw ApiException.conflict(ErrorReason.REQUEST_ID_CONFLICT,
                    "同一 requestId 携带了不同参数");
        }
        return existing;
    }

    private SlotAllocation replayCompletionOrConflict(SlotAllocation completed, CompletionRequest request) {
        if (!request.requestId().equals(completed.completionRequestId())) {
            throw ApiException.conflict(ErrorReason.INVALID_STATE, "记录已由其他请求完成");
        }
        if (request.actualBytes() != completed.actualBytes()) {
            throw ApiException.conflict(ErrorReason.REQUEST_ID_CONFLICT,
                    "同一 requestId 携带了不同参数");
        }
        return completed;
    }

    private String fingerprint(AllocationRequest request) {
        String canonical = String.join("|", request.predictionKey(),
                String.valueOf(request.expectedVersion()), request.stationCode(),
                request.slotStartUtc().toString(), request.slotEndUtc().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}