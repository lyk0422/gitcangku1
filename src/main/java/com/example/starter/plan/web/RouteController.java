package com.example.starter.plan.web;

import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.dto.GraphResponse;
import com.example.starter.plan.web.dto.ReplaceGraphRequest;
import com.example.starter.plan.web.dto.RoutePlanResponse;
import com.example.starter.plan.web.dto.RoutePublishRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线路图与寻径发布 API：图整体替换（expectedVersion 乐观校验）、当前图查询、
 * 按图寻径并一次发布 PUBLISHED 日计划。
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
     * 整体替换线路图，成功版本加一。
     */
    @PutMapping("/graph")
    public GraphResponse replaceGraph(@Valid @RequestBody ReplaceGraphRequest request) {
        return service.replaceGraph(request);
    }

    /**
     * 查询当前线路图。
     */
    @GetMapping("/graph")
    public GraphResponse getGraph() {
        return service.getGraph();
    }

    /**
     * 寻径发布：按当前图选择最优可行路径，一次创建 PUBLISHED 日计划。
     */
    @PostMapping("/route-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutePlanResponse publishRoute(@Valid @RequestBody RoutePublishRequest request) {
        return service.publishRoute(request);
    }
}
