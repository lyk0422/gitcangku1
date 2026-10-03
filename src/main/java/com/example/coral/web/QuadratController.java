package com.example.coral.web;

import java.util.List;

import com.example.coral.domain.PlantStatus;
import com.example.coral.domain.Quadrat;
import com.example.coral.domain.SealSnapshot;
import com.example.coral.domain.Transplant;
import com.example.coral.service.QuadratService;
import com.example.coral.service.TransplantService;
import com.example.coral.web.dto.Requests.CreateQuadratRequest;
import com.example.coral.web.dto.Responses.QuadratDetail;
import com.example.coral.web.dto.Responses.ReviewEntry;
import com.example.coral.web.dto.Responses.SealView;
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
 * 样方创建、封存与样方详情查询入口。
 */
@RestController
@RequestMapping("/api/quadrats")
public class QuadratController {

    private final QuadratService quadratService;
    private final TransplantService transplantService;

    public QuadratController(QuadratService quadratService, TransplantService transplantService) {
        this.quadratService = quadratService;
        this.transplantService = transplantService;
    }

    /** 创建合成样方。 */
    @PostMapping
    public ResponseEntity<QuadratDetail> create(@Valid @RequestBody CreateQuadratRequest req) {
        Quadrat q = quadratService.create(req.quadratCode(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(toDetail(q));
    }

    /** 封存样方：固化各状态数量及植株清单；已封存时重放返回原快照。 */
    @PostMapping("/{quadratCode}/seal")
    public SealView seal(@PathVariable String quadratCode) {
        return toSealView(quadratService.seal(quadratCode), quadratCode);
    }

    /** 样方详情：来源、当前状态数量、植株复核链与封存数量。 */
    @GetMapping("/{quadratCode}")
    public QuadratDetail detail(@PathVariable String quadratCode) {
        return toDetail(quadratService.findByCode(quadratCode));
    }

    private QuadratDetail toDetail(Quadrat q) {
        List<Transplant> plants = quadratService.plantsOf(q.id());
        List<TransplantDetail> details = plants.stream()
                .map(t -> new TransplantDetail(t.plantCode(), t.nursery(), q.quadratCode(),
                        t.status(), t.transplantedAt(), chainOf(t)))
                .toList();
        int alive = count(plants, PlantStatus.ALIVE);
        int dead = count(plants, PlantStatus.DEAD);
        int pending = count(plants, PlantStatus.PENDING);
        SealView seal = quadratService.snapshotOf(q.id())
                .map(s -> toSealView(s, q.quadratCode())).orElse(null);
        return new QuadratDetail(q.quadratCode(), q.name(), q.status(), alive + pending, alive,
                dead, pending, details, seal);
    }

    private List<ReviewEntry> chainOf(Transplant t) {
        return transplantService.reviewChain(t.id()).stream()
                .map(r -> new ReviewEntry(r.requestId(), r.result(), r.note(), r.reviewedAt()))
                .toList();
    }

    private static int count(List<Transplant> plants, PlantStatus status) {
        return (int) plants.stream().filter(t -> t.status() == status).count();
    }

    private static SealView toSealView(SealSnapshot s, String quadratCode) {
        return new SealView(quadratCode, s.aliveCount(), s.deadCount(), s.pendingCount(),
                s.plantCodes(), s.sealedAt());
    }
}