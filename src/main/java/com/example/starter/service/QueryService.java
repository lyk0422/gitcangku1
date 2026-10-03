package com.example.starter.service;

import com.example.starter.model.TakeoffClearance;
import com.example.starter.model.TakeoffRejection;
import com.example.starter.repo.ClearanceRepository;
import com.example.starter.repo.CompletionRepository;
import com.example.starter.repo.RejectionRepository;
import com.example.starter.repo.SlotRepository;
import com.example.starter.web.dto.VehicleTimelineEntry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询服务：车辆时间轴与航班放行记录。
 */
@Service
public class QueryService {

    private final SlotRepository slotRepository;
    private final CompletionRepository completionRepository;
    private final ClearanceRepository clearanceRepository;
    private final RejectionRepository rejectionRepository;

    public QueryService(SlotRepository slotRepository, CompletionRepository completionRepository,
            ClearanceRepository clearanceRepository, RejectionRepository rejectionRepository) {
        this.slotRepository = slotRepository;
        this.completionRepository = completionRepository;
        this.clearanceRepository = clearanceRepository;
        this.rejectionRepository = rejectionRepository;
    }

    /**
     * 车辆时间轴：按时隙开始升序，含液型与截止时刻。
     */
    @Transactional(readOnly = true)
    public List<VehicleTimelineEntry> vehicleTimeline(long vehicleId) {
        return slotRepository.findByVehicleId(vehicleId).stream()
                .map(slot -> {
                    var completion = completionRepository.findBySlotId(slot.id());
                    return new VehicleTimelineEntry(slot.id(), slot.flightId(), slot.startUtc(),
                            slot.endUtc(), slot.status(),
                            completion.map(c -> c.fluidType()).orElse(null),
                            completion.map(c -> c.completedAt()).orElse(null),
                            completion.map(c -> c.deadlineAt()).orElse(null));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<TakeoffClearance> flightClearances(long flightId) {
        return clearanceRepository.findByFlightId(flightId);
    }

    @Transactional(readOnly = true)
    public List<TakeoffRejection> flightRejections(long flightId) {
        return rejectionRepository.findByFlightId(flightId);
    }
}