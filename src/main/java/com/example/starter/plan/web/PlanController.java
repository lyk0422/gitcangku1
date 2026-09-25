package com.example.starter.plan.web;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.AdjustPlatformRequest;
import com.example.starter.plan.web.dto.BatchPublishRequest;
import com.example.starter.plan.web.dto.BatchPublishResponse;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PlatformAdjustResponse;
import com.example.starter.plan.web.dto.PlatformOccupancyView;
import com.example.starter.plan.web.dto.PlatformView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterConsistRequest;
import com.example.starter.plan.web.dto.RegisterPlatformRequest;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
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

    public PlanController(PlanService service) {
        this.service = service;
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
     * 发布计划，原子校验时隙冲突。
     */
    @PostMapping("/plans/{scheduleKey}/publish")
    public PlanResponse publish(@PathVariable String scheduleKey,
                                @Valid @RequestBody PlanActionRequest request) {
        return service.publish(scheduleKey, request.requestKey());
    }

    /**
     * 取消已发布计划，时隙立即释放，历史保留。
     */
    @PostMapping("/plans/{scheduleKey}/cancel")
    public PlanResponse cancel(@PathVariable String scheduleKey,
                               @Valid @RequestBody PlanActionRequest request) {
        return service.cancel(scheduleKey, request.requestKey());
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

    /**
     * 登记停靠站台（有效长度单位米，必须为正）。
     */
    @PostMapping("/platforms")
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformView registerPlatform(@Valid @RequestBody RegisterPlatformRequest request) {
        return service.registerPlatform(request);
    }

    /**
     * 查询站台当前有效长度与版本。
     */
    @GetMapping("/platforms/{platformCode}")
    public PlatformView getPlatform(@PathVariable String platformCode) {
        return service.getPlatform(platformCode);
    }

    /**
     * 调整站台有效长度；下调时同事务回查未来已发布计划并标记 PLATFORM_RISK 快照。
     */
    @PutMapping("/platforms/{platformCode}/length")
    public PlatformAdjustResponse adjustPlatform(@PathVariable String platformCode,
                                                 @Valid @RequestBody AdjustPlatformRequest request) {
        return service.adjustPlatform(platformCode, request);
    }

    /**
     * 按运营日期与站台代码查询当前已发布计划的停靠占用时段。
     */
    @GetMapping("/platforms/{platformCode}/occupancies")
    public List<PlatformOccupancyView> getPlatformOccupancies(
            @PathVariable String platformCode,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getPlatformOccupancies(date, platformCode);
    }

    /**
     * 登记/替换计划编组（车厢去重规范化，长度为正，站台存在，expectedVersion 乐观校验）。
     */
    @PutMapping("/plans/{scheduleKey}/consist")
    public PlanResponse registerConsist(@PathVariable String scheduleKey,
                                        @Valid @RequestBody RegisterConsistRequest request) {
        return service.registerConsist(scheduleKey, request);
    }

    /**
     * 批量发布：按最终站台占用与最终编组联合裁决，任一冲突 422 且整批不写入。
     */
    @PostMapping("/plans/batch-publish")
    public BatchPublishResponse batchPublish(@Valid @RequestBody BatchPublishRequest request) {
        return service.batchPublish(request);
    }
}
