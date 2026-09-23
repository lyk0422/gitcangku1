package com.example.starter.plan.web;

import com.example.starter.plan.service.SwitchService;
import com.example.starter.plan.web.dto.SwitchActivateRequest;
import com.example.starter.plan.web.dto.SwitchDetailResponse;
import com.example.starter.plan.web.dto.SwitchPreviewResponse;
import com.example.starter.plan.web.dto.SwitchRegisterRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 区段封锁切换单 API：登记、预览、原子激活与详情查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1/switches")
public class SwitchController {

    private final SwitchService service;

    public SwitchController(SwitchService service) {
        this.service = service;
    }

    /**
     * 登记区段封锁切换单。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SwitchDetailResponse register(@Valid @RequestBody SwitchRegisterRequest request) {
        return service.register(request);
    }

    /**
     * 预览全部相交 PUBLISHED 计划、版本与占用（只读）。
     */
    @GetMapping("/{switchKey}/preview")
    public SwitchPreviewResponse preview(@PathVariable String switchKey) {
        return service.preview(switchKey);
    }

    /**
     * 提交完整旧→替代映射并原子激活。
     */
    @PostMapping("/{switchKey}/activate")
    public SwitchDetailResponse activate(@PathVariable String switchKey,
                                         @Valid @RequestBody SwitchActivateRequest request) {
        return service.activate(switchKey, request);
    }

    /**
     * 查询切换单、映射与前后占用（只读）。
     */
    @GetMapping("/{switchKey}")
    public SwitchDetailResponse getSwitch(@PathVariable String switchKey) {
        return service.getSwitch(switchKey);
    }
}
