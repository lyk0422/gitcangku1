package com.example.starter.plan.web;

import com.example.starter.plan.service.HazardService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.BatchRescheduleRequest;
import com.example.starter.plan.web.dto.BatchRescheduleResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.HazardConfigRequest;
import com.example.starter.plan.web.dto.HazardConfigResponse;
import com.example.starter.plan.web.dto.HazardDiagnosticView;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishSnapshotSummary;
import com.example.starter.plan.web.dto.PublishSnapshotView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateCarsRequest;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
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
 * 铁路走廊日计划 API：草稿创建/整体替换、发布/取消、原子改签、
 * 计划明细、改签链与已发布时隙查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class PlanController {

    private final PlanService service;
    private final HazardService hazardService;

    public PlanController(PlanService service, HazardService hazardService) {
        this.service = service;
        this.hazardService = hazardService;
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
     * 整体替换草稿计划危险品编组（expectedVersion 乐观校验，成功版本加一）；
     * 车厢位置重复返回 422 并列出冲突对。
     */
    @PutMapping("/plans/{scheduleKey}/cars")
    public PlanResponse replaceCars(@PathVariable String scheduleKey,
                                    @Valid @RequestBody UpdateCarsRequest request) {
        return service.replaceCars(scheduleKey, request);
    }

    /**
     * 发布计划，原子校验时隙冲突与危险品联合校验（完整编组 + 完整区段路径），
     * 通过后固化发布快照。
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
     * 追加不可变前后继关联；任一校验失败整体回滚。
     */
    @PostMapping("/plans/{scheduleKey}/reschedule")
    public RescheduleResponse reschedule(@PathVariable String scheduleKey,
                                         @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(scheduleKey, request);
    }

    /**
     * 批量原子改签：先校验全部计划的完整最终状态，再在一个事务中提交；
     * 任一计划非法则整次不改变时隙或编组。
     */
    @PostMapping("/plans/batch-reschedule")
    public BatchRescheduleResponse batchReschedule(@Valid @RequestBody BatchRescheduleRequest request) {
        return service.batchReschedule(request);
    }

    /**
     * 整体替换危险品配置（隔离规则 + 限制区段）：先校验完整最终状态，
     * 再在一个事务中提交；配置非法返回 422 且整体不生效。
     */
    @PutMapping("/hazard/config")
    public HazardConfigResponse replaceHazardConfig(@Valid @RequestBody HazardConfigRequest request) {
        return hazardService.replaceConfig(request);
    }

    /**
     * 查询当前危险品配置（隔离规则与限制区段），稳定排序。
     */
    @GetMapping("/hazard/config")
    public HazardConfigResponse getHazardConfig() {
        return hazardService.getConfig();
    }

    /**
     * 危险品联合校验诊断：对计划当前编组与完整区段路径做只读校验，不改变状态。
     */
    @GetMapping("/plans/{scheduleKey}/hazard-diagnostic")
    public HazardDiagnosticView getHazardDiagnostic(@PathVariable String scheduleKey) {
        return hazardService.getDiagnostic(scheduleKey);
    }

    /**
     * 查询计划发布快照明细（固化编组、类别、区段限制版本与校验结论）。
     */
    @GetMapping("/plans/{scheduleKey}/publish-snapshot")
    public PublishSnapshotView getPublishSnapshot(@PathVariable String scheduleKey) {
        return hazardService.getSnapshot(scheduleKey);
    }

    /**
     * 按运营日查询发布快照历史，按 (createdAt, id) 稳定排序。
     */
    @GetMapping("/publish-snapshots")
    public List<PublishSnapshotSummary> listPublishSnapshots(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return hazardService.listSnapshots(date);
    }

    /**
     * 查询包含指定计划在内的完整有序改签链。
     */
    @GetMapping("/plans/{scheduleKey}/reschedule-chain")
    public RescheduleChainResponse getRescheduleChain(@PathVariable String scheduleKey) {
        return service.getRescheduleChain(scheduleKey);
    }

    /**
     * 计划明细（含历史占用）。
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
}
