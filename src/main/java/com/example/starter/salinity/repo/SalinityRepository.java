package com.example.starter.salinity.repo;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.starter.salinity.po.ProfileErratum;
import com.example.starter.salinity.po.SalinitySample;
import com.example.starter.salinity.po.SealedProfilePoint;
import com.example.starter.salinity.po.Voyage;

/**
 * 盐度采样持久化端口。
 */
public interface SalinityRepository {

    /** 不存在则注册航次（OPEN）；已存在不做改动。 */
    void registerVoyageIfAbsent(String voyageCode, Instant now);

    /** 按编号查航次，不存在返回空。 */
    Optional<Voyage> findVoyage(String voyageCode);

    /** 插入一条原始样本，返回带主键的样本；采样编号/请求 ID 唯一冲突时抛出底层异常。 */
    SalinitySample insertSample(SalinitySample sample);

    /** 按采样编号查样本。 */
    Optional<SalinitySample> findSampleByNo(String sampleNo);

    /** 按请求 ID 查样本（幂等重放）。 */
    Optional<SalinitySample> findSampleByRequestId(String requestId);

    /** 查航次下全部样本，按主键升序（即入库先后）。 */
    List<SalinitySample> findSamples(String voyageCode);

    /**
     * 以行锁锁定航次行并返回；不存在返回空。采纳与封存事务内首先调用，统一加锁顺序。
     */
    Optional<Voyage> lockVoyage(String voyageCode);

    /** 将航次标记为 SEALED 并记录封存时刻。 */
    void markSealed(String voyageCode, Instant sealedUtc);

    /**
     * 锁定某站位深度的全部样本行（含当前采纳行），返回这些行；与封存快照的行锁互斥。
     */
    List<SalinitySample> lockPosition(String voyageCode, String stationCode, int depthM);

    /** 将站位深度当前采纳版本降级为非采纳，并登记由指定重测样本替换。 */
    void demoteAdopted(String voyageCode, String stationCode, int depthM, Long replacementId);

    /** 将目标样本升级为采纳版本。 */
    void markAdopted(Long targetId);

    /** 查站位深度当前采纳样本。 */
    Optional<SalinitySample> findAdopted(String voyageCode, String stationCode, int depthM);

    /**
     * 封存一致性快照：对本航次当前全部采纳样本行加锁并返回，按站位、深度升序。
     * 与 {@link #lockPosition} 拿到的行锁互斥，保证封存采用同一版本集合，
     * 不会混入封存之后才获采纳的数据。
     */
    List<SalinitySample> lockAndLoadAdoptedSnapshot(String voyageCode);

    /** 批量写入冻结剖面点（同一事务）。 */
    void insertProfilePoints(List<SealedProfilePoint> points);

    /** 查冻结剖面点，按站位、深度升序。 */
    List<SealedProfilePoint> findProfilePoints(String voyageCode);

    /** 追加勘误，返回带主键的记录。 */
    ProfileErratum insertErratum(ProfileErratum erratum);

    /** 查航次全部勘误，按主键升序。 */
    List<ProfileErratum> findErrata(String voyageCode);
}
