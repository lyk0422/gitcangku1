package com.example.starter.web;

import com.example.starter.service.AllocationService;
import com.example.starter.service.QueryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接收时隙分配入口：申请、取消、完成回报与查询。
 */
@RestController
@RequestMapping("/api/allocations")
public class AllocationController {

    private final AllocationService allocationService;
    private final QueryService queryService;

    public AllocationController(AllocationService allocationService, QueryService queryService) {
        this.allocationService = allocationService;
        this.queryService = queryService;
    }

    /** 申请分配时隙；同 requestId 同参重放返回原结果，异参 409。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.AllocationResponse allocate(@Valid @RequestBody Dtos.AllocateRequest request) {
        return Dtos.AllocationResponse.from(allocationService.allocate(
                request.requestId(), request.satelliteCode(), request.stationCode(),
                request.slotStartUtc(), request.slotEndUtc(), request.expectedPredictionVersion()));
    }

    /** 取消分配并记录原因，取消后时隙释放。 */
    @PostMapping("/{id}/cancel")
    public Dtos.AllocationResponse cancel(@PathVariable long id,
            @Valid @RequestBody Dtos.CancelRequest request) {
        return Dtos.AllocationResponse.from(allocationService.cancel(id, request.reason()));
    }

    /** 完成回报，记录实际字节数（不超过计划量）。 */
    @PostMapping("/{id}/complete")
    public Dtos.AllocationResponse complete(@PathVariable long id,
            @Valid @RequestBody Dtos.CompleteRequest request) {
        return Dtos.AllocationResponse.from(
                allocationService.complete(id, request.requestId(), request.actualBytes()));
    }

    /** 查询待重新分配的时隙列表。 */
    @GetMapping("/pending-reassign")
    public List<Dtos.AllocationResponse> pendingReassign() {
        return queryService.pendingReassign().stream()
                .map(Dtos.AllocationResponse::from)
                .toList();
    }

    /** 查询分配详情，含接收实绩与取消原因。 */
    @GetMapping("/{id}")
    public Dtos.AllocationResponse detail(@PathVariable long id) {
        return Dtos.AllocationResponse.from(queryService.detail(id));
    }
}