package com.example.starter.service;

import com.example.starter.domain.ClearanceStatus;
import com.example.starter.domain.DeicingCompletion;
import com.example.starter.domain.DeicingSlot;
import com.example.starter.domain.DeicingVehicle;
import com.example.starter.domain.Flight;
import com.example.starter.domain.FluidType;
import com.example.starter.domain.TakeoffClearance;
import com.example.starter.repository.ClearanceRepository;
import com.example.starter.repository.CompletionRepository;
import com.example.starter.repository.FlightRepository;
import com.example.starter.repository.SlotRepository;
import com.example.starter.repository.VehicleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 除冰时隙业务服务。
 * 并发约束：同一车辆的申请通过车辆行锁串行化；完成与取消用条件更新保证只形成一个状态；
 * 起飞确认通过完成记录行锁互斥，requestId唯一约束兜底幂等。
 */
@Service
public class DeicingService {

    private final SlotRepository slotRepository;
    private final CompletionRepository completionRepository;
    private final ClearanceRepository clearanceRepository;
    private final FlightRepository flightRepository;
    private final VehicleRepository vehicleRepository;
    private final Clock clock;

    public DeicingService(SlotRepository slotRepository, CompletionRepository completionRepository,
            ClearanceRepository clearanceRepository, FlightRepository flightRepository,
            VehicleRepository vehicleRepository, Clock clock) {
        this.slotRepository = slotRepository;
        this.completionRepository = completionRepository;
        this.clearanceRepository = clearanceRepository;
        this.flightRepository = flightRepository;
        this.vehicleRepository = vehicleRepository;
        this.clock = clock;
    }

    /** 申请除冰时隙：requestId同参重放返回原记录，异参409；车辆时隙重叠409。 */
    @Transactional
    public DeicingSlot applySlot(String requestId, long flightId, long vehicleId, Instant slotStart,
            Instant slotEnd) {
        String hash = slotHash(flightId, vehicleId, slotStart, slotEnd);
        Optional<DeicingSlot> existing = slotRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replaySlot(existing.get(), hash);
        }
        if (!slotStart.isBefore(slotEnd)) {
            throw new BadRequestException("时隙开始必须早于结束（UTC左闭右开）");
        }
        requireFlight(flightId);
        vehicleRepository.lockById(vehicleId)
                .orElseThrow(() -> new NotFoundException("除冰车不存在: " + vehicleId));
        List<DeicingSlot> overlaps = slotRepository.findOverlapping(vehicleId, slotStart, slotEnd);
        if (!overlaps.isEmpty()) {
            throw new ConflictException("除冰车在该时段已有重叠作业: slotId=" + overlaps.get(0).id());
        }
        try {
            long id = slotRepository.insert(vehicleId, flightId, slotStart, slotEnd, requestId, hash,
                    clock.instant());
            return slotRepository.findById(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            // 并发下同requestId已落库，按幂等重放处理
            return replaySlot(slotRepository.findByRequestId(requestId).orElseThrow(), hash);
        }
    }

    /** 完成作业：记录液型与完成时刻（UTC），截止时刻=完成时刻+液型有效期。与取消竞争只形成一个状态。 */
    @Transactional
    public DeicingCompletion completeSlot(long slotId, FluidType fluidType) {
        if (!slotRepository.markCompleted(slotId)) {
            DeicingSlot slot = slotRepository.findById(slotId)
                    .orElseThrow(() -> new NotFoundException("时隙不存在: " + slotId));
            throw new ConflictException("时隙状态不允许完成: " + slot.status());
        }
        Instant now = clock.instant();
        Instant deadline = now.plus(fluidType.validity());
        long id = completionRepository.insert(slotId, fluidType, now, deadline);
        return completionRepository.findBySlotId(slotId).orElseThrow();
    }

    /** 取消未开始时隙并释放车辆；已开始、已完成或已取消的时隙返回409。 */
    @Transactional
    public DeicingSlot cancelSlot(long slotId) {
        if (!slotRepository.markCancelled(slotId, clock.instant())) {
            DeicingSlot slot = slotRepository.findById(slotId)
                    .orElseThrow(() -> new NotFoundException("时隙不存在: " + slotId));
            throw new ConflictException("时隙状态或开始时刻不允许取消: " + slot.status());
        }
        return slotRepository.findById(slotId).orElseThrow();
    }

