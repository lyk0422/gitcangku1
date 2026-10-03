package com.example.starter.service;

import com.example.starter.model.DeicingCompletion;
import com.example.starter.model.DeicingSlot;
import com.example.starter.model.FluidType;
import com.example.starter.model.SlotStatus;
import com.example.starter.repo.BaseDataRepository;
import com.example.starter.repo.CompletionRepository;
import com.example.starter.repo.SlotRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 除冰时隙服务：申请、完成作业、取消。
 */
@Service
public class DeicingService {

    private final SlotRepository slotRepository;
    private final CompletionRepository completionRepository;
    private final BaseDataRepository baseDataRepository;
    private final Clock clock;

    public DeicingService(SlotRepository slotRepository, CompletionRepository completionRepository,
            BaseDataRepository baseDataRepository, Clock clock) {
        this.slotRepository = slotRepository;
        this.completionRepository = completionRepository;
        this.baseDataRepository = baseDataRepository;
        this.clock = clock;
    }

    /**
     * 申请除冰时隙；requestId 同参重放返回原时隙，异参返回 409。
     * 同一车辆的申请通过车辆行锁串行化，重叠时隙（左闭右开）最多成功一个。
     */
    @Transactional
    public DeicingSlot applySlot(String requestId, long vehicleId, long flightId,
            Instant startUtc, Instant endUtc) {
        if (!endUtc.isAfter(startUtc)) {
            throw new ConflictException("时隙结束必须晚于开始");
        }
        if (!baseDataRepository.vehicleExists(vehicleId)) {
            throw new NotFoundException("除冰车不存在: " + vehicleId);
        }
        if (!baseDataRepository.flightExists(flightId)) {
            throw new NotFoundException("航班不存在: " + flightId);
        }
        baseDataRepository.lockVehicle(vehicleId);
        var existing = slotRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            DeicingSlot slot = existing.get();
            if (slot.vehicleId() == vehicleId && slot.flightId() == flightId
                    && slot.startUtc().equals(startUtc) && slot.endUtc().equals(endUtc)) {
                return slot;
            }
            throw new ConflictException("requestId 已存在但参数不一致: " + requestId);
        }
        if (!slotRepository.findOverlapping(vehicleId, startUtc, endUtc).isEmpty()) {
            throw new ConflictException("车辆时隙重叠: vehicleId=" + vehicleId);
        }
        try {
            return slotRepository.insert(new DeicingSlot(null, requestId, vehicleId, flightId,
                    startUtc, endUtc, SlotStatus.SCHEDULED));
        } catch (DuplicateKeyException e) {
            throw new ConflictException("requestId 已存在: " + requestId);
        }
    }

    /**
     * 完成作业，记录液型与完成时刻，生成截止时刻；与取消竞争只能成功一方。
     */
    @Transactional
    public DeicingCompletion completeSlot(long slotId, FluidType fluidType) {
        DeicingSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("时隙不存在: " + slotId));
        if (slot.status() == SlotStatus.COMPLETED) {
            return completionRepository.findBySlotId(slotId).orElseThrow();
        }
        if (slot.status() == SlotStatus.CANCELLED) {
            throw new ConflictException("时隙已取消，不能完成作业: " + slotId);
        }
        if (slotRepository.transitionStatus(slotId, SlotStatus.SCHEDULED, SlotStatus.COMPLETED) == 0) {
            throw new ConflictException("时隙状态已变更，作业完成失败: " + slotId);
        }
        Instant completedAt = clock.instant();
        Instant deadlineAt = completedAt.plusSeconds(fluidType.holdoverMinutes() * 60L);
        return completionRepository.insert(
                new DeicingCompletion(null, slotId, fluidType, completedAt, deadlineAt));
    }

    /**
     * 取消未开始时隙并释放车辆；已完成作业不能取消。
     */
    @Transactional
    public DeicingSlot cancelSlot(long slotId) {
        DeicingSlot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new NotFoundException("时隙不存在: " + slotId));
        if (slot.status() == SlotStatus.CANCELLED) {
            return slot;
        }
        if (slot.status() == SlotStatus.COMPLETED) {
            throw new ConflictException("已完成的作业不能取消: " + slotId);
        }
        if (slotRepository.transitionStatus(slotId, SlotStatus.SCHEDULED, SlotStatus.CANCELLED) == 0) {
            throw new ConflictException("时隙状态已变更，取消失败: " + slotId);
        }
        return slotRepository.findById(slotId).orElseThrow();
    }
}