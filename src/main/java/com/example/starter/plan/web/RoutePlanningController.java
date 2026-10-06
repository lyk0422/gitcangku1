package com.example.starter.plan.web;

import com.example.starter.plan.service.RoutePlanningService;
import com.example.starter.plan.web.dto.GraphResponse;
import com.example.starter.plan.web.dto.ReplaceGraphRequest;
import com.example.starter.plan.web.dto.RoutePublishRequest;
import com.example.starter.plan.web.dto.RoutePublishResponse;
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
 * 线路图与寻径发布 API：图整体替换（版本从 1 起）、当前图查询、按图寻径一次发布日计划。
 */
@RestController
@RequestMapping("/api/v1")
public class RoutePlanningController {

    private final RoutePlanningService service;

    public RoutePlanningController(RoutePlanningService service) {
        this.service = service;
    }

    /**
     * 整体替换线路图：expectedVersion 必须等于当前图版本，成功一次版本加一。
     */
    @PutMapping("/line-graph")
    public GraphResponse replaceGraph(@Valid @RequestBody ReplaceGraphRequest request) {
        return service.replaceGraph(request);
    }

    /**
     * 查询当前线路图（版本与区段清单）。
     */
    @GetMapping("/line-graph")
    public GraphResponse getGraph() {
        return service.getGraph();
    }

    /**
     * 寻径发布：按图版本寻径并一次创建发布整张日计划，冻结图版本、路径与逐段时刻。
     */
    @PostMapping("/route-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public RoutePublishResponse publishRoute(@Valid @RequestBody RoutePublishRequest request) {
        return service.publishRoute(request);
    }
}
