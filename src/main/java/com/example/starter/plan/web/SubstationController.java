package com.example.starter.plan.web;

import com.example.starter.plan.service.SubstationService;
import com.example.starter.plan.web.dto.BindSectionRequest;
import com.example.starter.plan.web.dto.CreateSubstationRequest;
import com.example.starter.plan.web.dto.PowerSnapshotView;
import com.example.starter.plan.web.dto.SectionBindingResponse;
import com.example.starter.plan.web.dto.SubstationResponse;
import com.example.starter.plan.web.dto.UpdateSubstationRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供电预算 API：变电所创建/调上限/查询、区段绑定/改绑、计划功率快照查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class SubstationController {

    private final SubstationService service;

    public SubstationController(SubstationService service) {
        this.service = service;
    }

    /**
     * 创建变电所（版本 1，正整数功率上限）。
     */
    @PostMapping("/substations")
    @ResponseStatus(HttpStatus.CREATED)
    public SubstationResponse create(@Valid @RequestBody CreateSubstationRequest request) {
        return service.createSubstation(request);
    }

    /**
     * 调整功率上限（expectedVersion 乐观校验，调低使现存计划超限则 409）。
     */
    @PutMapping("/substations/{substationKey}")
    public SubstationResponse updateLimit(@PathVariable String substationKey,
                                          @Valid @RequestBody UpdateSubstationRequest request) {
        return service.updateLimit(substationKey, request);
    }

    /**
     * 查询变电所当前上限与版本。
     */
    @GetMapping("/substations/{substationKey}")
    public SubstationResponse getSubstation(@PathVariable String substationKey) {
        return service.getSubstation(substationKey);
    }

    /**
     * 绑定/改绑区段到变电所（未绑定旧区段须先取消旧已发布占用）。
     */
    @PutMapping("/sections/{sectionId}/binding")
    public SectionBindingResponse bindSection(@PathVariable String sectionId,
                                              @Valid @RequestBody BindSectionRequest request) {
        return service.bindSection(sectionId, request);
    }

    /**
     * 查询计划发布/改签时冻结的功率快照（绑定、功率、上限版本），历史不漂移。
     */
    @GetMapping("/plans/{scheduleKey}/power-snapshot")
    public List<PowerSnapshotView> getPowerSnapshots(@PathVariable String scheduleKey) {
        return service.getPowerSnapshots(scheduleKey);
    }
}
