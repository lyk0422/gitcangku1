package com.example.starter.water;

import com.example.starter.water.dto.Dtos.AllocationResponse;
import com.example.starter.water.dto.Dtos.CurveAllocationRequest;
import com.example.starter.water.dto.Dtos.StationConfigRequest;
import com.example.starter.water.dto.Dtos.StationResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 泵站与流量曲线 REST 接口。新增写操作携带 requestId 保证幂等，
 * 身份含操作、操作者（X-Actor-Id，可空）、资源与全部参数（含期望版本）。
 */
@RestController
@RequestMapping("/api")
public class PumpStationController {

    private final PumpStationService service;

    public PumpStationController(PumpStationService service) {
        this.service = service;
    }

    /** 写入泵站配置：expectedVersion=0 新建，否则须等于当前版本。 */
    @PostMapping("/pump-stations/{stationKey}/config")
    public StationResponse writeConfig(@PathVariable String stationKey,
                                       @RequestBody StationConfigRequest request,
                                       @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        return service.writeConfig(stationKey, request.requestId(), request.maxFlow(),
                request.maxRampPerMinute(), request.expectedVersion(), actor);
    }

    /** 查询泵站当前配置与版本。 */
    @GetMapping("/pump-stations/{stationKey}")
    public StationResponse getStation(@PathVariable String stationKey) {
        return service.getStation(stationKey);
    }

    /** 提交带流量曲线的配水申请，申请人为 actor。 */
    @PostMapping("/curve-allocations")
    public AllocationResponse submitCurveAllocation(@RequestBody CurveAllocationRequest request,
                                                    @RequestHeader("X-Actor-Id") String actor) {
        return service.submitCurveAllocation(request.requestId(), request.allocationKey(), request.windowId(),
                request.userId(), request.amount(), request.curve(), actor);
    }
}