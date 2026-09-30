package com.example.starter.film.api;

import com.example.starter.film.service.FilmService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 影片修复业务 HTTP 入口。
 */
@RestController
@RequestMapping("/api/films")
public class FilmController {

    private final FilmService filmService;

    public FilmController(FilmService filmService) {
        this.filmService = filmService;
    }

    /** 注册一部计划包含固定片段数的馆藏影片。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerFilm(@Valid @RequestBody RegisterFilmRequest request) {
        long id = filmService.registerFilm(request.code(), request.plannedSegments());
        return Map.of("id", id, "code", request.code(),
                "plannedSegments", request.plannedSegments());
    }

    /** 提交某片段的一次修复工作版。 */
    @PostMapping("/{filmCode}/segments")
    @ResponseStatus(HttpStatus.CREATED)
    public SegmentSubmitResponse submitSegment(@PathVariable String filmCode,
                                               @Valid @RequestBody SegmentSubmitRequest request) {
        return filmService.submitSegment(filmCode, request);
    }

    /** 整片审核；所有计划片段齐全后固定为不可变母版。 */
    @PostMapping("/{filmCode}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewResponse review(@PathVariable String filmCode,
                                 @Valid @RequestBody ReviewRequest request) {
        return filmService.review(filmCode, request);
    }

    /** 撤销母版；只阻止新交付，既有回执保留。 */
    @PostMapping("/masters/{masterNo}/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String masterNo) {
        filmService.revoke(masterNo);
    }

    /** 按不可变母版编号发起交付；重试返回原回执。 */
    @PostMapping("/deliveries")
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryResponse deliver(@Valid @RequestBody DeliveryRequest request) {
        return filmService.deliver(request);
    }

    /** 查询片段版次链、母版引用与交付回执。 */
    @GetMapping("/{filmCode}")
    public FilmView getFilm(@PathVariable String filmCode) {
        return filmService.getFilm(filmCode);
    }
}
