package com.example.starter.plan.service;

import com.example.starter.plan.model.Occupancy;
import com.example.starter.plan.model.SectionAxleLimit;
import com.example.starter.plan.model.TrainAxleProfile;
import com.example.starter.plan.repo.AxleProfileRepository;
import com.example.starter.plan.web.ApiException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 车轴局部集中承载核验：单轴上限与任意连续 3 轴滑窗总重上限。
 *
 * <p>核验规则：列车档案与区段档案均缺失的占用不参与核验（未登记区段/列车）；
 * 仅一方缺失视为档案缺失，抛 404；双方齐全则逐轴、逐三轴滑窗核验，
 * 越限明细携带区段与轴序号。所有总和以 BigInteger 精确计算，不做 long 溢出近似。
 *
 * <p>发布/改签通过本服务核验候选计划全部轴窗并冻结当时快照；
 * 档案更新通过本服务核验全部已发布计划在同一完整后态下仍合法，否则 409。
 */
@Service
public class AxleEnvelopeService {

    private final AxleProfileRepository axleRepo;

    public AxleEnvelopeService(AxleProfileRepository axleRepo) {
        this.axleRepo = axleRepo;
    }

    /**
     * 核验候选计划全部占用的轴限。档案缺失抛 404；返回越限明细（为空表示合法）。
     */
    public List<Map<String, Object>> validateOccupancies(List<Occupancy> occupancies) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Occupancy o : occupancies) {
            Optional<TrainAxleProfile> train = axleRepo.findTrainProfile(o.trainNo());
            Optional<SectionAxleLimit> section = axleRepo.findSectionLimit(o.sectionId());
            if (train.isEmpty() && section.isEmpty()) {
                continue;
            }
            if (train.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRAIN_PROFILE_NOT_FOUND",
                        "列车轴重档案缺失: " + o.trainNo());
            }
            if (section.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "SECTION_LIMIT_NOT_FOUND",
                        "区段轴限档案缺失: " + o.sectionId());
            }
            violations.addAll(checkEnvelope(o.trainNo(), train.get().axleWeightsKg(),
                    o.sectionId(), section.get(), null));
        }
        return violations;
    }

    /**
     * 冻结发布时快照：候选计划涉及列车的当时轴序列与涉及区段的当时限值、版本。
     * 未登记档案的列车/区段无快照。同一列车/区段在同一计划内只冻结一次。
     */
    public void freezeSnapshot(long planId, List<Occupancy> occupancies) {
        Set<String> trains = new LinkedHashSet<>();
        Set<String> sections = new LinkedHashSet<>();
        for (Occupancy o : occupancies) {
            trains.add(o.trainNo());
            sections.add(o.sectionId());
        }
        for (String trainNo : trains) {
            axleRepo.findTrainProfile(trainNo).ifPresent(p ->
                    axleRepo.insertFrozenAxles(planId, trainNo, p.version(), p.axleWeightsKg()));
        }
        for (String sectionId : sections) {
            axleRepo.findSectionLimit(sectionId).ifPresent(l ->
                    axleRepo.insertFrozenSection(planId, sectionId, l.version(),
                            l.singleAxleLimitKg(), l.window3LimitKg()));
        }
    }

    /**
     * 核验列车档案更新后的完整后态：所有已发布计划在该列车新轴序列与
     * 当前区段限值下仍合法，否则抛 409（版本不变，需先取消相关计划）。
     */
    public void assertTrainProfileUpdateLegal(String trainNo, List<Long> newWeightsKg) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (AxleProfileRepository.PublishedRef ref : axleRepo.findPublishedRefsByTrain(trainNo)) {
            Optional<SectionAxleLimit> section = axleRepo.findSectionLimit(ref.sectionId());
            if (section.isEmpty()) {
                continue;
            }
            violations.addAll(checkEnvelope(trainNo, newWeightsKg, ref.sectionId(),
                    section.get(), ref.scheduleKey()));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFILE_UPDATE_CONFLICT",
                    "更新列车轴重档案将使已发布计划非法，需先取消相关计划: " + trainNo, violations);
        }
    }

    /**
     * 核验区段档案更新后的完整后态：所有已发布计划在该区段新限值与
     * 当前列车轴序列下仍合法，否则抛 409（版本不变，需先取消相关计划）。
     */
    public void assertSectionLimitUpdateLegal(String sectionId, long newSingleAxleLimitKg,
                                              long newWindow3LimitKg) {
        List<Map<String, Object>> violations = new ArrayList<>();
        SectionAxleLimit candidate = new SectionAxleLimit(sectionId, 0,
                newSingleAxleLimitKg, newWindow3LimitKg);
        for (AxleProfileRepository.PublishedRef ref
                : axleRepo.findPublishedRefsBySection(sectionId)) {
            Optional<TrainAxleProfile> train = axleRepo.findTrainProfile(ref.trainNo());
            if (train.isEmpty()) {
                continue;
            }
            violations.addAll(checkEnvelope(ref.trainNo(), train.get().axleWeightsKg(),
                    sectionId, candidate, ref.scheduleKey()));
        }
        if (!violations.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PROFILE_UPDATE_CONFLICT",
                    "更新区段轴限档案将使已发布计划非法，需先取消相关计划: " + sectionId, violations);
        }
    }

    /**
     * 对单条占用执行轴限核验：逐轴检查单轴上限；轴数不少于 3 时逐三轴滑窗检查总重上限。
     * 总和以 BigInteger 精确累加，不发生 long 溢出后放行。
     *
     * @param scheduleKey 已发布计划核验场景下的计划业务键；候选计划核验传 null
     */
    static List<Map<String, Object>> checkEnvelope(String trainNo, List<Long> axleWeightsKg,
                                                   String sectionId, SectionAxleLimit limit,
                                                   String scheduleKey) {
        List<Map<String, Object>> violations = new ArrayList<>();
        BigInteger singleLimit = BigInteger.valueOf(limit.singleAxleLimitKg());
        for (int i = 0; i < axleWeightsKg.size(); i++) {
            BigInteger weight = BigInteger.valueOf(axleWeightsKg.get(i));
            if (weight.compareTo(singleLimit) > 0) {
                Map<String, Object> detail = baseDetail("SINGLE_AXLE", trainNo, sectionId,
                        scheduleKey);
                detail.put("axleIndex", i);
                detail.put("actualKg", weight);
                detail.put("limitKg", limit.singleAxleLimitKg());
                violations.add(detail);
            }
        }
        if (axleWeightsKg.size() >= 3) {
            BigInteger windowLimit = BigInteger.valueOf(limit.window3LimitKg());
            for (int i = 0; i + 3 <= axleWeightsKg.size(); i++) {
                BigInteger sum = BigInteger.ZERO;
                for (int j = i; j < i + 3; j++) {
                    sum = sum.add(BigInteger.valueOf(axleWeightsKg.get(j)));
                }
                if (sum.compareTo(windowLimit) > 0) {
                    Map<String, Object> detail = baseDetail("WINDOW_3", trainNo, sectionId,
                            scheduleKey);
                    detail.put("windowStartIndex", i);
                    detail.put("actualKg", sum);
                    detail.put("limitKg", limit.window3LimitKg());
                    violations.add(detail);
                }
            }
        }
        return violations;
    }

    private static Map<String, Object> baseDetail(String type, String trainNo, String sectionId,
                                                  String scheduleKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type);
        detail.put("trainNo", trainNo);
        detail.put("sectionId", sectionId);
        if (scheduleKey != null) {
            detail.put("scheduleKey", scheduleKey);
        }
        return detail;
    }
}
