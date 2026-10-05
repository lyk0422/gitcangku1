package com.example.starter.maintenance.api;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.ComponentResponse;
import com.example.starter.maintenance.api.dto.ComponentStatusResponse;
import com.example.starter.maintenance.api.dto.InstallComponentRequest;
import com.example.starter.maintenance.api.dto.InstallResponse;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.UninstallComponentRequest;
import com.example.starter.maintenance.api.dto.UninstallResponse;
import com.example.starter.maintenance.service.ComponentService;

/**
 * 部件装拆账 API：部件在多台设备间轮换，寿命按装拆区间（左闭右开）累计，
 * 与整台设备累计工时及设备保养状态相互独立。
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {

    private final ComponentService service;

    public ComponentController(ComponentService service) {
        this.service = service;
    }

    /** 登记部件：合成编号唯一，寿命上限（分钟）为固定正整数，登记后不可修改。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentResponse register(@Valid @RequestBody RegisterComponentRequest req) {
        return service.register(req);
    }

    /** 安装：引用目标设备已提交读数记起始累计；同部件同一时刻只能装一台，寿命达上限禁止安装。 */
    @PostMapping("/{componentId}/installs")
    @ResponseStatus(HttpStatus.CREATED)
    public InstallResponse install(@PathVariable String componentId,
                                   @Valid @RequestBody InstallComponentRequest req) {
        return service.install(componentId, req);
    }

    /** 拆卸：引用当前在装设备已提交读数记结束累计，差值加入部件累计；拆卸累计不得小于安装累计。 */
    @PostMapping("/{componentId}/uninstall")
    @ResponseStatus(HttpStatus.CREATED)
    public UninstallResponse uninstall(@PathVariable String componentId,
                                       @Valid @RequestBody UninstallComponentRequest req) {
        return service.uninstall(componentId, req);
    }

    /** 部件状态：当前归属设备、历次装拆账与累计寿命。 */
    @GetMapping("/{componentId}")
    public ComponentStatusResponse getStatus(@PathVariable String componentId) {
        return service.getStatus(componentId);
    }
}
