package com.example.starter.plan.web;

import com.example.starter.plan.service.HazardConfigService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardRuleSetView;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanDiagnosticsView;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.ReplaceHazardRulesRequest;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.SectionRestrictionView;
import com.example.starter.plan.web.dto.UpdateConsistRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.example.starter.plan.web.dto.UpdateSectionRestrictionRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路走廊日计划 API：草稿创建/占用与危险品编组整体替换、发布（危险品隔离校验 + 快照）、
 * 取消、单个/批量原子改签、计划明细/改签链/发布快照/诊断/已发布时隙查询，
 * 以及危险品互斥规则集与限制区段开放清单的版本化配置。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class PlanController {

    private final PlanService service;
    private final HazardConfigService hazardConfigService;

    public PlanController(PlanService service, HazardConfigService hazardConfigService) {
        this.service = service;
        this.hazardConfigService = hazardConfigService;
    }

    /**
     * 创建草稿计划。
     */
    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PlanResponse create(@Valid @RequestBody CreatePlanRequest request) {
        return service.createDraft(request);
    }

    /**
     * 整体替换草稿占用清单（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/plans/{scheduleKey}/occupancies")
    public PlanResponse replaceOccupancies(@PathVariable String scheduleKey,
                                           @Valid @RequestBody UpdateOccupanciesRequest request) {
        return service.replaceOccupancies(scheduleKey, request);
    }

    /**
     * 整体替换草稿危险品编组（expectedVersion 乐观校验，成功版本加一；位置重复 422）。
     */
    @PutMapping("/plans/{scheduleKey}/consist")
    public PlanResponse replaceConsist(@PathVariable String scheduleKey,
                                       @Valid @RequestBody UpdateConsistRequest request) {
        return service.replaceConsist(scheduleKey, request);
    }

    /**
     * 发布计划：在完整编组与完整区段路径上原子校验时隙冲突与危险品隔离，并固化发布快照。
     */
    @PostMapping("/plans/{scheduleKey}/publish")
    public PlanResponse publish(@PathVariable String scheduleKey,
                                @Valid @RequestBody PlanActionRequest request) {
        return service.publish(scheduleKey, request.requestKey(), request.operator());
    }

    /**
     * 取消已发布计划，时隙立即释放，历史与发布快照保留。
     */
    @PostMapping("/plans/{scheduleKey}/cancel")
    public PlanResponse cancel(@PathVariable String scheduleKey,
                               @Valid @RequestBody PlanActionRequest request) {
        return service.cancel(scheduleKey, request.requestKey());
    }

    /**
     * 原子改签：同一事务取消旧计划并发布新草稿，追加不可变前后继关联与发布快照；
     * 任一校验失败整体回滚。
     */
    @PostMapping("/plans/{scheduleKey}/reschedule")
    public RescheduleResponse reschedule(@PathVariable String scheduleKey,
                                         @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(scheduleKey, request);
    }

    /**
     * 批量原子改签：所有改签项先在完整后态上统一校验，全部合法才在一个事务内提交，
     * 含任一非法计划时整次不改变时隙或编组。
     */
    @PostMapping("/batch-reschedules")
    public BatchRescheduleResponse batchReschedule(@Valid @RequestBody BatchRescheduleRequest request) {
        return service.batchReschedule(request);
    }

    /**
     * 查询包含指定计划在内的完整有序改签链。
     */
    @GetMapping("/plans/{scheduleKey}/reschedule-chain")
    public RescheduleChainResponse getRescheduleChain(@PathVariable String scheduleKey) {
        return service.getRescheduleChain(scheduleKey);
    }

    /**
     * 查询计划发布快照（固化编组、类别、区段限制版本与校验结论）。
     */
    @GetMapping("/plans/{scheduleKey}/publish-snapshot")
    public PublishSnapshotView getPublishSnapshot(@PathVariable String scheduleKey) {
        return service.getSnapshot(scheduleKey);
    }

    /**
     * 只读诊断：以当前危险品配置对计划当前编组与区段路径执行与发布相同的校验。
     */
    @GetMapping("/plans/{scheduleKey}/diagnostics")
    public PlanDiagnosticsView diagnose(@PathVariable String scheduleKey) {
        return service.diagnose(scheduleKey);
    }

    /**
     * 计划明细（含历史占用与编组）。
     */
    @GetMapping("/plans/{scheduleKey}")
    public PlanResponse getPlan(@PathVariable String scheduleKey) {
        return service.getPlan(scheduleKey);
    }

    /**
     * 按运营日期与区段查询当前已发布时隙。
     */
    @GetMapping("/published-slots")
    public List<PublishedSlotView> getPublishedSlots(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam @NotBlank String sectionId) {
        return service.getPublishedSlots(date, sectionId);
    }

    /**
     * 整体替换危险品互斥规则集（expectedVersion 乐观校验，追加新版本；配置非法 422 不落库）。
     */
    @PutMapping("/hazard-rules")
    public HazardRuleSetView replaceHazardRules(@Valid @RequestBody ReplaceHazardRulesRequest request) {
        return hazardConfigService.replaceRules(request);
    }

    /**
     * 查询当前危险品互斥规则集版本。
     */
    @GetMapping("/hazard-rules")
    public HazardRuleSetView getHazardRules() {
        return hazardConfigService.getCurrentRules();
    }

    /**
     * 整体替换限制区段危险品开放清单（expectedVersion 乐观校验，追加新版本）。
     */
    @PutMapping("/sections/{sectionId}/restriction")
    public SectionRestrictionView replaceSectionRestriction(@PathVariable String sectionId,
                                                            @Valid
                                                            @RequestBody UpdateSectionRestrictionRequest request) {
        return hazardConfigService.replaceSectionRestriction(sectionId, request);
    }

    /**
     * 查询限制区段当前开放清单版本（未配置返回 version=0、allowedClasses=null）。
     */
    @GetMapping("/sections/{sectionId}/restriction")
    public SectionRestrictionView getSectionRestriction(@PathVariable String sectionId) {
        return hazardConfigService.getCurrentSection(sectionId);
    }
}
