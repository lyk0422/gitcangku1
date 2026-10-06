package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationPathResponse;
import com.example.starter.water.dto.Dtos.CreateSegmentRequest;
import com.example.starter.water.dto.Dtos.PathAllocationResponse;
import com.example.starter.water.dto.Dtos.SegmentResponse;
import com.example.starter.water.dto.Dtos.SubmitPathAllocationRequest;
import com.example.starter.water.dto.Dtos.UpdateResistanceRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 简化水力模型 REST 接口。所有写操作携带全局 requestId 幂等键与非空操作人（X-Actor-Id）。
 */
@RestController
@RequestMapping("/api")
public class HydraulicController {

    private final HydraulicService service;

    public HydraulicController(HydraulicService service) {
        this.service = service;
    }

    /** 创建渠段（阻力版本从 1 起）。 */
    @PostMapping("/segments")
    public SegmentResponse createSegment(@RequestBody CreateSegmentRequest request,
                                         @RequestHeader("X-Actor-Id") String actor) {
        return service.createSegment(request.requestId(), request.segmentKey(), request.resistance(),
                actor);
    }

    /** 修改渠段阻力（携带 expectedVersion；导致现存申请越限返回 409）。 */
    @PostMapping("/segments/{segmentKey}/resistance")
    public SegmentResponse updateResistance(@PathVariable String segmentKey,
                                          @RequestBody UpdateResistanceRequest request,
                                          @RequestHeader("X-Actor-Id") String actor) {
        return service.updateResistance(request.requestId(), segmentKey, request.resistance(),
                request.expectedVersion(), actor);
    }

    /** 查询渠段。 */
    @GetMapping("/segments/{segmentKey}")
    public SegmentResponse getSegment(@PathVariable String segmentKey) {
        return service.getSegment(segmentKey);
    }

    /** 提交新类型配水申请（带供水路径）。 */
    @PostMapping("/path-allocations")
    public PathAllocationResponse submitPathAllocation(@RequestBody SubmitPathAllocationRequest request,
                                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.submitPathAllocation(request.requestId(), request.allocationKey(),
                request.windowId(), request.userId(), request.flow(), request.startUtc(),
                request.endUtc(), request.availableHead(), request.segments(), actor);
    }

    /** 查询申请路径与审批冻结快照。 */
    @GetMapping("/allocations/{allocationKey}/path")
    public AllocationPathResponse getAllocationPath(@PathVariable String allocationKey) {
        return service.getAllocationPath(allocationKey);
    }
}
