package com.example.starter.prediction;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.prediction.dto.PredictionRequest;
import com.example.starter.prediction.dto.PredictionView;

/**
 * 过境预测接口：创建、修订与版本查询。
 */
@RestController
@RequestMapping("/api/predictions")
public class PredictionController {

    private final PredictionService predictionService;

    public PredictionController(PredictionService predictionService) {
        this.predictionService = predictionService;
    }

    /**
     * 创建预测首个版本。
     */
    @PostMapping("/{predictionKey}")
    @ResponseStatus(HttpStatus.CREATED)
    public PredictionView create(@PathVariable @NotBlank String predictionKey,
            @Valid @RequestBody PredictionRequest request) {
        return PredictionView.from(predictionService.create(predictionKey, request));
    }

    /**
     * 修订预测，产生新版本并触发未开始分配的重新校核。
     */
    @PostMapping("/{predictionKey}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public PredictionView revise(@PathVariable @NotBlank String predictionKey,
            @Valid @RequestBody PredictionRequest request) {
        return PredictionView.from(predictionService.revise(predictionKey, request));
    }

    /**
     * 查询预测全部版本。
     */
    @GetMapping("/{predictionKey}/versions")
    public List<PredictionView> versions(@PathVariable @NotBlank String predictionKey) {
        return predictionService.listVersions(predictionKey).stream()
                .map(PredictionView::from).toList();
    }
}