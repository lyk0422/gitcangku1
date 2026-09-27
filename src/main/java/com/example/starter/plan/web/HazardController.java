package com.example.starter.plan.web;

import com.example.starter.plan.service.HazardService;
import com.example.starter.plan.web.dto.ConfigureSectionRequest;
import com.example.starter.plan.web.dto.HazardCarsResponse;
import com.example.starter.plan.web.dto.HazardDiagnosticsResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.RestrictedSectionView;
import com.example.starter.plan.web.dto.RestrictedSectionsResponse;
import com.example.starter.plan.web.dto.SeparationRulesResponse;
import com.example.starter.plan.web.dto.UpdateHazardCarsRequest;
import com.example.starter.plan.web.dto.UpdateRulesRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路危险品编组隔离 API：编组登记、互斥规则集、限制区段配置、
 * 诊断试校验与发布快照历史查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class HazardController {

    private final HazardService service;

    public HazardController(HazardService service) {
        this.service = service;
    }

    /**
     * 整体替换计划危险品编组（仅草稿，expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/plans/{scheduleKey}/hazard-cars")
    public HazardCarsResponse replaceHazardCars(@PathVariable String scheduleKey,
                                                @Valid @RequestBody UpdateHazardCarsRequest request) {
        return service.replaceHazardCars(scheduleKey, request);
    }

    /**
     * 查询计划当前编组（按车厢位置升序）。
     */
    @GetMapping("/plans/{scheduleKey}/hazard-cars")
    public HazardCarsResponse getHazardCars(@PathVariable String scheduleKey) {
        return service.getHazardCars(scheduleKey);
    }

    /**
     * 诊断试校验：当前编组与完整区段路径对照当前版本配置，不改变状态。
     */
    @GetMapping("/plans/{scheduleKey}/hazard-check")
    public HazardDiagnosticsResponse diagnostics(@PathVariable String scheduleKey) {
        return service.diagnostics(scheduleKey);
    }

    /**
     * 查询计划发布快照历史（按追加顺序，快照不可变）。
     */
    @GetMapping("/plans/{scheduleKey}/hazard-snapshots")
    public List<PublishSnapshotView> getSnapshots(@PathVariable String scheduleKey) {
        return service.getSnapshots(scheduleKey);
    }

    /**
     * 整体替换互斥规则集（成功规则集版本加一）。
     */
    @PutMapping("/hazard/separation-rules")
    public SeparationRulesResponse replaceRules(@Valid @RequestBody UpdateRulesRequest request) {
        return service.replaceRules(request);
    }

    /**
     * 查询当前互斥规则集（含版本，按类别对字典序）。
     */
    @GetMapping("/hazard/separation-rules")
    public SeparationRulesResponse getRules() {
        return service.getRules();
    }

    /**
     * 覆盖式设置限制区段开放清单（成功区段配置版本加一）。
     */
    @PutMapping("/hazard/restricted-sections/{sectionId}")
    public RestrictedSectionView configureSection(@PathVariable String sectionId,
                                                  @Valid @RequestBody ConfigureSectionRequest request) {
        return service.configureSection(sectionId, request);
    }

    /**
     * 查询全部限制区段配置（含版本，按区段 ID 字典序）。
     */
    @GetMapping("/hazard/restricted-sections")
    public RestrictedSectionsResponse getSections() {
        return service.getSections();
    }

    /**
     * 查询单个限制区段配置，未配置返回 404。
     */
    @GetMapping("/hazard/restricted-sections/{sectionId}")
    public RestrictedSectionView getSection(@PathVariable String sectionId) {
        return service.getSection(sectionId);
    }
}
