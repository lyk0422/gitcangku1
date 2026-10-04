package com.example.starter.cue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 提示点编排版本服务：创建新版本、校验冻结、版本差异。 */
@Service
public class CueService {

    static final int MIN_DURATION_SECONDS = 1;
    static final int MAX_DURATION_SECONDS = 600;

    private final CueRepository cueRepository;

    public CueService(CueRepository cueRepository) {
        this.cueRepository = cueRepository;
    }

    /** 每次编排修改产生一个新的草稿版本，原版本不变。 */
    @Transactional
    public long createVersion(CueDtos.CreateVersionRequest request) {
        if (request == null || request.cues() == null) {
            throw new ConflictException("提示点列表不能为空");
        }
        long versionId = cueRepository.insertVersion();
        for (CueDtos.CueInput cue : request.cues()) {
            if (cue.cueType() == null || cue.triggerPhrase() == null || cue.triggerPhrase().isBlank()) {
                throw new ConflictException("提示点类型与触发口令不能为空");
            }
            cueRepository.insertItem(versionId, cue);
        }
        return versionId;
    }

    /** 冻结版本：全部提示点校验通过才冻结，任何无效时长或重复序号都使整个版本不能冻结。 */
    @Transactional
    public CueVersion freeze(long versionId) {
        CueVersion version = requireVersion(versionId);
        if (version.status() == VersionStatus.FROZEN) {
            return version;
        }
        validateItems(cueRepository.findItems(versionId));
        cueRepository.markFrozen(versionId);
        return requireVersion(versionId);
    }

    /** 版本差异：按序号对比新增、删除与变更的提示点。 */
    @Transactional(readOnly = true)
    public CueDtos.VersionDiff diff(long fromVersionId, long toVersionId) {
        requireVersion(fromVersionId);
        requireVersion(toVersionId);
        Map<Integer, CueItem> from = bySeq(cueRepository.findItems(fromVersionId));
        Map<Integer, CueItem> to = bySeq(cueRepository.findItems(toVersionId));
        List<CueItem> added = new ArrayList<>();
        List<CueItem> removed = new ArrayList<>();
        List<CueDtos.CueChange> changed = new ArrayList<>();
        for (CueItem item : to.values()) {
            CueItem old = from.get(item.seq());
            if (old == null) {
                added.add(item);
            } else if (!sameCue(old, item)) {
                changed.add(new CueDtos.CueChange(item.seq(), old, item));
            }
        }
        for (CueItem item : from.values()) {
            if (!to.containsKey(item.seq())) {
                removed.add(item);
            }
        }
        return new CueDtos.VersionDiff(fromVersionId, toVersionId, added, removed, changed);
    }

    /** 最新版本ID，用于未开始场次重新绑定。 */
    public java.util.Optional<Long> latestVersionId() {
        return cueRepository.findLatestVersionId();
    }

    public CueVersion requireVersion(long versionId) {
        return cueRepository.findVersion(versionId)
                .orElseThrow(() -> new NotFoundException("版本不存在: " + versionId));
    }

    /** 读取版本提示点并整体校验，绑定或开始前排练必须全部有效。 */
    public List<CueItem> validatedItems(long versionId) {
        List<CueItem> items = cueRepository.findItems(versionId);
        validateItems(items);
        return items;
    }

    /** 整体校验：任一提示点时长越界或序号重复都抛出冲突，不允许只丢弃坏提示点。 */
    static void validateItems(List<CueItem> items) {
        List<String> problems = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (CueItem item : items) {
            if (item.durationSeconds() < MIN_DURATION_SECONDS
                    || item.durationSeconds() > MAX_DURATION_SECONDS) {
                problems.add("序号" + item.seq() + "持续秒数" + item.durationSeconds()
                        + "超出" + MIN_DURATION_SECONDS + "至" + MAX_DURATION_SECONDS + "范围");
            }
            if (!seen.add(item.seq())) {
                problems.add("序号" + item.seq() + "重复");
            }
        }
        if (!problems.isEmpty()) {
            throw new ConflictException("版本校验失败: " + String.join("; ", problems));
        }
    }

    private static Map<Integer, CueItem> bySeq(List<CueItem> items) {
        Map<Integer, CueItem> map = new HashMap<>();
        for (CueItem item : items) {
            map.putIfAbsent(item.seq(), item);
        }
        return map;
    }

    private static boolean sameCue(CueItem a, CueItem b) {
        return a.cueType() == b.cueType()
                && a.durationSeconds() == b.durationSeconds()
                && a.triggerPhrase().equals(b.triggerPhrase());
    }
}