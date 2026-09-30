package com.example.starter.water;

import com.example.starter.water.dto.Dtos.CommandRequest;
import com.example.starter.water.dto.Dtos.EmergencyActivationRequest;
import com.example.starter.water.dto.Dtos.EmergencyActivationResponse;
import com.example.starter.water.dto.Dtos.EmergencyEventDetailResponse;
import com.example.starter.water.dto.Dtos.EmergencyEventRequest;
import com.example.starter.water.dto.Dtos.EmergencyEventResponse;
import com.example.starter.water.dto.Dtos.EmergencyFlowListResponse;
import com.example.starter.water.dto.Dtos.EmergencySettlementRequest;
import com.example.starter.water.dto.Dtos.ReserveResponse;
import com.example.starter.water.dto.Dtos.WindowEmergencyResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 紧急储备 REST 接口。与普通配水接口相互独立：
 * 角色由 X-Role-Id 提供，确认人/操作人由 X-Actor-Id 提供；写操作携带 commandKey 保证幂等。
 */
@RestController
@RequestMapping("/api/emergency")
public class EmergencyController {

    private final EmergencyService service;

    public EmergencyController(EmergencyService service) {
        this.service = service;
    }

    /** 登记有效紧急事件。 */
    @PostMapping("/events")
    public EmergencyEventResponse createEvent(@RequestBody EmergencyEventRequest request) {
        return service.createEvent(request.commandKey(), request.eventKey(), request.title());
    }

    /** 作废紧急事件（标记为非紧急）。 */
    @PostMapping("/events/{eventKey}/invalidate")
    public EmergencyEventResponse invalidateEvent(@PathVariable String eventKey,
                                                  @RequestBody CommandRequest request) {
        return service.invalidateEvent(request.commandKey(), eventKey);
    }

    /** 查询事件明细与历史启用请求（保留当时事件与两位确认人）。 */
    @GetMapping("/events/{eventKey}")
    public EmergencyEventDetailResponse getEvent(@PathVariable String eventKey) {
        return service.getEvent(eventKey);
    }

    /** 双角色确认启用储备水至目标窗口。 */
    @PostMapping("/activations")
    public EmergencyActivationResponse confirm(@RequestBody EmergencyActivationRequest request,
                                               @RequestHeader("X-Role-Id") String role,
                                               @RequestHeader("X-Actor-Id") String actor) {
        return service.confirmActivation(request.commandKey(), request.requestId(), request.eventKey(),
                request.windowId(), request.amount(), role, actor);
    }

    /** 查询启用请求（储备预占、核销、归还与确认人）。 */
    @GetMapping("/activations/{requestId}")
    public EmergencyActivationResponse getRequest(@PathVariable String requestId) {
        return service.getRequest(requestId);
    }

    /** 核销已耗用储备水（不回补储备）。 */
    @PostMapping("/activations/{requestId}/writeoff")
    public EmergencyActivationResponse writeOff(@PathVariable String requestId,
                                                @RequestBody EmergencySettlementRequest request,
                                                @RequestHeader("X-Actor-Id") String actor) {
        return service.writeOff(request.commandKey(), requestId, request.amount(), actor);
    }

    /** 归还未耗用储备水（回补储备）。 */
    @PostMapping("/activations/{requestId}/return")
    public EmergencyActivationResponse returnWater(@PathVariable String requestId,
                                                   @RequestBody EmergencySettlementRequest request,
                                                   @RequestHeader("X-Actor-Id") String actor) {
        return service.returnWater(request.commandKey(), requestId, request.amount(), actor);
    }

    /** 查询启用单全部流水（启用、核销、归还）。 */
    @GetMapping("/activations/{requestId}/flows")
    public EmergencyFlowListResponse listRequestFlows(@PathVariable String requestId) {
        return service.listRequestFlows(requestId);
    }

    /** 查询储备账本。 */
    @GetMapping("/reserve")
    public ReserveResponse getReserve() {
        return service.getReserve();
    }

    /** 查询目标窗口的紧急水预占与启用单。 */
    @GetMapping("/windows/{windowId}")
    public WindowEmergencyResponse getWindowEmergency(@PathVariable long windowId) {
        return service.getWindowEmergency(windowId);
    }

    /** 查询目标窗口的紧急流水。 */
    @GetMapping("/windows/{windowId}/flows")
    public EmergencyFlowListResponse listWindowFlows(@PathVariable long windowId) {
        return service.listWindowFlows(windowId);
    }
}
