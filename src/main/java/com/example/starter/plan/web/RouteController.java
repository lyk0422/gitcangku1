package com.example.starter.plan.web;

import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.dto.CreateRouteRequest;
import com.example.starter.plan.web.dto.RouteMatrixResponse;
import com.example.starter.plan.web.dto.RouteResponse;
import com.example.starter.plan.web.dto.UpdateRouteMatrixRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进路注册与冲突矩阵 API：进路注册（上限 20 条）、矩阵查询与版本化更新。
 */
@RestController
@RequestMapping("/api/v1")
public class RouteController {

    private final RouteService service;

    public RouteController(RouteService service) {
        this.service = service;
    }

    /**
     * 注册新进路。
     */
    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public RouteResponse createRoute(@Valid @RequestBody CreateRouteRequest request) {
        return service.createRoute(request);
    }

    /**
     * 查询当前冲突矩阵（版本、进路集合与冲突对）。
     */
    @GetMapping("/route-matrix")
    public RouteMatrixResponse getMatrix() {
        return service.getMatrix();
    }

    /**
     * 更新冲突矩阵（expectedVersion 乐观校验，成功版本加一）；
     * 新增冲突对撞上已发布占用时 409 拒绝。
     */
    @PutMapping("/route-matrix")
    public RouteMatrixResponse updateMatrix(@Valid @RequestBody UpdateRouteMatrixRequest request) {
        return service.updateMatrix(request);
    }
}