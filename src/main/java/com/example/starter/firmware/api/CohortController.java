package com.example.starter.firmware.api;

import com.example.starter.firmware.service.CohortService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投放队列、区域配额、设备首次分配与队列人工恢复。
 */
@RestController
@RequestMapping("/api/cohorts")
public class CohortController {

    private final CohortService cohortService;

    public CohortController(CohortService cohortService) {
        this.cohortService = cohortService;
    }

    @PostMapping("/regions")
    public CohortRegionView createRegion(@Valid @RequestBody CreateRegionRequest request) {
        return CohortRegionView.of(cohortService.createRegion(request));
    }

    @PostMapping
    public CohortView createCohort(@Valid @RequestBody CreateCohortRequest request) {
        return cohortService.createCohort(request);
    }

    @PostMapping("/assignments")
    public CommandView assign(@Valid @RequestBody AssignDeviceRequest request) {
        return cohortService.assignDevice(request);
    }

    @PostMapping("/{cohortId}/resume")
    public CohortView resume(@PathVariable long cohortId, @Valid @RequestBody ResumeCohortRequest request) {
        return cohortService.resumeCohort(cohortId, request);
    }
}
