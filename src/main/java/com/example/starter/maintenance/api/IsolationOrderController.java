package com.example.starter.maintenance.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.LockEventView;
import com.example.starter.maintenance.service.IsolationService;

/**
 * 隔离令级查询 API（不绑定设备路径）：锁定点确认/解除事件流水。
 */
@RestController
@RequestMapping("/api/isolation-orders")
public class IsolationOrderController {

    private final IsolationService service;

    public IsolationOrderController(IsolationService service) {
        this.service = service;
    }

    /** 隔离令锁定点事件流水（确认/解除，按发生顺序升序，稳定排序）。 */
    @GetMapping("/{orderId}/events")
    public List<LockEventView> listLockEvents(@PathVariable String orderId) {
        return service.listLockEvents(orderId);
    }
}
