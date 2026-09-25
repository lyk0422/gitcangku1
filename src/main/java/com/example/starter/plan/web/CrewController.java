package com.example.starter.plan.web;

import com.example.starter.plan.model.DayPlan;
import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.repo.PlanRepository;
import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.web.dto.CreateQualificationRequest;
import com.example.starter.plan.web.dto.CrewAssignmentRequest;
import com.example.starter.plan.web.dto.CrewRiskRecordView;
import com.example.starter.plan.web.dto.PlanCrewView;
import com.example.starter.plan.web.dto.QualificationView;
import com.example.starter.plan.web.dto.TerminateQualificationRequest;
import com.example.starter.plan.web.dto.UpdateQualificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
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
 * 乘务资质 API：资质创建/修改/提前终止、资质查询、
 * 计划乘务指派、风险记录与缺口诊断查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CrewController {

    private final CrewService crewService;
    private final PlanRepository planRepo;

    public CrewController(CrewService crewService, PlanRepository planRepo) {
        this.crewService = crewService;
        this.planRepo = planRepo;
    }

    /**
     * 创建乘务资质。
     */
    @PostMapping("/crew-qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationView create(@Valid @RequestBody CreateQualificationRequest request) {
        return crewService.createQualification(request);
    }

    /**
     * 修改资质覆盖区段与到期时刻（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/crew-qualifications/{qualCode}")
    public QualificationView update(@PathVariable String qualCode,
                                    @Valid @RequestBody UpdateQualificationRequest request) {
        return crewService.updateQualification(qualCode, request);
    }

    /**
     * 提前终止资质：回查所有未来已发布计划并写入不可变风险记录，任一回查失败整次回滚。
     */
    @PostMapping("/crew-qualifications/{qualCode}/terminate")
    public QualificationView terminate(@PathVariable String qualCode,
                                       @Valid @RequestBody TerminateQualificationRequest request) {
        return crewService.terminateQualification(qualCode, request);
    }

    /**
     * 资质明细查询。
     */
    @GetMapping("/crew-qualifications/{qualCode}")
    public QualificationView getQualification(@PathVariable String qualCode) {
        return crewService.getQualification(qualCode);
    }

    /**
     * 查询计划当前乘务指派（司机/车长及所依据资质）。
     */
    @GetMapping("/plans/{scheduleKey}/crew")
    public List<PlanCrewView> getPlanCrew(@PathVariable String scheduleKey) {
        return crewService.getPlanCrew(requirePlan(scheduleKey).id());
    }

    /**
     * 查询计划乘务风险记录（不可变，写入顺序）。
     */
    @GetMapping("/plans/{scheduleKey}/crew-risks")
    public List<CrewRiskRecordView> getPlanRisks(@PathVariable String scheduleKey) {
        return crewService.getRiskRecords(requirePlan(scheduleKey).id());
    }

    /**
     * 缺口诊断：对指定计划按给定乘务指派做与发布门禁一致的只读校验，
     * 返回稳定排序的缺口列表（空列表表示两角色资质完整）。
     */
    @GetMapping("/plans/{scheduleKey}/crew-gap-diagnosis")
    public List<Map<String, Object>> diagnose(@PathVariable String scheduleKey,
                                              @RequestParam @NotBlank String driverCrewId,
                                              @RequestParam @NotBlank String driverQualCode,
                                              @RequestParam @NotBlank String conductorCrewId,
                                              @RequestParam @NotBlank String conductorQualCode) {
        DayPlan plan = requirePlan(scheduleKey);
        List<Occupancy> occupancies = planRepo.findOccupancies(plan.id());
        return crewService.diagnose(occupancies,
                new CrewAssignmentRequest(driverCrewId, driverQualCode),
                new CrewAssignmentRequest(conductorCrewId, conductorQualCode));
    }

    private DayPlan requirePlan(String scheduleKey) {
        return planRepo.findByKey(scheduleKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAN_NOT_FOUND",
                        "计划不存在: " + scheduleKey));
    }
}
