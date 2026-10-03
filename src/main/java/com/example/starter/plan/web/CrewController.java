package com.example.starter.plan.web;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.web.dto.CrewCheckResponse;
import com.example.starter.plan.web.dto.GrantQualificationRequest;
import com.example.starter.plan.web.dto.PlanActionRequest;
import com.example.starter.plan.web.dto.QualificationResponse;
import com.example.starter.plan.web.dto.SectionGradeRequest;
import com.example.starter.plan.web.dto.SectionGradeResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘务资格门禁 API：资格授予/撤销/查询、区段线路等级登记、
 * 计划逐人资格覆盖结果查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CrewController {

    private final CrewService service;

    public CrewController(CrewService service) {
        this.service = service;
    }

    /**
     * 授予乘务资格（幂等）。已存在有效资格时返回 409，须先撤销。
     */
    @PostMapping("/crew-qualifications")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationResponse grant(@Valid @RequestBody GrantQualificationRequest request) {
        return service.grant(request);
    }

    /**
     * 撤销乘务资格（幂等）：状态置为 REVOKED，版本加一。
     */
    @PostMapping("/crew-qualifications/{crewMemberId}/revoke")
    public QualificationResponse revoke(@PathVariable String crewMemberId,
                                        @Valid @RequestBody PlanActionRequest request) {
        return service.revoke(crewMemberId, request.requestKey());
    }

    /**
     * 查询乘务员当前资格。
     */
    @GetMapping("/crew-qualifications/{crewMemberId}")
    public QualificationResponse getQualification(@PathVariable String crewMemberId) {
        return service.getQualification(crewMemberId);
    }

    /**
     * 登记或更新区段线路等级（幂等）。
     */
    @PutMapping("/sections/{sectionId}/grade")
    public SectionGradeResponse registerGrade(@PathVariable String sectionId,
                                              @Valid @RequestBody SectionGradeRequest request) {
        return service.registerSectionGrade(sectionId, request.lineGrade(), request.requestKey());
    }

    /**
     * 查询计划逐人资格覆盖结果、占用窗口与冲突计划（按人员稳定升序）。
     */
    @GetMapping("/plans/{scheduleKey}/crew-check")
    public CrewCheckResponse getCrewCheck(@PathVariable @NotBlank String scheduleKey) {
        return service.getCrewCheck(scheduleKey);
    }
}