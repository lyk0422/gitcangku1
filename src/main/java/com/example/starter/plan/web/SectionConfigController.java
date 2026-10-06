package com.example.starter.plan.web;

import com.example.starter.plan.service.SectionConfigService;
import com.example.starter.plan.web.dto.SectionConfigRequest;
import com.example.starter.plan.web.dto.SectionConfigResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单线区段换向配置 API：创建/更新（幂等，expectedVersion 乐观校验）与查询。
 */
@RestController
@RequestMapping("/api/v1/sections")
public class SectionConfigController {

    private final SectionConfigService service;

    public SectionConfigController(SectionConfigService service) {
        this.service = service;
    }

    /**
     * 创建或更新区段换向配置：不存在时创建（版本 1），已存在时按 expectedVersion 更新。
     */
    @PutMapping("/{sectionId}/clearance")
    public SectionConfigResponse upsert(@PathVariable String sectionId,
                                        @Valid @RequestBody SectionConfigRequest request) {
        return service.upsert(sectionId, request);
    }

    /**
     * 查询区段换向配置，不存在返回 404。
     */
    @GetMapping("/{sectionId}/clearance")
    public SectionConfigResponse get(@PathVariable String sectionId) {
        return service.get(sectionId);
    }
}
