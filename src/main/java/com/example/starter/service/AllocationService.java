package com.example.starter.service;

import com.example.starter.model.AllocationStatus;
import com.example.starter.model.GroundStation;
import com.example.starter.model.PassPrediction;
import com.example.starter.model.RejectReason;
import com.example.starter.model.SlotAllocation;
import com.example.starter.repo.GroundStationRepository;
import com.example.starter.repo.PassPredictionRepository;
import com.example.starter.repo.SlotAllocationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接收时隙分配服务：确认、取消、完成回报，全部在事务内完成，
 * 失败回滚不留下占用或完成标记。
 */
@Service
public class AllocationService {

    private final SlotAllocationRepository allocationRepository;
    private final PassPredictionRepository predictionRepository;
    private final GroundStationRepository stationRepository;
    private final Clock clock;

    public AllocationService(SlotAllocationRepository allocationRepository,
            PassPredictionRepository predictionRepository,
            GroundStationRepository stationRepository, Clock businessClock) {
        this.allocationRepository = allocationRepository;
        this.predictionRepository = predictionRepository;
        this.stationRepository = stationRepository;
        this.clock = businessClock;
    }

    /**
     * 申请分配接收时隙。同一 requestId 同参重放返回原结果，异参返回 409。
     * 确认时固化预测版本与容量快照；基于过期预测的请求被拒绝。
     */
    @Transactional
    public SlotAllocation allocate(String requestId, String satelliteCode, String stationCode,
            Instant slotStartUtc, Instant slotEndUtc, Integer expectedPredictionVersion) {
        String requestHash = hash("ALLOC", requestId, satelliteCode, stationCode,
                String.valueOf(slotStartUtc), String.valueOf(slotEndUtc));
        var existing = allocationRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            SlotAllocation found = existing.get();
            if (found.requestHash().equals(requestHash)) {
                return found;
            }
            throw BusinessException.conflict(RejectReason.IDEMPOTENCY_CONFLICT,
                    "requestId 已使用且参数不一致");
        }
        if (!slotEndUtc.isAfter(slotStartUtc)) {
            throw BusinessException.conflict(RejectReason.WINDOW_GAP, "时隙结束必须晚于开始");
        }
        PassPrediction prediction = predictionRepository.lockLatest(satelliteCode)
                .orElseThrow(() -> BusinessException.notFound("卫星预测不存在: " + satelliteCode));
        if (prediction.superseded() || (expectedPredictionVersion != null
                && expectedPredictionVersion != prediction.version())) {
            throw BusinessException.conflict(RejectReason.STALE_PREDICTION,
                    "预测已修订，请基于最新版本 " + prediction.version() + " 重新申请");
        }
        GroundStation station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> BusinessException.notFound("站点不存在: " + stationCode));
        // 锁定站点行，串行化同一天线上的确认，防止并发重叠占用。
        stationRepository.lockById(station.id());
        if (slotStartUtc.isBefore(prediction.windowStartUtc())
                || slotEndUtc.isAfter(prediction.windowEndUtc())) {
            throw BusinessException.conflict(RejectReason.WINDOW_GAP,
                    "请求时隙超出预测可见窗口");
        }
        if (prediction.minElevationDeg().compareTo(station.minElevationDeg()) < 0) {
            throw BusinessException.conflict(RejectReason.ELEVATION_BELOW_THRESHOLD,
                    "预测最低仰角低于站点门槛");
        }
        long minutes = Duration.between(slotStartUtc, slotEndUtc).toMinutes();
        if (minutes * station.capacityBytesPerMin() < prediction.plannedBytes()) {
            throw BusinessException.conflict(RejectReason.CAPACITY_EXCEEDED,
                    "时隙容量不足以容纳计划数据量");
        }
        if (allocationRepository.countOverlap(station.id(), slotStartUtc, slotEndUtc) > 0) {
            throw BusinessException.conflict(RejectReason.ANTENNA_OVERLAP,
                    "同一天线在该时段已存在已确认时隙");
        }
        Instant now = Instant.now(clock);
        SlotAllocation allocation = new SlotAllocation(0L, requestId, requestHash,
                prediction.id(), prediction.version(), station.id(), slotStartUtc, slotEndUtc,
                station.capacityBytesPerMin(), prediction.plannedBytes(),
                AllocationStatus.CONFIRMED, null, null, null, null, now, now);
        try {
            long id = allocationRepository.insert(allocation);
            return allocationRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            SlotAllocation found = allocationRepository.findByRequestId(requestId).orElseThrow();
            if (found.requestHash().equals(requestHash)) {
                return found;
            }
            throw BusinessException.conflict(RejectReason.IDEMPOTENCY_CONFLICT,
                    "requestId 已使用且参数不一致");
        }
    }

    /**
     * 取消分配并记录原因，取消后时隙立即释放。
     * 与完成回报并发时，由带状态条件的原子更新保证只有一个终态。
     */
    @Transactional
    public SlotAllocation cancel(long id, String reason) {
        SlotAllocation current = allocationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分配不存在: " + id));
        if (current.status() == AllocationStatus.CANCELLED) {
            return current;
        }
        if (current.status() == AllocationStatus.COMPLETED) {
            throw BusinessException.conflict(RejectReason.INVALID_STATE, "接收已完成，不能取消");
        }
        int updated = allocationRepository.cancelIfActive(id, reason, Instant.now(clock));
        if (updated == 0) {
            throw BusinessException.conflict(RejectReason.INVALID_STATE, "分配状态已变化，取消失败");
        }
        return allocationRepository.findById(id).orElseThrow();
    }

    /**
     * 完成回报：记录实际字节数（不得超过计划量快照）。
     * 同一 requestId 同参重放返回原结果，异参返回 409。
     */
    @Transactional
    public SlotAllocation complete(long id, String requestId, long actualBytes) {
        String completionHash = hash("DONE", String.valueOf(id), String.valueOf(actualBytes));
        SlotAllocation current = allocationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分配不存在: " + id));
        if (current.status() == AllocationStatus.COMPLETED) {
            if (requestId.equals(current.completionRequestId())) {
                if (current.completionRequestHash().equals(completionHash)) {
                    return current;
                }
                throw BusinessException.conflict(RejectReason.IDEMPOTENCY_CONFLICT,
                        "完成回报 requestId 已使用且参数不一致");
            }
            throw BusinessException.conflict(RejectReason.INVALID_STATE, "接收已完成，不能重复回报");
        }
        allocationRepository.findByCompletionRequestId(requestId).ifPresent(other -> {
            throw BusinessException.conflict(RejectReason.IDEMPOTENCY_CONFLICT,
                    "完成回报 requestId 已被其他分配使用");
        });
        if (actualBytes < 0 || actualBytes > current.plannedBytes()) {
            throw BusinessException.conflict(RejectReason.ACTUAL_BYTES_EXCEED_PLANNED,
                    "实际字节数超出计划数据量");
        }
        int updated = allocationRepository.completeIfConfirmed(
                id, actualBytes, requestId, completionHash, Instant.now(clock));
        if (updated == 0) {
            throw BusinessException.conflict(RejectReason.INVALID_STATE,
                    "分配已取消或状态已变化，完成回报失败");
        }
        return allocationRepository.findById(id).orElseThrow();
    }

    /** 计算请求参数指纹（SHA-256 十六进制）。 */
    static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}