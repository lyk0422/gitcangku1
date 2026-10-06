package com.example.starter.plan.web;

import com.example.starter.plan.service.SectionConfigService;
import com.example.starter.plan.web.dto.SectionConfigCreateRequest;
import com.example.starter.plan.web.dto.SectionConfigResponse;
import com.example.starter.plan.web.dto.SectionConfigUpdateRequest;
import jakarta.validation.Valid;
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
 * 单线区段换向配置 API：创建（版本 1）、按 expectedVersion 更新与查询。
 * 配置与发布、改签、取消按同一串行边界裁决。
 */
@Validated
@RestController
@RequestMapping("/api/v1/section-configs")
public class SectionConfigController {

    private final SectionConfigService service;

    public SectionConfigController(SectionConfigService service) {
        this.service = service;
    }

    /**
     * 创建区段单线配置（版本 1）。区段已配置或已发布集合无法纳入新配置时 409。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SectionConfigResponse create(@Valid @RequestBody SectionConfigCreateRequest request) {
        return service.create(request);
    }

    /**
     * 更新区段单线配置（expectedVersion 乐观校验，成功版本加一）；
     * 新配置下当前已发布集合非法则 409。
     */
    @PutMapping("/{sectionId}")
    public SectionConfigResponse update(@PathVariable String sectionId,
                                        @Valid @RequestBody SectionConfigUpdateRequest request) {
        return service.update(sectionId, request);
    }

    /**
     * 查询区段单线配置。
     */
    @GetMapping("/{sectionId}")
    public SectionConfigResponse get(@PathVariable String sectionId) {
        return service.get(sectionId);
    }
}
