package com.example.starter.film.service;

import com.example.starter.film.api.DeliveryRequest;
import com.example.starter.film.api.DeliveryResponse;
import com.example.starter.film.api.FilmView;
import com.example.starter.film.api.ReviewRequest;
import com.example.starter.film.api.ReviewResponse;
import com.example.starter.film.api.SegmentSubmitRequest;
import com.example.starter.film.api.SegmentSubmitResponse;

/**
 * 影片修复业务入口：片段提交、整片审核、审核撤销、交付冻结与查询。
 */
public interface FilmService {

    /**
     * 注册一部计划包含指定片段数的馆藏影片。
     */
    long registerFilm(String code, int plannedSegments);

    SegmentSubmitResponse submitSegment(String filmCode, SegmentSubmitRequest request);

    ReviewResponse review(String filmCode, ReviewRequest request);

    /**
     * 撤销母版；只阻止之后的新交付，不影响既有交付回执。
     */
    void revoke(String masterNo);

    DeliveryResponse deliver(DeliveryRequest request);

    FilmView getFilm(String filmCode);
}
