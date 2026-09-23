package com.example.starter.plan.web;

import com.example.starter.plan.service.DisruptionService;
import com.example.starter.plan.web.dto.DisruptionActivateRequest;
import com.example.starter.plan.web.dto.DisruptionDetailResponse;
import com.example.starter.plan.web.dto.DisruptionPreviewResponse;
import com.example.starter.plan.web.dto.DisruptionRegisterRequest;
import com.example.starter.plan.web.dto.DisruptionSubmitRequest;
import com.example.starter.plan.web.dto.DisruptionSwitchView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 区段封锁切换 API：登记封锁窗口、预览相交已发布计划、提交旧计划到替代草稿的映射、
 * 单事务原子激活，以及切换单（含映射与前后占用）查询。
 */
@RestController
@RequestMapping("/api/v1/disruptions")
public class DisruptionController {

    private final DisruptionService service;

    public DisruptionController(DisruptionService service) {
        this.service = service;
    }

    /**
     * 登记区段封锁切换单（REGISTERED），窗口为左闭右开 UTC 区间。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DisruptionSwitchView register(@Valid @RequestBody DisruptionRegisterRequest request) {
        return service.register(request);
    }

    /**
     * 预览与封锁窗口相交的全部 PUBLISHED 计划、版本与占用，只读不写。
     */
    @GetMapping("/{switchKey}/preview")
    public DisruptionPreviewResponse preview(@PathVariable String switchKey) {
        return service.preview(switchKey);
    }

    /**
     * 提交完整旧计划集合与一对一替代草稿映射；重复提交整体替换。
     */
    @PutMapping("/{switchKey}/mappings")
    public DisruptionDetailResponse submitMappings(@PathVariable String switchKey,
                                                   @Valid @RequestBody DisruptionSubmitRequest request) {
        return service.submitMappings(switchKey, request);
    }

    /**
     * 原子激活：事务内重新计算影响集合并校验版本、状态、链环与时隙，
     * 成功挂起全部旧计划、发布全部替代计划并写入替代链与快照。
     */
    @PostMapping("/{switchKey}/activate")
    public DisruptionDetailResponse activate(@PathVariable String switchKey,
                                             @Valid @RequestBody DisruptionActivateRequest request) {
        return service.activate(switchKey, request);
    }

    /**
     * 查询切换单、映射与前后占用；ACTIVE 返回激活时不可变快照，只读不写。
     */
    @GetMapping("/{switchKey}")
    public DisruptionDetailResponse getSwitch(@PathVariable String switchKey) {
        return service.getSwitch(switchKey);
    }
}
