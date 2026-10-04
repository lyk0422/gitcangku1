package com.example.starter.plan.web;

import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.dto.DeclareRouteRequest;
import com.example.starter.plan.web.dto.RouteMatrixResponse;
import com.example.starter.plan.web.dto.RouteResponse;
import com.example.starter.plan.web.dto.UpdateRouteMatrixRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进路注册与冲突矩阵 API：进路声明（最多 20 条）、矩阵查询与乐观版本更新。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    /**
     * 声明进路；已存在时幂等返回当前状态，超过 20 条返回 409。
     */
    @PutMapping("/routes/{routeId}")
    public RouteResponse declareRoute(@PathVariable @NotBlank String routeId,
                                      @Valid @RequestBody DeclareRouteRequest request) {
        return service.declareRoute(routeId, request);
    }

    /**
     * 查询全部已注册进路。
     */
    @GetMapping("/routes")
    public List<RouteResponse> listRoutes() {
        return service.listRoutes();
    }

    /**
     * 查询当前进路冲突矩阵（版本与全部对称冲突对）。
     */
    @GetMapping("/route-matrix")
    public RouteMatrixResponse getMatrix() {
        return service.getMatrix();
    }

    /**
     * 更新进路冲突矩阵（expectedVersion 乐观校验，成功版本加一）。
     */
    @PutMapping("/route-matrix")
    public RouteMatrixResponse updateMatrix(@Valid @RequestBody UpdateRouteMatrixRequest request) {
        return service.updateMatrix(request);
    }
}