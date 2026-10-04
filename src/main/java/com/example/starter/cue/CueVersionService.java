package com.example.starter.cue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.common.BusinessException;
import com.example.starter.cue.CueDtos.CueChange;
import com.example.starter.cue.CueDtos.CueInput;
import com.example.starter.cue.CueDtos.CueView;
import com.example.starter.cue.CueDtos.DiffView;
import com.example.starter.cue.CueDtos.VersionView;
import com.example.starter.cue.CueVersionRepository.VersionRow;

/**
 * 提示点编排：版本创建、冻结校验与版本差异查询。
 * 编排修改只产生新版本；任何无效时长或重复序号使整个版本不能冻结。
 */
@Service
public class CueVersionService {

    static final int MIN_DURATION_SECONDS = 1;
    static final int MAX_DURATION_SECONDS = 600;

    private final CueVersionRepository repository;

    public CueVersionService(CueVersionRepository repository) {
        this.repository = repository;
    }

    /** 创建草稿版本：仅做非空校验，时长与序号合法性留待冻结时统一判定。 */
    @Transactional
    public VersionView createVersion(String playCode, List<CueInput> cues) {
        int versionNo = repository.nextVersionNo(playCode);
        long id = repository.insertVersion(playCode, versionNo, Instant.now());
        for (CueInput cue : cues) {
            repository.insertItem(id, cue);
        }
        return getVersion(id);
    }

    /** 冻结版本：全部提示点合法才置为FROZEN，任一问题都使整体不能冻结。 */
    @Transactional
    public VersionView freeze(long versionId) {
        VersionRow row = repository.findVersion(versionId)
                .orElseThrow(() -> BusinessException.notFound("编排版本不存在: " + versionId));
        if ("FROZEN".equals(row.status())) {
            return toView(row);
        }
        List<String> problems = validateAll(repository.findItems(versionId));
        if (!problems.isEmpty()) {
            throw BusinessException.unprocessable("版本不能冻结: " + String.join("; ", problems));
        }
        repository.markFrozen(versionId, Instant.now());
        return getVersion(versionId);
    }

    public VersionView getVersion(long versionId) {
        VersionRow row = repository.findVersion(versionId)
                .orElseThrow(() -> BusinessException.notFound("编排版本不存在: " + versionId));
        return toView(row);
    }

    /** 版本差异：按序号对齐，报告新增、移除与修改。 */
    public DiffView diff(long fromVersionId, long toVersionId) {
        repository.findVersion(fromVersionId)
                .orElseThrow(() -> BusinessException.notFound("编排版本不存在: " + fromVersionId));
        repository.findVersion(toVersionId)
                .orElseThrow(() -> BusinessException.notFound("编排版本不存在: " + toVersionId));
        Map<Integer, CueView> from = bySeq(repository.findItems(fromVersionId));
        Map<Integer, CueView> to = bySeq(repository.findItems(toVersionId));
        List<CueView> added = new ArrayList<>();
        List<CueView> removed = new ArrayList<>();
        List<CueChange> modified = new ArrayList<>();
        for (Map.Entry<Integer, CueView> e : to.entrySet()) {
            CueView old = from.get(e.getKey());
            if (old == null) {
                added.add(e.getValue());
            } else if (!old.equals(e.getValue())) {
                modified.add(new CueChange(e.getKey(), old, e.getValue()));
            }
        }
        for (Map.Entry<Integer, CueView> e : from.entrySet()) {
            if (!to.containsKey(e.getKey())) {
                removed.add(e.getValue());
            }
        }
        return new DiffView(fromVersionId, toVersionId, added, removed, modified);
    }

    /** 校验全部提示点，返回问题列表；空列表表示可冻结。 */
    public List<String> validateAll(List<CueView> cues) {
        List<String> problems = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CueView cue : cues) {
            if (!seen.add(cue.seqNo())) {
                problems.add("序号重复: " + cue.seqNo());
            }
            if (cue.durationSeconds() < MIN_DURATION_SECONDS
                    || cue.durationSeconds() > MAX_DURATION_SECONDS) {
                problems.add("序号 " + cue.seqNo() + " 持续秒数超出范围 "
                        + MIN_DURATION_SECONDS + "-" + MAX_DURATION_SECONDS + ": "
                        + cue.durationSeconds());
            }
        }
        if (cues.isEmpty()) {
            problems.add("版本不包含任何提示点");
        }
        return problems;
    }

    private VersionView toView(VersionRow row) {
        return new VersionView(row.id(), row.playCode(), row.versionNo(), row.status(),
                row.createdAt(), row.frozenAt(), repository.findItems(row.id()));
    }

    private static Map<Integer, CueView> bySeq(List<CueView> cues) {
        Map<Integer, CueView> map = new HashMap<>();
        for (CueView cue : cues) {
            map.put(cue.seqNo(), cue);
        }
        return map;
    }
}