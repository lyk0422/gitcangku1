package com.example.starter.film.repo;

import com.example.starter.film.domain.DeliveryReceipt;
import com.example.starter.film.domain.Film;
import com.example.starter.film.domain.Master;
import com.example.starter.film.domain.MasterSegment;
import com.example.starter.film.domain.SegmentVersion;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 影片修复业务的持久化端口；所有写操作必须在调用方事务内执行。
 */
public interface FilmRepository {

    /**
     * 新建影片。
     */
    long createFilm(String code, int plannedSegments, Instant now);

    Optional<Film> findFilmByCode(String code);

    Optional<Film> findFilmById(long filmId);

    /**
     * 行级锁定影片（SELECT ... FOR UPDATE）；提交与审核借此按影片串行化。
     */
    void lockFilm(long filmId);

    /**
     * 按幂等请求号查询既有的片段提交。
     */
    Optional<SegmentVersion> findSegmentRequest(long filmId, String requestId);

    /**
     * 查询指定片段的最新版次；不存在返回空。
     */
    Optional<SegmentVersion> findLatestSegment(long filmId, int segmentNo);

    /**
     * 追加一条片段版本记录，返回新主键。
     */
    long insertSegmentVersion(long filmId, int segmentNo, int versionNo, String contentDigest,
                              Integer basedOnVersion, String requestId, Instant now);

    /**
     * 查询影片全部片段版本链，按（片段序号, 版次）升序。
     */
    List<SegmentVersion> findSegmentChain(long filmId);

    /**
     * 查询每个片段的最新版次，按片段序号升序。
     */
    List<SegmentVersion> findLatestVersions(long filmId);

    /**
     * 按审核请求号查询既有母版。
     */
    Optional<Master> findMasterByReviewRequest(long filmId, String requestId);

    /**
     * 插入母版主记录，返回新主键。
     */
    long insertMaster(long filmId, String masterNo, String reviewRequestId, Instant now);

    /**
     * 插入母版固定片段引用。
     */
    void insertMasterSegment(long masterId, int segmentNo, int versionNo, String contentDigest);

    Optional<Master> findMasterByNo(String masterNo);

    List<MasterSegment> findMasterSegments(long masterId);

    List<Master> findMasters(long filmId);

    /**
     * 撤销母版。
     */
    void revokeMaster(long masterId, Instant now);

    /**
     * 按交付幂等请求号查询既有回执。
     */
    Optional<DeliveryReceipt> findDeliveryRequest(long filmId, String requestId);

    /**
     * 插入交付回执，返回新主键。
     */
    long insertReceipt(long filmId, String receiptNo, String masterNo, String requestId,
                       String snapshotJson, Instant now);

    List<DeliveryReceipt> findReceipts(long filmId);
}
