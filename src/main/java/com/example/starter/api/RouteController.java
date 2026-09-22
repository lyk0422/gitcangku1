package com.example.starter.api;

import com.example.starter.api.dto.CreateRouteRequest;
import com.example.starter.api.dto.ReplaceRouteRequest;
import com.example.starter.api.dto.RouteResponse;
import com.example.starter.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 航线接口：创建与整体替换点列，所有写操作携带全局唯一 requestId。
 */
@RestController
@RequestMapping("/api/routes")
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    /**
     * 创建航线：初始版本为 1。
     */
    @PostMapping
    public RouteResponse create(@Valid @RequestBody CreateRouteRequest request) {
        return routeService.create(request);
    }

    /**
     * 替换航线点列：expectedVersion 须等于当前版本，成功后版本加一并使当前审核失效。
     */
    @PutMapping("/{routeId}")
    public RouteResponse replace(@PathVariable String routeId,
                                 @Valid @RequestBody ReplaceRouteRequest request) {
        return routeService.replace(routeId, request);
    }
}
