package com.example.starter.service;

import com.example.starter.model.AllocationStatus;
import com.example.starter.model.GroundStation;
import com.example.starter.model.SlotAllocation;
import com.example.starter.repo.GroundStationRepository;
import com.example.starter.repo.SlotAllocationRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 查询服务：时隙时间线、待重排列表、分配详情（含接收实绩）。
 */
@Service
public class QueryService {

    private final SlotAllocationRepository allocationRepository;
    private final GroundStationRepository stationRepository;

    public QueryService(SlotAllocationRepository allocationRepository,
            GroundStationRepository stationRepository) {
        this.allocationRepository = allocationRepository;
        this.stationRepository = stationRepository;
    }

    /** 查询站点在指定 UTC 范围内的时隙时间线。 */
    @Transactional(readOnly = true)
    public List<SlotAllocation> timeline(String stationCode, Instant from, Instant to) {
        GroundStation station = stationRepository.findByCode(stationCode)
                .orElseThrow(() -> BusinessException.notFound("站点不存在: " + stationCode));
        return allocationRepository.findTimeline(station.id(), from, to);
    }

    /** 查询全部待重新分配的时隙。 */
    @Transactional(readOnly = true)
    public List<SlotAllocation> pendingReassign() {
        return allocationRepository.findByStatus(AllocationStatus.PENDING_REASSIGN);
    }

    /** 查询分配详情，含接收实绩（实际字节数）与取消原因。 */
    @Transactional(readOnly = true)
    public SlotAllocation detail(long id) {
        return allocationRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("分配不存在: " + id));
    }
}