package com.example.starter.plan.web;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.service.PlanService;
import com.example.starter.plan.web.dto.CreatePlanRequest;
import com.example.starter.plan.web.dto.CrewGapDiagnosisResponse;
import com.example.starter.plan.web.dto.CrewReplacementRequest;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.PlanCrewQualificationResponse;
import com.example.starter.plan.web.dto.PlanResponse;
import com.example.starter.plan.web.dto.PublishPlanRequest;
import com.example.starter.plan.web.dto.PublishedSlotView;
import com.example.starter.plan.web.dto.QualificationResponse;
import com.example.starter.plan.web.dto.RegisterQualificationRequest;
import com.example.starter.plan.web.dto.RescheduleChainResponse;
import com.example.starter.plan.web.dto.RescheduleRequest;
import com.example.starter.plan.web.dto.RescheduleResponse;
import com.example.starter.plan.web.dto.RiskRecordListResponse;
import com.example.starter.plan.web.dto.TerminateQualificationRequest;
import com.example.starter.plan.web.dto.TerminateQualificationResponse;
import com.example.starter.plan.web.dto.UpdateOccupanciesRequest;
import com.example.starter.plan.web.dto.UpdateQualificationRequest;
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
    private final CrewService crewService;

    public PlanController(PlanService service, CrewService crewService) {
        this.service = service;
        this.crewService = crewService;
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
     * 发布计划，原子校验时隙冲突、同车底风险门禁与乘务完整资质（如指定）。
     */
    @PostMapping("/plans/{scheduleKey}/publish")
    public PlanResponse publish(@PathVariable String scheduleKey,
                                @Valid @RequestBody PublishPlanRequest request) {
        return service.publish(scheduleKey, request);
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
     * 登记乘务员资质。
     */
    @PostMapping("/crew-qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationResponse registerQualification(
            @Valid @RequestBody RegisterQualificationRequest request) {
        return crewService.register(request);
    }

    /**
     * 修改乘务员资质（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/crew-qualifications/{crewId}/{qualificationCode}")
    public QualificationResponse updateQualification(
            @PathVariable String crewId, @PathVariable String qualificationCode,
            @Valid @RequestBody UpdateQualificationRequest request) {
        return crewService.update(crewId, qualificationCode, request);
    }

    /**
     * 提前终止资质：同一事务回查所有未来已发布计划并写入不可变风险记录。
     */
    @PostMapping("/crew-qualifications/{crewId}/{qualificationCode}/terminate")
    public TerminateQualificationResponse terminateQualification(
            @PathVariable String crewId, @PathVariable String qualificationCode,
            @Valid @RequestBody TerminateQualificationRequest request) {
        return crewService.terminate(crewId, qualificationCode, request);
    }

    /**
     * 风险计划换人：两角色一次性替换为合格人员并解除风险门禁。
     */
    @PostMapping("/plans/{scheduleKey}/crew-replacement")
    public PlanResponse replaceCrew(@PathVariable String scheduleKey,
                                    @Valid @RequestBody CrewReplacementRequest request) {
        return crewService.replaceCrew(scheduleKey, request);
    }

    /**
     * 查询计划乘务资质（两角色指派、资质清单与合格性）。
     */
    @GetMapping("/plans/{scheduleKey}/crew-qualification")
    public PlanCrewQualificationResponse getPlanCrewQualification(
            @PathVariable String scheduleKey) {
        return crewService.getPlanCrewQualification(scheduleKey);
    }

    /**
     * 查询计划乘务资质缺口诊断。
     */
    @GetMapping("/plans/{scheduleKey}/crew-qualification-gaps")
    public CrewGapDiagnosisResponse getCrewGaps(@PathVariable String scheduleKey) {
        return crewService.getCrewGaps(scheduleKey);
    }

    /**
     * 查询计划乘务风险记录。
     */
    @GetMapping("/plans/{scheduleKey}/risk-records")
    public RiskRecordListResponse getRiskRecords(@PathVariable String scheduleKey) {
        return crewService.getRiskRecords(scheduleKey);
    }
}
