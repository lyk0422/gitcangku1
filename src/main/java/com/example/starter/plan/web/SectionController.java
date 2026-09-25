package com.example.starter.plan.web;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.SectionOccupancyView;
import com.example.starter.plan.web.dto.SectionPriorityRequest;
import com.example.starter.plan.web.dto.SectionPriorityView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 走廊区段 API：登记 1～5 级走廊等级，按区段查询当前等级与生效占用。
 */
@Validated
@RestController
@RequestMapping("/api/v1/sections")
public class SectionController {

    private final PlanService service;

    public SectionController(PlanService service) {
        this.service = service;
    }

    /**
     * 登记或更新区段走廊等级（1～5，数值越大优先级越高，幂等 upsert）。
     */
    @PutMapping("/{sectionId}/priority")
    public SectionPriorityView registerPriority(@PathVariable @NotBlank String sectionId,
                                                @Valid @RequestBody SectionPriorityRequest request) {
        return service.registerSectionPriority(sectionId, request.priority());
    }

    /**
     * 按区段查询当前等级与生效占用；date 可选，缺省不限运营日。
     */
    @GetMapping("/{sectionId}/occupancy")
    public SectionOccupancyView getOccupancy(
            @PathVariable @NotBlank String sectionId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getSectionOccupancy(sectionId, date);
    }
}
