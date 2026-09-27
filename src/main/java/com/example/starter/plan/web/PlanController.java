package com.example.starter.plan.web;

import com.example.starter.plan.service.HazardConfigService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardConfigRequest;
import com.example.starter.plan.web.dto.HazardConfigView;
import com.example.starter.plan.web.dto.HazardDiagnosisResponse;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishHistoryResponse;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
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
 * 铁路走廊日计划 API：草稿创建/整体替换（含危险品编组）、发布/取消、原子改签、批量改签、
 * 危险品隔离配置版本、发布快照与历史、改签链与已发布时隙查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class PlanController {

    private final PlanService service;
    private final HazardConfigService configService;

    public PlanController(PlanService service, HazardConfigService configService) {
        this.service = service;
        this.configService = configService;
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
     * 整体替换草稿占用清单与危险品编组（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/plans/{scheduleKey}/occupancies")
    public PlanResponse replaceOccupancies(@PathVariable String scheduleKey,
                                           @Valid @RequestBody UpdateOccupanciesRequest request) {
        return service.replaceOccupancies(scheduleKey, request);
    }

    /**
     * 发布计划：在完整编组与完整区段路径上原子校验时隙与危险品隔离，并固化发布快照。
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
        return service.cancel(scheduleKey, request.requestKey(), request.operator());
    }

    /**
     * 原子改签：同一事务取消路径中的已发布旧计划并发布同运营日的新草稿，
     * 固化新计划快照、追加不可变前后继关联；任一校验失败整体回滚。
     */
    @PostMapping("/plans/{scheduleKey}/reschedule")
    public RescheduleResponse reschedule(@PathVariable String scheduleKey,
                                         @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(scheduleKey, request);
    }

    /**
     * 批量改签：整批先验证完整后态（含批内互相冲突与危险品隔离），
     * 任一计划非法则整次 422，时隙与编组均不改变；全部合法后单事务提交。
     */
    @PostMapping("/reschedules/batch")
    public BatchRescheduleResponse batchReschedule(
            @Valid @RequestBody BatchRescheduleRequest request) {
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
     * 计划明细（含历史占用与危险品编组）。
     */
    @GetMapping("/plans/{scheduleKey}")
    public PlanResponse getPlan(@PathVariable String scheduleKey) {
        return service.getPlan(scheduleKey);
    }

    /**
     * 计划发布快照（固化编组、类别、配置版本与校验结论）；只读不改写历史。
     */
    @GetMapping("/plans/{scheduleKey}/publish-snapshot")
    public PublishSnapshotView getPublishSnapshot(@PathVariable String scheduleKey) {
        return service.getPublishSnapshot(scheduleKey);
    }

    /**
     * 危险品隔离诊断（只读）：对当前完整编组与完整区段路径执行与发布一致的校验，
     * 返回冲突明细，不发布、不修改状态。
     */
    @GetMapping("/plans/{scheduleKey}/hazard-diagnosis")
    public HazardDiagnosisResponse diagnose(@PathVariable String scheduleKey) {
        return service.diagnose(scheduleKey);
    }

    /**
     * 发布历史查询，按发布时刻升序、快照 id 升序稳定排列；可按运营日闭区间过滤、限制条数。
     */
    @GetMapping("/publish-history")
    public PublishHistoryResponse getPublishHistory(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @Min(1) @Max(1000) Integer limit) {
        return service.getPublishHistory(fromDate, toDate, limit);
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
     * 写入危险品隔离配置版本（互斥类别间隔、限制区段开放清单、生效区间），整体仅追加。
     */
    @PostMapping("/hazard-configs")
    @ResponseStatus(HttpStatus.CREATED)
    public HazardConfigView writeHazardConfig(@Valid @RequestBody HazardConfigRequest request) {
        return configService.writeConfig(request);
    }

    /**
     * 按版本号查询危险品配置版本。
     */
    @GetMapping("/hazard-configs/{versionNo}")
    public HazardConfigView getHazardConfig(@PathVariable @Min(1) int versionNo) {
        return configService.getVersion(versionNo);
    }

    /**
     * 查询指定 UTC 时刻生效的危险品配置版本；无生效版本返回 404（语义：当前无任何限制）。
     */
    @GetMapping("/hazard-configs/effective")
    public HazardConfigView getEffectiveHazardConfig(
            @RequestParam @NotNull
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant atUtc) {
        HazardConfigView view = configService.getEffectiveAt(atUtc);
        if (view == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "HAZARD_CONFIG_NOT_FOUND",
                    "指定时刻无生效危险品配置版本");
        }
        return view;
    }
}
