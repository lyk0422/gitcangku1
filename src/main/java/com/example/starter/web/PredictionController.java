package com.example.starter.web;

import com.example.starter.service.PredictionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 卫星过境预测入口：发布新版本与版本查询。
 */
@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /** 发布某卫星的新版本预测；同一卫星再次发布即修订并触发未开始分配的重校核。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.PredictionResponse publish(@Valid @RequestBody Dtos.PublishPredictionRequest request) {
        return Dtos.PredictionResponse.from(predictionService.publish(
                request.satelliteCode(), request.windowStartUtc(), request.windowEndUtc(),
                request.minElevationDeg(), request.plannedBytes()));
    }

    /** 查询卫星全部预测版本。 */
    @GetMapping("/{satelliteCode}/versions")
    public List<Dtos.PredictionResponse> versions(@PathVariable String satelliteCode) {
        return predictionService.versions(satelliteCode).stream()
                .map(Dtos.PredictionResponse::from)
                .toList();
    }
}