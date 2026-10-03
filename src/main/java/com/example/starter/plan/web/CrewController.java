package com.example.starter.plan.web;

import com.example.starter.plan.service.CrewService;
import com.example.starter.plan.web.dto.CrewCheckResponse;
import com.example.starter.plan.web.dto.CrewGroupRequest;
import com.example.starter.plan.web.dto.CrewGroupResponse;
import com.example.starter.plan.web.dto.QualificationGrantRequest;
import com.example.starter.plan.web.dto.QualificationResponse;
import com.example.starter.plan.web.dto.QualificationRevokeRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘务资格门禁 API：乘务组登记、资格授予/撤销、计划逐人资格核查查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class CrewController {

    private final CrewService crewService;

    public CrewController(CrewService crewService) {
        this.crewService = crewService;
    }

    /**
     * 创建乘务组（名册不可变）。
     */
    @PostMapping("/crew-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public CrewGroupResponse createGroup(@Valid @RequestBody CrewGroupRequest request) {
        return crewService.createGroup(request);
    }

    /**
     * 授予乘务资格（版本递增，旧有效版本作废）。
     */
    @PostMapping("/qualifications/grant")
    @ResponseStatus(HttpStatus.CREATED)
    public QualificationResponse grant(@Valid @RequestBody QualificationGrantRequest request) {
        return crewService.grant(request);
    }

    /**
     * 撤销乘务员当前有效资格；与发布经全局发布锁串行化。
     */
    @PostMapping("/qualifications/revoke")
    public QualificationResponse revoke(@Valid @RequestBody QualificationRevokeRequest request) {
        return crewService.revoke(request);
    }

    /**
     * 计划逐人资格核查：已发布计划返回发布时快照，草稿按 crewGroup 实时评估。
     */
    @GetMapping("/plans/{scheduleKey}/crew-check")
    public CrewCheckResponse crewCheck(@PathVariable String scheduleKey,
                                       @RequestParam(required = false) String crewGroup) {
        return crewService.crewCheck(scheduleKey, crewGroup);
    }
}