package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AnalysisView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AnalysisRepository;
import com.example.starter.blind.repo.AnalysisRepository.AnalysisRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.ResultRepository;
import com.example.starter.blind.repo.ResultRepository.ResultRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 受限置换分析业务：
 * 实验关闭后，REVIEWER 引用 4~8 个不同未退组参与者与固定结果版本；
 * 分组取自库内盲底映射（调用方不给分组），观察统计量为两组均值差的绝对值；
 * 枚举相同 A 人数的全部分组，以整数交叉相乘比较，输出精确 k/n 与匿名人数。
 * 成功分析连同完整内部依据冻结落库；外部响应仅含统计量，失败不留统计。
 */
@Service
public class AnalysisService {

    static final int MIN_PARTICIPANTS = 4;
    static final int MAX_PARTICIPANTS = 8;
    static final int MIN_PER_GROUP = 2;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ResultRepository resultRepository;
    private final AnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnalysisService(ExperimentRepository experimentRepository,
                           AllocationRepository allocationRepository,
                           ResultRepository resultRepository,
                           AnalysisRepository analysisRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.resultRepository = resultRepository;
        this.analysisRepository = analysisRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** 单名参与者的内部依据：处理代码与冻结版本取值，仅库内使用，禁止透出。 */
    private record BasisEntry(String participantId, String treatment, int value) {
    }

    /**
     * 执行精确置换分析并冻结落库。实验行级锁使分析与关闭、结果提交、退组串行。
     */
    @Transactional
    public AnalysisView analyze(String experimentId, List<String> participantIds,
                                Integer version, String actorId) {
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (!"CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验未关闭，不能执行置换分析");
        }
        if (version == null || version < 1) {
            throw ApiException.unprocessable("结果版本必须为不小于 1 的整数");
        }
        List<String> referenced = validateParticipants(participantIds);

        // 汇总内部依据：分组取库内映射，结果取冻结版本；任何缺值 422。
        List<BasisEntry> basis = new ArrayList<>(referenced.size());
        for (String pid : referenced) {
            AllocationRow allocation =
                    allocationRepository.findByExperimentAndParticipant(experimentId, pid);
            if (allocation == null) {
                throw ApiException.unprocessable("引用的参与者未在该实验登记: " + pid);
            }
            if ("WITHDRAWN".equals(allocation.status())) {
                throw ApiException.unprocessable("引用的参与者已退组: " + pid);
            }
            SeatRow seat = experimentRepository.findSeat(
                    experimentId, allocation.blockNo(), allocation.seatNo());
            if (seat == null) {
                throw new IllegalStateException("席位映射缺失，数据不一致");
            }
            ResultRow result = resultRepository.findByVersion(experimentId, pid, version);
            if (result == null) {
                throw ApiException.unprocessable("引用的参与者在版本 " + version + " 缺少结果值");
            }
            basis.add(new BasisEntry(pid, seat.treatment(), result.value()));
        }

        int groupACount = (int) basis.stream().filter(e -> "A".equals(e.treatment())).count();
        int groupBCount = basis.size() - groupACount;
        if (groupACount < MIN_PER_GROUP || groupBCount < MIN_PER_GROUP) {
            throw ApiException.unprocessable("引用集合中每个处理组人数均须至少 " + MIN_PER_GROUP + " 人");
        }

        long[] values = basis.stream().mapToLong(BasisEntry::value).toArray();
        long totalSum = basis.stream().mapToLong(BasisEntry::value).sum();
        long sumA = basis.stream().filter(e -> "A".equals(e.treatment()))
                .mapToLong(BasisEntry::value).sum();
        // 观察统计量分子：|meanA - meanB| = |sumA*countB - sumB*countA| / (countA*countB)，
        // 各组合分母相同，仅比较分子即可，全程整数运算，无浮点比较。
        long observedNum = Math.abs(sumA * groupBCount - (totalSum - sumA) * groupACount);
        long[] counts = enumerate(values, groupACount, groupBCount, totalSum, observedNum);
        long k = counts[0];
        long n = counts[1];

        String analysisId = "AN-" + UUID.randomUUID().toString().replace("-", "");
        long now = clock.nowMillis();
        analysisRepository.insert(new AnalysisRow(analysisId, experimentId, version,
                String.join(",", referenced), groupACount, groupBCount, k, n, observedNum,
                toBasisJson(basis), actorId, now));
        return new AnalysisView(analysisId, experimentId, version, basis.size(),
                groupACount, groupBCount, k, n, k + "/" + n, now);
    }

    /** 校验并返回字典序排序后的引用列表；人数、重复、空值不符一律 422。 */
    private List<String> validateParticipants(List<String> participantIds) {
        if (participantIds == null || participantIds.isEmpty()) {
            throw ApiException.unprocessable("参与者列表不能为空");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String pid : participantIds) {
            if (pid == null || pid.isBlank()) {
                throw ApiException.unprocessable("参与者编号不能为空");
            }
            if (!distinct.add(pid)) {
                throw ApiException.unprocessable("参与者不得重复: " + pid);
            }
        }
        if (distinct.size() < MIN_PARTICIPANTS || distinct.size() > MAX_PARTICIPANTS) {
            throw ApiException.unprocessable(
                    "分析须引用 " + MIN_PARTICIPANTS + " 至 " + MAX_PARTICIPANTS + " 个不同参与者");
        }
        return distinct.stream().sorted().toList();
    }

    /**
     * 枚举 values 中选出 countA 个作为“A组”的全部组合；
     * 返回 {k, n}：统计量分子大于等于观察分子的组合数 k 与总组合数 n。
     */
    private long[] enumerate(long[] values, int countA, int countB, long totalSum,
                             long observedNum) {
        long[] counts = new long[2];
        combine(values, 0, countA, countA, countB, 0L, totalSum, observedNum, counts);
        return counts;
    }

    private void combine(long[] values, int index, int remaining, int countA, int countB,
                         long chosenSum, long totalSum, long observedNum, long[] counts) {
        if (remaining == 0) {
            counts[1]++;
            long num = Math.abs(chosenSum * countB - (totalSum - chosenSum) * countA);
            if (num >= observedNum) {
                counts[0]++;
            }
            return;
        }
        for (int i = index; i <= values.length - remaining; i++) {
            combine(values, i + 1, remaining - 1, countA, countB,
                    chosenSum + values[i], totalSum, observedNum, counts);
        }
    }

    private String toBasisJson(List<BasisEntry> basis) {
        try {
            return objectMapper.writeValueAsString(basis);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("内部依据无法序列化", e);
        }
    }
}