    /**
     * 起飞确认：须严格早于截止时刻，固化所用完成记录。
     * 逾期或作业缺失写入REJECTED历史；同一完成记录不允许第二次放行。
     */
    @Transactional
    public TakeoffClearance confirmTakeoff(String requestId, long flightId) {
        String hash = takeoffHash(flightId);
        Optional<TakeoffClearance> existing = clearanceRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replayClearance(existing.get(), hash);
        }
        requireFlight(flightId);
        Instant now = clock.instant();
        Optional<DeicingCompletion> completion =
                completionRepository.findLatestByFlightIdForUpdate(flightId);
        if (completion.isEmpty()) {
            return insertClearance(flightId, null, requestId, hash, ClearanceStatus.REJECTED,
                    "除冰作业未完成", now);
        }
        DeicingCompletion latest = completion.get();
        if (clearanceRepository.existsClearedByCompletionId(latest.id())) {
            throw new ConflictException("该完成记录已放行，不允许第二次起飞确认");
        }
        if (!now.isBefore(latest.deadlineAt())) {
            return insertClearance(flightId, latest.id(), requestId, hash, ClearanceStatus.REJECTED,
                    "超过起飞截止时刻", now);
        }
        try {
            return insertClearance(flightId, latest.id(), requestId, hash, ClearanceStatus.CLEARED,
                    null, now);
        } catch (DuplicateKeyException e) {
            return replayClearance(clearanceRepository.findByRequestId(requestId).orElseThrow(), hash);
        }
    }

    /** 车辆时间轴：全部时隙（含已取消）及对应完成记录的液型与截止时刻。 */
    @Transactional(readOnly = true)
    public VehicleTimeline vehicleTimeline(long vehicleId) {
        DeicingVehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("除冰车不存在: " + vehicleId));
        List<TimelineEntry> entries = slotRepository.findByVehicleId(vehicleId).stream()
                .map(slot -> new TimelineEntry(slot,
                        completionRepository.findBySlotId(slot.id()).orElse(null)))
                .toList();
        return new VehicleTimeline(vehicle, entries);
    }

    /** 航班放行记录：放行与拒绝历史按确认时刻升序。 */
    @Transactional(readOnly = true)
    public List<TakeoffClearance> flightClearances(long flightId) {
        requireFlight(flightId);
        return clearanceRepository.findByFlightId(flightId);
    }

    private DeicingSlot replaySlot(DeicingSlot slot, String hash) {
        if (!slot.requestHash().equals(hash)) {
            throw new ConflictException("requestId已使用且参数不一致: " + slot.requestId());
        }
        return slot;
    }

    private TakeoffClearance replayClearance(TakeoffClearance clearance, String hash) {
        if (!clearance.requestHash().equals(hash)) {
            throw new ConflictException("requestId已使用且参数不一致: " + clearance.requestId());
        }
        return clearance;
    }

    private TakeoffClearance insertClearance(long flightId, Long completionId, String requestId,
            String hash, ClearanceStatus status, String reason, Instant now) {
        try {
            long id = clearanceRepository.insert(flightId, completionId, requestId, hash, status,
                    reason, now);
            return clearanceRepository.findByRequestId(requestId).orElseThrow();
        } catch (DuplicateKeyException e) {
            return replayClearance(clearanceRepository.findByRequestId(requestId).orElseThrow(), hash);
        }
    }

    private Flight requireFlight(long flightId) {
        return flightRepository.findById(flightId)
                .orElseThrow(() -> new NotFoundException("航班不存在: " + flightId));
    }

    private static String slotHash(long flightId, long vehicleId, Instant start, Instant end) {
        return flightId + "|" + vehicleId + "|" + start + "|" + end;
    }

    private static String takeoffHash(long flightId) {
        return String.valueOf(flightId);
    }

    /** 车辆时间轴条目：时隙及其完成记录（未完成时completion为空）。 */
    public record TimelineEntry(DeicingSlot slot, DeicingCompletion completion) {
    }

    /** 车辆时间轴视图。 */
    public record VehicleTimeline(DeicingVehicle vehicle, List<TimelineEntry> entries) {
    }
}