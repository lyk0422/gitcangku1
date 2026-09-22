package com.example.starter.api;

import com.example.starter.api.dto.CreateZoneRequest;
import com.example.starter.api.dto.RevokeZoneRequest;
import com.example.starter.api.dto.ZoneResponse;
import com.example.starter.service.ZoneService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 禁飞区接口：创建与撤销，所有写操作携带全局唯一 requestId。
 */
@RestController
@RequestMapping("/api/zones")
public class ZoneController {

    private final ZoneService zoneService;

    public ZoneController(ZoneService zoneService) {
        this.zoneService = zoneService;
    }

    /**
     * 创建禁飞区：成功后全局空域版本加一。
     */
    @PostMapping
    public ZoneResponse create(@Valid @RequestBody CreateZoneRequest request) {
        return zoneService.create(request);
    }

    /**
     * 撤销禁飞区：成功后全局空域版本加一，历史审核结论不受影响。
     */
    @PostMapping("/{zoneId}/revoke")
    public ZoneResponse revoke(@PathVariable String zoneId,
                               @Valid @RequestBody RevokeZoneRequest request) {
        return zoneService.revoke(zoneId, request);
    }
}
