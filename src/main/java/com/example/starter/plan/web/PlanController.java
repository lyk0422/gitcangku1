package com.example.starter.plan.web;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.PairCancelRequest;
import com.example.starter.plan.web.dto.PairPublishRequest;
import com.example.starter.plan.web.dto.PairPublishResponse;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishedSlotView;
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
     * 夜间计划对联合发布：同一事务重查两张草稿版本与两个运营日的区段冲突，
     * 两张同时变为已发布并写入不可变计划对记录。
     */
    @PostMapping("/night-pairs/publish")
    public PairPublishResponse publishPair(@Valid @RequestBody PairPublishRequest request) {
        return service.publishPair(request);
    }

    /**
     * 取消夜间计划对中的单张成员：仅释放该成员自身占用，另一张保持已发布。
     */
    @PostMapping("/night-pairs/cancel")
    public PlanResponse cancelPairMember(@Valid @RequestBody PairCancelRequest request) {
        return service.cancelPairMember(request);
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
}
