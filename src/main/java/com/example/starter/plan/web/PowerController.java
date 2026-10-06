package com.example.starter.plan.web;

import com.example.starter.plan.service.PowerService;
import com.example.starter.plan.web.dto.BindSectionRequest;
import com.example.starter.plan.web.dto.CreateSubstationRequest;
import com.example.starter.plan.web.dto.PowerAuditView;
import com.example.starter.plan.web.dto.SectionBindingResponse;
import com.example.starter.plan.web.dto.SubstationResponse;
import com.example.starter.plan.web.dto.UpdateSubstationRequest;
import jakarta.validation.Valid;
import java.util.List;
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
 * 供电预算 API：变电所创建/调参、区段绑定与功率裁决快照查询。
 * 写操作均以全局 requestId 幂等，携带非空操作者。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class PowerController {

    private final PowerService service;

    public PowerController(PowerService service) {
        this.service = service;
    }

    /**
     * 创建变电所（上限版本 1）。
     */
    @PostMapping("/substations")
    @ResponseStatus(HttpStatus.CREATED)
    public SubstationResponse createSubstation(@Valid @RequestBody CreateSubstationRequest request) {
        return service.createSubstation(request);
    }

    /**
     * 调整功率上限（expectedVersion 乐观校验；调低使现存计划超限则 409）。
     */
    @PutMapping("/substations/{substationKey}")
    public SubstationResponse updateCap(@PathVariable String substationKey,
                                        @Valid @RequestBody UpdateSubstationRequest request) {
        return service.updateCap(substationKey, request);
    }

    /**
     * 变电所明细。
     */
    @GetMapping("/substations/{substationKey}")
    public SubstationResponse getSubstation(@PathVariable String substationKey) {
        return service.getSubstation(substationKey);
    }

    /**
     * 功率裁决快照历史（冻结当时绑定、功率与上限版本，不漂移）。
     */
    @GetMapping("/substations/{substationKey}/power-audits")
    public List<PowerAuditView> getAudits(@PathVariable String substationKey) {
        return service.getAudits(substationKey);
    }

    /**
     * 绑定或改绑区段到变电所（绑定前须取消该区段已发布占用）。
     */
    @PutMapping("/sections/{sectionId}/binding")
    public SectionBindingResponse bindSection(@PathVariable String sectionId,
                                              @Valid @RequestBody BindSectionRequest request) {
        return service.bindSection(sectionId, request);
    }

    /**
     * 查询区段当前绑定。
     */
    @GetMapping("/sections/{sectionId}/binding")
    public SectionBindingResponse getBinding(@PathVariable String sectionId) {
        return service.getBinding(sectionId);
    }
}
