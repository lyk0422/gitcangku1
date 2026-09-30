package com.example.starter.film.service;

import com.example.starter.film.exception.NotFoundException;
import com.example.starter.film.model.DeliveryReceipt;
import com.example.starter.film.model.Film;
import com.example.starter.film.model.Master;
import com.example.starter.film.model.MasterSegment;
import com.example.starter.film.model.ReviewRequest;
import com.example.starter.film.model.ReviewSegmentBaseline;
import com.example.starter.film.model.SegmentVersion;
import com.example.starter.film.repo.DeliveryRepository;
import com.example.starter.film.repo.FilmRepository;
import com.example.starter.film.repo.MasterRepository;
import com.example.starter.film.repo.ReviewRepository;
import com.example.starter.film.repo.SegmentVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 只读查询：片段版次链、母版引用与交付回执聚合展示。
 */
@Service
public class FilmQueryService {

    private final FilmRepository films;
    private final SegmentVersionRepository segments;
    private final ReviewRepository reviews;
    private final MasterRepository masters;
    private final DeliveryRepository deliveries;

    public FilmQueryService(FilmRepository films, SegmentVersionRepository segments,
                            ReviewRepository reviews, MasterRepository masters,
                            DeliveryRepository deliveries) {
        this.films = films;
        this.segments = segments;
        this.reviews = reviews;
        this.masters = masters;
        this.deliveries = deliveries;
    }

    /**
     * 影片完整版本视图。
     */
    @Transactional(readOnly = true)
    public FilmDetail getDetail(long filmId) {
        Film film = films.findById(filmId)
                .orElseThrow(() -> new NotFoundException("影片不存在: " + filmId));
        List<SegmentVersion> chain = segments.findChainByFilm(filmId);
        List<ReviewRequest> reviewList = reviews.findByFilm(filmId);
        List<Master> masterList = masters.findByFilm(filmId);
        List<DeliveryReceipt> receiptList = deliveries.findByFilm(filmId);

        List<ReviewView> reviewViews = reviewList.stream()
                .map(r -> new ReviewView(r, reviews.findBaselines(r.id()))).toList();
        List<MasterView> masterViews = masterList.stream()
                .map(m -> new MasterView(m, masters.findSegments(m.id()))).toList();
        return new FilmDetail(film, chain, reviewViews, masterViews, receiptList);
    }

    /**
     * 审核视图：审核状态及其固定基线。
     */
    public record ReviewView(ReviewRequest review, List<ReviewSegmentBaseline> baselines) {
    }

    /**
     * 母版视图：母版编号及不可变片段快照。
     */
    public record MasterView(Master master, List<MasterSegment> segments) {
    }

    /**
     * 影片聚合：影片、版次链、审核（含基线）、母版（含快照）、交付回执。
     */
    public record FilmDetail(
            Film film,
            List<SegmentVersion> segmentChain,
            List<ReviewView> reviews,
            List<MasterView> masters,
            List<DeliveryReceipt> deliveries
    ) {
    }
}
