package com.example.starter.plan.web;

import com.example.starter.plan.service.RouteService;
import com.example.starter.plan.web.dto.RouteGraphReplaceRequest;
import com.example.starter.plan.web.dto.RouteGraphResponse;
import com.example.starter.plan.web.dto.RoutePlanRequest;
import com.example.starter.plan.web.dto.RoutePlanResponse;
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
 * 线路图与寻径发布 API：图整体替换（expectedVersion 乐观校验，成功版本加一）、
 * 当前图查询、按起终站寻径并一次发布 PUBLISHED 日计划。
 */
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
    @PutMapping("/route-graph")
    public RouteGraphResponse replaceGraph(@Valid @RequestBody RouteGraphReplaceRequest request) {
        return service.replaceGraph(request);
    }

    /**
     * 查询当前线路图。
     */
    @GetMapping("/route-graph")
    public RouteGraphResponse getCurrentGraph() {
        return service.getCurrentGraph();
    }

    /**
     * 寻径并一次发布：选总分钟最少的可行路径，一次创建 PUBLISHED 日计划。
     */
    @PostMapping("/route-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutePlanResponse findAndPublish(@Valid @RequestBody RoutePlanRequest request) {
        return service.findAndPublish(request);
    }
}
