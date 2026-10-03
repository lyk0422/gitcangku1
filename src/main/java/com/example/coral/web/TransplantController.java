package com.example.coral.web;

import java.util.List;

import com.example.coral.domain.Review;
import com.example.coral.domain.Transplant;
import com.example.coral.service.QuadratService;
import com.example.coral.service.TransplantService;
import com.example.coral.web.dto.Requests.ReviewRequest;
import com.example.coral.web.dto.Requests.TransplantRequest;
import com.example.coral.web.dto.Responses.ReviewEntry;
import com.example.coral.web.dto.Responses.ReviewResult;
import com.example.coral.web.dto.Responses.TransplantDetail;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 移植登记、复核与单株查询入口。
 */
@RestController
@RequestMapping("/api")
public class TransplantController {

    private final TransplantService transplantService;
    private final QuadratService quadratService;

    public TransplantController(TransplantService transplantService,
            QuadratService quadratService) {
        this.transplantService = transplantService;
        this.quadratService = quadratService;
    }

    /** 移植登记：201新建；同requestId同参重放返回200原记录。 */
    @PostMapping("/transplants")
    public ResponseEntity<TransplantDetail> register(@Valid @RequestBody TransplantRequest req) {
        boolean replay = transplantService.findByRequestId(req.requestId()).isPresent();
        Transplant t = transplantService.register(req.requestId(), req.plantCode(), req.nursery(),
                req.quadratCode());
        return ResponseEntity.status(replay ? HttpStatus.OK : HttpStatus.CREATED)
                .body(toDetail(t));
    }

    /** 复核：标为存活、死亡或待复核。 */
    @PostMapping("/reviews")
    public ReviewResult review(@Valid @RequestBody ReviewRequest req) {
        Review r = transplantService.review(req.requestId(), req.plantCode(), req.result(),
                req.note());
        var owner = transplantService.findByPlantCode(req.plantCode());
        return new ReviewResult(r.requestId(), owner.plantCode(), r.result(), r.reviewedAt());
    }

    /** 单株查询：来源苗圃、当前样方与复核链。 */
    @GetMapping("/transplants/{plantCode}")
    public TransplantDetail detail(@PathVariable String plantCode) {
        return toDetail(transplantService.findByPlantCode(plantCode));
    }

    private TransplantDetail toDetail(Transplant t) {
        var quadrat = quadratService.findById(t.quadratId());
        List<ReviewEntry> chain = transplantService.reviewChain(t.id()).stream()
                .map(r -> new ReviewEntry(r.requestId(), r.result(), r.note(), r.reviewedAt()))
                .toList();
        return new TransplantDetail(t.plantCode(), t.nursery(), quadrat.quadratCode(), t.status(),
                t.transplantedAt(), chain);
    }
}