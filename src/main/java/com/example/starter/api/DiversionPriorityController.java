package com.example.starter.api;

import com.example.starter.api.dto.CapacitySetRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.PreemptionResultDto;
import com.example.starter.api.dto.PriorityReviewRequest;
import com.example.starter.repo.ClearancePo;
import com.example.starter.service.DiversionPriorityService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 紧急备降优先级与时空容量抢占 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class DiversionPriorityController {

    private final DiversionPriorityService service;

    public DiversionPriorityController(DiversionPriorityService service) {
        this.service = service;
    }

    /** 提交带备降优先级的审查（NORMAL / EMERGENCY，EMERGENCY 触发抢占裁决）。 */
    @PostMapping("/reviews/priority")
    public ResponseEntity<MutationResponse> submitPriorityReview(
            @Valid @RequestBody PriorityReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submitPriorityReview(request));
    }

    /** 起飞登记：批件转为 DEPARTED 后不可被抢占。 */
    @PostMapping("/departures")
    public ResponseEntity<MutationResponse> registerDeparture(
            @Valid @RequestBody DepartureRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerDeparture(request));
    }

    /** 设置（覆盖）格网单元容量。 */
    @PostMapping("/capacity")
    public ResponseEntity<MutationResponse> setCapacity(
            @Valid @RequestBody CapacitySetRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.setCapacity(request));
    }

    /** 查询容量桶：单元容量、占用数与占用明细。 */
    @GetMapping("/capacity/buckets")
    public DiversionPriorityService.CapacityBucketViewResult getCapacityBucket(
            @RequestParam int cellX,
            @RequestParam int cellY,
            @RequestParam long timeBucket) {
        return service.getCapacityBucket(cellX, cellY, timeBucket);
    }

    /** 查询批件当前状态。 */
    @GetMapping("/clearances/{clearanceId}")
    public ClearancePo getClearance(@PathVariable String clearanceId) {
        return service.getClearance(clearanceId);
    }

    /** 查询不可变抢占快照。 */
    @GetMapping("/preemptions/{preemptionId}")
    public PreemptionResultDto getPreemption(@PathVariable String preemptionId) {
        return service.getPreemption(preemptionId);
    }

    /** 查询某航线被置换的全部记录（含处理状态）。 */
    @GetMapping("/routes/{routeId}/displaced")
    public Object getDisplacedRoutes(@PathVariable String routeId) {
        return service.getDisplacedRoutes(routeId);
    }
}
