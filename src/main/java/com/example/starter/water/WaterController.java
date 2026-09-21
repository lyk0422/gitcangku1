package com.example.starter.water;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.water.dto.AllocationView;
import com.example.starter.water.dto.CapacityView;
import com.example.starter.water.dto.CreateRestrictionCommand;
import com.example.starter.water.dto.CreateWindowCommand;
import com.example.starter.water.dto.KeyedCommand;
import com.example.starter.water.dto.RestrictionView;
import com.example.starter.water.dto.SubmitAllocationCommand;
import com.example.starter.water.dto.WindowHistoryView;
import com.example.starter.water.dto.WindowView;

/**
 * 灌区配水配额与限供 REST 接口。所有写操作均通过 commandKey 幂等。
 */
@RestController
@RequestMapping("/api/water")
public class WaterController {

    private final WaterService service;

    public WaterController(WaterService service) {
        this.service = service;
    }

    /** 创建供水窗口。 */
    @PostMapping("/windows")
    public ResponseEntity<WindowView> createWindow(@RequestBody CreateWindowCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createWindow(command));
    }

    /** 提交配水申请。 */
    @PostMapping("/allocations")
    public ResponseEntity<AllocationView> submitAllocation(
            @RequestHeader("X-Actor-Id") String actor,
            @RequestBody SubmitAllocationCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.submitAllocation(actor, command));
    }

    /** 批准配水申请。 */
    @PostMapping("/allocations/{allocationKey}/approve")
    public ResponseEntity<AllocationView> approveAllocation(
            @PathVariable String allocationKey,
            @RequestBody KeyedCommand command) {
        return ResponseEntity.ok(service.approveAllocation(allocationKey, command));
    }

    /** 取消配水申请（仅申请人本人）。 */
    @PostMapping("/allocations/{allocationKey}/cancel")
    public ResponseEntity<AllocationView> cancelAllocation(
            @RequestHeader("X-Actor-Id") String actor,
            @PathVariable String allocationKey,
            @RequestBody KeyedCommand command) {
        return ResponseEntity.ok(service.cancelAllocation(actor, allocationKey, command));
    }

    /** 创建限供。 */
    @PostMapping("/windows/{windowId}/restrictions")
    public ResponseEntity<RestrictionView> createRestriction(
            @PathVariable long windowId,
            @RequestBody CreateRestrictionCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createRestriction(windowId, command));
    }

    /** 取消当前生效限供。 */
    @PostMapping("/windows/{windowId}/restrictions/cancel")
    public ResponseEntity<RestrictionView> cancelRestriction(
            @PathVariable long windowId,
            @RequestBody KeyedCommand command) {
        return ResponseEntity.ok(service.cancelRestriction(windowId, command));
    }

    /** 查询窗口当前可用容量。 */
    @GetMapping("/windows/{windowId}/capacity")
    public ResponseEntity<CapacityView> getCapacity(@PathVariable long windowId) {
        return ResponseEntity.ok(service.getCapacity(windowId));
    }

    /** 查询窗口历史明细。 */
    @GetMapping("/windows/{windowId}/history")
    public ResponseEntity<WindowHistoryView> getHistory(@PathVariable long windowId) {
        return ResponseEntity.ok(service.getHistory(windowId));
    }
}
