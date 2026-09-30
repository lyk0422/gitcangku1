package com.example.starter.film.web;

import com.example.starter.film.dto.ApproveRequest;
import com.example.starter.film.dto.CreateFilmRequest;
import com.example.starter.film.dto.DeliveryRequest;
import com.example.starter.film.dto.ReviewRequest;
import com.example.starter.film.dto.SegmentSubmitRequest;
import com.example.starter.film.model.DeliveryReceipt;
import com.example.starter.film.model.Film;
import com.example.starter.film.model.Master;
import com.example.starter.film.model.SegmentVersion;
import com.example.starter.film.service.ApproveOutcome;
import com.example.starter.film.service.FilmQueryService;
import com.example.starter.film.service.FilmService;
import com.example.starter.film.service.SegmentSubmitOutcome;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影片修复版本管理 REST 入口。
 */
@RestController
@RequestMapping("/api/films")
public class FilmController {

    private final FilmService service;
    private final FilmQueryService queryService;

    public FilmController(FilmService service, FilmQueryService queryService) {
        this.service = service;
        this.queryService = queryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody CreateFilmRequest req) {
        Film film = service.createFilm(req.code(), req.title(), req.plannedSegments());
        return filmView(film);
    }

    @GetMapping("/{filmId}")
    public Map<String, Object> detail(@PathVariable long filmId) {
        FilmQueryService.FilmDetail d = queryService.getDetail(filmId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("film", filmView(d.film()));
        root.put("segments", d.segmentChain().stream().map(this::segmentView).toList());
        root.put("reviews", d.reviews().stream().map(this::reviewView).toList());
        root.put("masters", d.masters().stream().map(this::masterView).toList());
        root.put("deliveries", d.deliveries().stream().map(this::receiptView).toList());
        return root;
    }

    @PostMapping("/{filmId}/segments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submitSegment(@PathVariable long filmId,
                                             @Valid @RequestBody SegmentSubmitRequest req) {
        SegmentSubmitOutcome outcome = service.submitSegment(
                filmId, req.seq(), req.digest(), req.baseVersion(), req.requestId());
        Map<String, Object> body = segmentView(outcome.version());
        body.put("expiredReviewCount", outcome.expiredReviewCount());
        return body;
    }

    @PostMapping("/{filmId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReview(@PathVariable long filmId,
                                            @Valid @RequestBody ReviewRequest req) {
        return reviewRawView(service.createReview(filmId, req.requestId()));
    }

    @PostMapping("/{filmId}/reviews/{reviewId}/approve")
    public Map<String, Object> approve(@PathVariable long filmId, @PathVariable long reviewId,
                                       @Valid @RequestBody ApproveRequest req) {
        ApproveOutcome outcome = service.approveReview(filmId, reviewId, req.requestId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("review", reviewRawView(outcome.review()));
        body.put("master", masterSimpleView(outcome.master()));
        return body;
    }

    @PostMapping("/{filmId}/reviews/{reviewId}/revoke")
    public Map<String, Object> revoke(@PathVariable long filmId, @PathVariable long reviewId,
                                      @Valid @RequestBody ApproveRequest req) {
        return reviewRawView(service.revokeReview(filmId, reviewId, req.requestId()));
    }

    @PostMapping("/{filmId}/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> deliver(@PathVariable long filmId,
                                       @Valid @RequestBody DeliveryRequest req) {
        DeliveryReceipt receipt = service.deliver(filmId, req.masterNo(), req.requestId());
        return receiptView(receipt);
    }

    // ---- 视图 ---------------------------------------------------------

    private Map<String, Object> filmView(Film film) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", film.id());
        m.put("code", film.code());
        m.put("title", film.title());
        m.put("plannedSegments", film.plannedSegments());
        m.put("createdAt", film.createdAt());
        return m;
    }

    private Map<String, Object> segmentView(SegmentVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.id());
        m.put("seq", v.seq());
        m.put("version", v.versionNo());
        m.put("digest", v.digest());
        m.put("requestId", v.requestId());
        m.put("createdAt", v.createdAt());
        return m;
    }

    private Map<String, Object> reviewRawView(com.example.starter.film.model.ReviewRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("requestId", r.requestId());
        m.put("status", r.status().name());
        m.put("masterId", r.masterId());
        m.put("createdAt", r.createdAt());
        m.put("decidedAt", r.decidedAt());
        return m;
    }

    private Map<String, Object> reviewView(FilmQueryService.ReviewView rv) {
        Map<String, Object> m = reviewRawView(rv.review());
        m.put("baselines", rv.baselines().stream().map(b -> {
            Map<String, Object> bm = new LinkedHashMap<>();
            bm.put("seq", b.seq());
            bm.put("version", b.versionNo());
            bm.put("digest", b.digest());
            bm.put("fixedAt", b.fixedAt());
            return bm;
        }).toList());
        return m;
    }

    private Map<String, Object> masterView(FilmQueryService.MasterView mv) {
        Map<String, Object> m = masterSimpleView(mv.master());
        m.put("segments", mv.segments().stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("seq", s.seq());
            sm.put("version", s.versionNo());
            sm.put("digest", s.digest());
            return sm;
        }).toList());
        return m;
    }

    private Map<String, Object> masterSimpleView(Master master) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", master.id());
        m.put("masterNo", master.masterNo());
        m.put("filmId", master.filmId());
        m.put("reviewId", master.reviewId());
        m.put("segmentCount", master.segmentCount());
        m.put("createdAt", master.createdAt());
        return m;
    }

    private Map<String, Object> receiptView(DeliveryReceipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("requestId", r.requestId());
        m.put("masterNo", r.masterNo());
        m.put("masterId", r.masterId());
        m.put("snapshot", r.snapshotDigest());
        m.put("createdAt", r.createdAt());
        return m;
    }
}
