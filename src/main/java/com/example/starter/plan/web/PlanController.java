package com.example.starter.plan.web;

import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PreemptionView;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.RegisterSectionRequest;
import com.example.starter.plan.web.dto.SectionOccupancyView;
import com.example.starter.plan.web.dto.SectionResponse;
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
 * 铁路走廊日计划 API：区段等级登记、草稿创建/整体替换、发布（含等级抢占）/取消、
 * 计划明细、已发布时隙、抢占记录与按区段的当前等级占用查询。
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
     * 登记走廊区段等级（1～5，数值越大越高）。
     */
    @PostMapping("/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionResponse registerSection(@Valid @RequestBody RegisterSectionRequest request) {
        return service.registerSection(request);
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
     * 发布计划，原子校验时隙冲突；携带 preemptKey 时按走廊等级抢占低等级已发布计划。
     */
    @PostMapping("/plans/{scheduleKey}/publish")
    public PlanResponse publish(@PathVariable String scheduleKey,
                                @Valid @RequestBody PlanActionRequest request) {
        return service.publish(scheduleKey, request.requestKey(), request.preemptKey());
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
     * 计划明细（含历史占用与计划等级）。
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
     * 查询全部抢占记录（不可变），含双方计划、各自等级与涉及时隙。
     */
    @GetMapping("/preemptions")
    public List<PreemptionView> listPreemptions() {
        return service.listPreemptions();
    }

    /**
     * 按区段查询当前已发布的等级占用（区段登记等级与所属计划等级）。
     */
    @GetMapping("/sections/{sectionId}/occupancy")
    public List<SectionOccupancyView> getSectionOccupancy(
            @PathVariable String sectionId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.getSectionOccupancy(date, sectionId);
    }
}
