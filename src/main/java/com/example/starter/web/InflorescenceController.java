package com.example.starter.web;

import com.example.starter.service.PollinationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 花序与授粉操作接口，路径统一挂在 /api/inflorescences 下。
 */
@RestController
@RequestMapping("/api/inflorescences")
public class InflorescenceController {

    private final PollinationService service;

    public InflorescenceController(PollinationService service) {
        this.service = service;
    }

    /** 新建花序，登记首次开放时刻（UTC）。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InflorescenceView create(@Valid @RequestBody CreateInflorescenceRequest request) {
        return service.createInflorescence(request);
    }

    /** 摘除花序：之后禁止新授粉操作，历史保留。 */
    @PostMapping("/{id}/removal")
    public InflorescenceView remove(@PathVariable long id) {
        return service.removeInflorescence(id);
    }

    /** 登记授粉操作（幂等）。 */
    @PostMapping("/{id}/pollinations")
    @ResponseStatus(HttpStatus.CREATED)
    public OperationView pollinate(@PathVariable long id, @Valid @RequestBody CreateOperationRequest request) {
        return service.createOperation(id, request);
    }

    /** 按花序查询窗口、花粉来源、操作及结果时间线。 */
    @GetMapping("/{id}/timeline")
    public TimelineView timeline(@PathVariable long id) {
        return service.getTimeline(id);
    }
}