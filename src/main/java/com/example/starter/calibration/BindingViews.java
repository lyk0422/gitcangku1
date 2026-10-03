package com.example.starter.calibration;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 绑定视图装配：补齐任务编号与天线编号。
 */
@Component
public class BindingViews {

    private final TaskRepository taskRepository;
    private final AntennaRepository antennaRepository;

    public BindingViews(TaskRepository taskRepository, AntennaRepository antennaRepository) {
        this.taskRepository = taskRepository;
        this.antennaRepository = antennaRepository;
    }

    /** 装配单条绑定视图。 */
    public BindingView of(TaskBinding binding) {
        String taskCode = taskRepository.findById(binding.taskId())
                .map(ReceiveTask::taskCode)
                .orElseThrow(() -> ApiException.conflict("DATA_INCONSISTENT",
                        "绑定引用的任务不存在: " + binding.taskId()));
        String antennaCode = antennaRepository.findCodeById(binding.antennaId())
                .orElseThrow(() -> ApiException.conflict("DATA_INCONSISTENT",
                        "绑定引用的天线不存在: " + binding.antennaId()));
        return ViewAssembler.toBindingView(binding, taskCode, antennaCode);
    }

    /** 装配绑定视图列表。 */
    public List<BindingView> ofAll(List<TaskBinding> bindings) {
        List<BindingView> views = new ArrayList<>(bindings.size());
        for (TaskBinding binding : bindings) {
            views.add(of(binding));
        }
        return views;
    }
}