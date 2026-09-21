package com.example.starter.api;

import com.example.starter.api.dto.Responses;
import com.example.starter.common.TimeSupport;
import com.example.starter.service.DecisionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 播出决定接口：按频道与时刻查询应播出内容。
 */
@RestController
public class DecisionController {

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @GetMapping("/channels/{channelId}/decision")
    public Responses.DecisionView decide(@PathVariable String channelId,
                                         @RequestParam String at) {
        Instant instant = TimeSupport.parse(at, "at");
        return ViewMapper.toView(decisionService.decide(channelId, instant));
    }
}
