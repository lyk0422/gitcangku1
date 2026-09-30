package com.example.starter.plan.web;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.CandidatePlatformView;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PlatformCapabilityView;
import com.example.starter.plan.web.dto.PlatformOccupancyView;
import com.example.starter.plan.web.dto.PlatformRegistrationRequest;
import com.example.starter.plan.web.dto.PlatformView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
 * 铁路走廊日计划 API：草稿创建/整体替换、发布/取消、原子改签、
 * 计划明细、改签链、已发布时隙查询，以及编组到发站台的能力登记、
 * 候选站台适配查询与站台占用查询。
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
     * 发布计划，原子校验时隙冲突与编组到发站台适配/窗口占用。
     */
    @PostMapping("/plans/{scheduleKey}/publish")
    public PlanResponse publish(@PathVariable String scheduleKey,
                                @Valid @RequestBody PlanActionRequest request) {
        return service.publish(scheduleKey, request.requestKey());
    }

    /**
     * 取消已发布计划，区段时隙与站台到发窗口立即释放，历史保留。
     */
    @PostMapping("/plans/{scheduleKey}/cancel")
    public PlanResponse cancel(@PathVariable String scheduleKey,
                               @Valid @RequestBody PlanActionRequest request) {
        return service.cancel(scheduleKey, request.requestKey());
    }

    /**
     * 原子改签：同一事务取消路径中的已发布旧计划并发布同运营日的新草稿，
     * 按新草稿最终时段与编组完成站台适配/窗口校验后一起更新，追加不可变前后继关联；
     * 任一校验失败整体回滚。
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
     * 计划明细（含历史占用与发布时固化的站台能力版本）。
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
     * 站台管理方登记/变更站台能力（首次登记版本 1，能力变化升版本并留存历史）。
     */
    @PutMapping("/platforms/{platformId}")
    public PlatformView registerPlatform(@PathVariable String platformId,
                                         @Valid @RequestBody PlatformRegistrationRequest request) {
        return service.registerPlatform(platformId, request);
    }

    /**
     * 查询站台当前能力。
     */
    @GetMapping("/platforms/{platformId}")
    public PlatformView getPlatform(@PathVariable String platformId) {
        return service.getPlatform(platformId);
    }

    /**
     * 查询站台能力的指定历史版本（历史发布当时的站台能力）。
     */
    @GetMapping("/platforms/{platformId}/capabilities/{version}")
    public PlatformCapabilityView getPlatformCapability(@PathVariable String platformId,
                                                       @PathVariable int version) {
        return service.getPlatformCapabilityVersion(platformId, version);
    }

    /**
     * 站台管理方查询指定站台当前生效（已发布）的到发占用，按开始时刻升序。
     */
    @GetMapping("/platforms/{platformId}/occupancies")
    public List<PlatformOccupancyView> getPlatformOccupancies(@PathVariable String platformId) {
        return service.getPlatformOccupancies(platformId);
    }

    /**
     * 调度员候选站台查询：按编组长度（米）、供电制式、无障碍需求与 UTC 窗口评估全部站台，
     * 返回能力适配结论、逐条不适配原因与窗口重叠占用。可传 platformId 仅评估单个站台。
     */
    @GetMapping("/candidate-platforms")
    public List<CandidatePlatformView> findCandidates(
            @RequestParam(required = false) String platformId,
            @RequestParam @NotNull @Positive Integer consistLengthMeters,
            @RequestParam @NotBlank String powerSystem,
            @RequestParam @NotNull Boolean accessibleRequired,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant startUtc,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant endUtc) {
        return service.findCandidates(platformId, consistLengthMeters, powerSystem,
                accessibleRequired, startUtc, endUtc);
    }
}
