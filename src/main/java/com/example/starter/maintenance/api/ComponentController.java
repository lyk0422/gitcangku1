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
import com.example.starter.maintenance.api.dto.InstallationResponse;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.RemoveComponentRequest;
import com.example.starter.maintenance.service.ComponentService;

/**
 * 可拆卸部件装拆账 API。部件在多台设备间轮换，寿命按装拆区间累计，与设备整机工时分离。
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {

    private final ComponentService service;

    public ComponentController(ComponentService service) {
        this.service = service;
    }

    /** 登记部件：合成编号唯一，寿命上限（分钟）为固定正整数；初始版本 1、累计寿命 0。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentResponse register(@Valid @RequestBody RegisterComponentRequest req) {
        return service.register(req);
    }

    /** 安装部件：以目标设备一条已提交读数标记起点；已安装或寿命耗尽返回 409。 */
    @PostMapping("/{componentId}/installations")
    @ResponseStatus(HttpStatus.CREATED)
    public InstallationResponse install(@PathVariable String componentId,
                                        @Valid @RequestBody InstallComponentRequest req) {
        return service.install(componentId, req);
    }

    /** 拆卸部件：以当前设备一条已提交读数标记终点，区间差值计入部件累计寿命。 */
    @PostMapping("/{componentId}/removals")
    @ResponseStatus(HttpStatus.CREATED)
    public InstallationResponse remove(@PathVariable String componentId,
                                       @Valid @RequestBody RemoveComponentRequest req) {
        return service.remove(componentId, req);
    }

    /** 部件台账：当前所在设备、历次装拆（区间左闭右开）与累计寿命。 */
    @GetMapping("/{componentId}")
    public ComponentStatusResponse getComponent(@PathVariable String componentId) {
        return service.getComponent(componentId);
    }
}
