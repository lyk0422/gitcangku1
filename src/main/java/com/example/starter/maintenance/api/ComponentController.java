package com.example.starter.maintenance.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.ComponentInstallView;
import com.example.starter.maintenance.api.dto.ComponentResponse;
import com.example.starter.maintenance.api.dto.InstallComponentRequest;
import com.example.starter.maintenance.api.dto.RegisterComponentRequest;
import com.example.starter.maintenance.api.dto.RemoveComponentRequest;
import com.example.starter.maintenance.service.ComponentService;

/**
 * 可拆卸部件装拆账 API。部件跨设备轮换，寿命按装拆区间累计，与设备保养无关。
 */
@RestController
@RequestMapping("/api/components")
public class ComponentController {

    private final ComponentService service;

    public ComponentController(ComponentService service) {
        this.service = service;
    }

    /** 登记部件：合成编号唯一，寿命分钟为正整数，登记后不可修改；初始版本 1、累计 0。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentResponse register(@Valid @RequestBody RegisterComponentRequest req) {
        return service.register(req);
    }

    /** 安装部件：引用目标设备已提交读数，时刻以读数 UTC 采样时刻为准；同部件同一时刻仅装一台。 */
    @PostMapping("/{componentId}/installs")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentInstallView install(@PathVariable String componentId,
                                        @Valid @RequestBody InstallComponentRequest req) {
        return service.install(componentId, req);
    }

    /** 拆卸部件：引用在装设备已提交读数，拆卸累计不得小于安装累计，差值计入部件累计。 */
    @PostMapping("/{componentId}/removals")
    @ResponseStatus(HttpStatus.CREATED)
    public ComponentInstallView remove(@PathVariable String componentId,
                                       @Valid @RequestBody RemoveComponentRequest req) {
        return service.remove(componentId, req);
    }

    /** 部件状态：当前在装设备、已结算累计分钟、是否达到寿命上限。 */
    @GetMapping("/{componentId}")
    public ComponentResponse getComponent(@PathVariable String componentId) {
        return service.getComponent(componentId);
    }

    /** 部件历次装拆账（按记录顺序升序，区间左闭右开）。 */
    @GetMapping("/{componentId}/installs")
    public List<ComponentInstallView> listInstalls(@PathVariable String componentId) {
        return service.listInstalls(componentId);
    }
}