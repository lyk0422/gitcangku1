package com.example.starter.plan;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路走廊日计划 API：草稿创建/更新、发布/取消、计划明细与已发布时隙查询。
 */
@RestController
@RequestMapping("/api")
public class PlanController {

    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    /**
     * 创建草稿计划。
     */
    @PostMapping("/plans")
    public ResponseEntity<PlanResponse> create(@Valid @RequestBody CreatePlanRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    /**
     * 按 expectedVersion 整体替换草稿占用清单。
     */
    @PutMapping("/plans/{scheduleKey}")
    public PlanResponse update(@PathVariable String scheduleKey, @Valid @RequestBody UpdatePlanRequest request) {
        return service.update(scheduleKey, request);
    }

    /**
     * 发布草稿计划；冲突时返回 422 且计划保持草稿。
     */
    @PostMapping("/plans/{scheduleKey}/publish")
    public PlanResponse publish(@PathVariable String scheduleKey, @Valid @RequestBody KeyedRequest request) {
        return service.publish(scheduleKey, request);
    }

    /**
     * 取消已发布计划，时隙立即释放。
     */
    @PostMapping("/plans/{scheduleKey}/cancel")
    public PlanResponse cancel(@PathVariable String scheduleKey, @Valid @RequestBody KeyedRequest request) {
        return service.cancel(scheduleKey, request);
    }

    /**
     * 查询计划明细（含历史计划及原始占用）。
     */
    @GetMapping("/plans/{scheduleKey}")
    public PlanResponse getPlan(@PathVariable String scheduleKey) {
        return service.getPlan(scheduleKey);
    }

    /**
     * 按运营日期与区段查询当前已发布时隙。
     */
    @GetMapping("/slots")
    public List<SlotView> listSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam String sectionId) {
        return service.listPublishedSlots(date, sectionId);
    }
}
