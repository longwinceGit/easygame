package com.template.app.game.parking.ui;

import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.template.app.R;
import com.template.app.databinding.FragmentParkingBinding;
import com.template.app.util.SoundManager;
import com.template.app.game.parking.engine.MoveResult;
import com.template.app.game.parking.engine.ParkingConfig;
import com.template.app.game.parking.engine.ParkingEngine;
import com.template.app.game.parking.engine.BoardStep;
import com.template.app.game.parking.model.Level;

import java.util.List;
import com.template.app.game.parking.model.PassengerGroup;
import com.template.app.game.parking.view.ParkingView;

/**
 * 挪车消消消游戏页。
 * <p>
 * 职责边界：只做「把引擎状态渲染到控件」和「把 View 的手势转成 ViewModel 命令」，
 * 游戏规则一律不在这里（ADR-004）。
 */
public class ParkingFragment extends Fragment {

    private FragmentParkingBinding binding;
    private ParkingViewModel viewModel;
    private SoundManager soundManager;
    private String[] colorNames;

    /**
     * 历史最高分（由 {@code getBest()} 的观察者持续刷新）。
     * <p>
     * 这个字段<b>不能省</b>：Room 的 {@code LiveData} 只有在存在活跃观察者时才真正查询数据库，
     * 没有观察者就从不计算、{@code getValue()} 恒为 null。弹窗里临时读一次是读不到数的，
     * 必须靠这里的常驻观察者把值养出来。
     */
    private int historicalBest;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentParkingBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(ParkingViewModel.class);
        colorNames = getResources().getStringArray(R.array.parking_color_names);
        soundManager = SoundManager.getInstance(requireContext());

        binding.parkingView.attach(viewModel.getEngine());
        binding.parkingView.setListener(new ParkingView.Listener() {
            @Override
            public void onMoveRequested(int vehicleId, int delta) {
                viewModel.move(vehicleId, delta);
            }

            @Override
            public void onTapRequested(int vehicleId) {
                viewModel.tap(vehicleId);
            }

            @Override
            public void onRemoveRequested(int vehicleId) {
                viewModel.removeVehicle(vehicleId);
            }
        });

        binding.btnBack.setOnClickListener(v -> {
            soundManager.click();
            navigateUp();
        });
        binding.btnSort.setOnClickListener(v -> {
            if (binding.parkingView.isBusy()) {
                return;
            }
            soundManager.click();
            viewModel.sortQueue();
        });
        binding.btnRefresh.setOnClickListener(v -> {
            if (binding.parkingView.isBusy()) {
                return;
            }
            soundManager.click();
            viewModel.refreshLevel();
        });
        binding.btnRemove.setOnClickListener(v -> {
            soundManager.click();
            viewModel.toggleRemoveMode();
        });

        renderHint();

        viewModel.getScore().observe(getViewLifecycleOwner(), this::renderScore);
        // 历史最高分必须常驻观察：Room 的 LiveData 只在有活跃观察者时才查询数据库，
        // 不注册的话 getValue() 恒为 null，通关提示里的"历史最高"会错成"累计分数"。
        viewModel.getBest().observe(getViewLifecycleOwner(), this::renderBest);
        viewModel.getLevel().observe(getViewLifecycleOwner(), this::renderLevel);
        viewModel.getRemovesLeft().observe(getViewLifecycleOwner(), this::renderRemoveButton);
        viewModel.getSortsLeft().observe(getViewLifecycleOwner(), this::renderSortButton);
        viewModel.getCanUndo().observe(getViewLifecycleOwner(), ignored -> renderControls());
        viewModel.getLoading().observe(getViewLifecycleOwner(), this::renderLoading);
        viewModel.getRemoveMode().observe(getViewLifecycleOwner(), ignored -> {
            binding.parkingView.setRemoveMode(Boolean.TRUE.equals(
                viewModel.getRemoveMode().getValue()));
            renderHint();
            renderControls();
        });
        viewModel.getState().observe(getViewLifecycleOwner(), ignored -> renderControls());
        viewModel.getRevision().observe(getViewLifecycleOwner(), ignored -> {
            binding.parkingView.invalidate();
            updateBoardDescription();
        });

        viewModel.getLevelEvent().observe(getViewLifecycleOwner(), this::onLevelReady);
        viewModel.getMoveEvent().observe(getViewLifecycleOwner(), this::onMoved);
        viewModel.getMessageEvent().observe(getViewLifecycleOwner(), this::onMessage);
        // boardEvent 必须先于 finishEvent 注册：移除 / 排序道具触发的离场动画要
        // 先开始播放，finishEvent 处理时 isBusy() 才能正确反映"动画还在跑"。
        viewModel.getBoardEvent().observe(getViewLifecycleOwner(), this::onBoardSteps);
        viewModel.getFinishEvent().observe(getViewLifecycleOwner(), this::onFinish);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel == null) {
            return;
        }
        ParkingEngine.State state = viewModel.getState().getValue();
        if (state == null || state == ParkingEngine.State.READY) {
            if (viewModel.hasResumableSession()) {
                showContinueDialog();
            } else {
                viewModel.startRun();
            }
        }
    }

    /** 第二次进入时弹出：继续（从存档关卡接续）或重新开始（从第 1 关）。 */
    private void showContinueDialog() {
        int level = viewModel.getSavedLevel();
        int score = viewModel.getSavedScore();
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.parking_continue_title)
            .setMessage(getString(R.string.parking_continue_message, level, score))
            .setCancelable(false)
            .setPositiveButton(R.string.parking_continue_positive,
                (dialog, which) -> viewModel.continueRun())
            .setNegativeButton(R.string.parking_continue_negative,
                (dialog, which) -> viewModel.abandonAndRestart())
            .show();
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            binding.parkingView.setListener(null);
            binding.parkingView.setOnAnimationFinishedListener(null);
        }
        binding = null;
        super.onDestroyView();
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private void onLevelReady(@Nullable Level level) {
        if (binding == null || level == null) {
            return;
        }
        viewModel.applyLevel(level);
        updateBoardDescription();
    }

    private void renderScore(@Nullable Integer score) {
        if (binding == null) {
            return;
        }
        binding.tvScore.setText(String.valueOf(score == null ? 0 : score));
    }

    /**
     * 历史最高分：既刷新顶部栏的常驻显示，也缓存到 {@link #historicalBest}
     * 供通关弹窗使用（弹窗要等离场动画播完才弹，届时不能再去读 LiveData 的瞬时值）。
     */
    private void renderBest(@Nullable Integer best) {
        historicalBest = best == null ? 0 : best;
        if (binding != null) {
            binding.tvBest.setText(String.valueOf(historicalBest));
        }
    }

    private void renderLevel(@Nullable Integer level) {
        if (binding == null) {
            return;
        }
        binding.tvTitle.setText(getString(R.string.parking_level_title, level == null ? 1 : level));
    }

    private void renderLoading(@Nullable Boolean loading) {
        if (binding == null) {
            return;
        }
        boolean busy = Boolean.TRUE.equals(loading);
        binding.progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        renderControls();
    }

    private void renderRemoveButton(@Nullable Integer removesLeft) {
        if (binding == null) {
            return;
        }
        int left = removesLeft == null ? 0 : removesLeft;
        binding.btnRemove.setText(getString(R.string.parking_remove_count, left));
        renderControls();
    }

    private void renderSortButton(@Nullable Integer sortsLeft) {
        if (binding == null) {
            return;
        }
        int left = sortsLeft == null ? 0 : sortsLeft;
        binding.btnSort.setText(getString(R.string.parking_sort_count, left));
        renderControls();
    }

    private void renderControls() {
        if (binding == null) {
            return;
        }
        boolean busy = Boolean.TRUE.equals(viewModel.getLoading().getValue());
        boolean running = viewModel.getState().getValue() == ParkingEngine.State.RUNNING;
        Integer removes = viewModel.getRemovesLeft().getValue();
        Integer sorts = viewModel.getSortsLeft().getValue();

        binding.btnSort.setEnabled(running && !busy && sorts != null && sorts > 0);
        binding.btnRefresh.setEnabled(!busy);
        binding.btnRemove.setEnabled(running && !busy && removes != null && removes > 0);
        binding.btnRemove.setChecked(Boolean.TRUE.equals(viewModel.getRemoveMode().getValue()));
    }

    private void renderHint() {
        if (binding == null) {
            return;
        }
        if (Boolean.TRUE.equals(viewModel.getRemoveMode().getValue())) {
            binding.tvHint.setText(R.string.parking_remove_hint);
            binding.tvHint.setVisibility(View.VISIBLE);
        } else if (!viewModel.isHintShown()) {
            binding.tvHint.setText(R.string.parking_hint);
            binding.tvHint.setVisibility(View.VISIBLE);
        } else {
            binding.tvHint.setVisibility(View.GONE);
        }
    }

    private void onMoved(@Nullable MoveResult result) {
        if (binding == null || result == null || !result.success) {
            return;
        }
        binding.parkingView.startMove(result);
        if (result.boarded > 0) {
            binding.parkingView.startPopup(result.boarded * ParkingConfig.SCORE_PER_PASSENGER);
            pulse(binding.tvScore);
            announce(getString(R.string.parking_announce_boarded, result.boarded));
            soundManager.success();
        } else if (result.exited) {
            announce(getString(R.string.parking_announce_parked,
                viewModel.getEngine().getFreeSlots()));
        }
        showComboIfAny(result.boardSteps);
        binding.parkingView.performHapticFeedback(result.boarded > 0
            ? HapticFeedbackConstants.LONG_PRESS
            : HapticFeedbackConstants.KEYBOARD_TAP);

        if (!viewModel.isHintShown()) {
            viewModel.markHintShown();
            renderHint();
        }
        updateBoardDescription();
        viewModel.clearMoveEvent();
    }

    private void onBoardSteps(List<BoardStep> steps) {
        if (binding == null || steps == null || steps.isEmpty()) {
            return;
        }
        binding.parkingView.startBoardSteps(steps);
        showComboIfAny(steps);
        viewModel.clearBoardEvent();
    }

    /** 一次操作送走 ≥2 辆车时弹出连击浮字并播报。 */
    private void showComboIfAny(@Nullable List<BoardStep> steps) {
        if (binding == null || steps == null || steps.size() < 2) {
            return;
        }
        int cars = steps.size();
        int bonus = ParkingConfig.SCORE_COMBO_PER_EXTRA * (cars - 1);
        binding.parkingView.startComboPopup(cars, bonus);
        soundManager.success();
        announce(getString(R.string.parking_combo_announce, cars, bonus));
    }

    private void onMessage(@Nullable Integer messageRes) {
        if (binding == null || messageRes == null || messageRes == 0) {
            return;
        }
        Snackbar.make(binding.getRoot(), messageRes, Snackbar.LENGTH_SHORT).show();
        announce(getString(messageRes));
        viewModel.clearMessageEvent();
    }

    private void onFinish(@Nullable Boolean solved) {
        if (binding == null || solved == null) {
            return;
        }
        viewModel.clearFinishEvent();
        // 通关 / 卡住时，离场动画（接客 → 开上马路 → 开走）可能还在播放。
        // 还有动画在跑就先不弹窗，等所有车都开走、动画结束回调后再弹。
        if (!binding.parkingView.isBusy()) {
            showEndDialog(solved);
            return;
        }
        final boolean solvedFlag = solved;
        binding.parkingView.setOnAnimationFinishedListener(() -> {
            if (binding == null) {
                return;
            }
            binding.parkingView.setOnAnimationFinishedListener(null);
            showEndDialog(solvedFlag);
        });
    }

    private void showEndDialog(boolean solved) {
        if (solved) {
            showSolvedDialog();
        } else {
            showStuckDialog();
        }
    }

    private void showSolvedDialog() {
        int level = viewModel.getLevel().getValue() == null ? 1 : viewModel.getLevel().getValue();
        int score = viewModel.getScore().getValue() == null ? 0 : viewModel.getScore().getValue();
        // 本局成绩入库是异步的，LiveData 可能还没刷新到最新，用 max 兜住这个时间差。
        // （historicalBest 由常驻观察者持续刷新，见 onViewCreated。）
        int bestScore = Math.max(historicalBest, score);
        int served = viewModel.getEngine().getPassengersServed();
        String message = getString(R.string.parking_solved_message, served, score, bestScore);
        announce(message);
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.parking_solved_title, level))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.parking_next_level,
                (dialog, which) -> viewModel.nextLevel())
            .setNegativeButton(R.string.parking_back_to_hub, (dialog, which) -> navigateUp())
            .show();
    }

    private void showStuckDialog() {
        String message = getString(R.string.parking_stuck_message);
        announce(message);
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.parking_stuck_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.parking_stuck_undo, (dialog, which) -> viewModel.undo())
            .setNegativeButton(R.string.parking_stuck_refresh,
                (dialog, which) -> viewModel.refreshLevel())
            .setNeutralButton(R.string.parking_back_to_hub, (dialog, which) -> navigateUp())
            .show();
    }

    /** 数值变化的脉冲反馈，让分数变化被看见。 */
    private void pulse(View view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.animate()
            .scaleX(1.18f)
            .scaleY(1.18f)
            .setDuration(120)
            .setInterpolator(new AccelerateDecelerateInterpolator())
            .withEndAction(() -> view.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .start())
            .start();
    }

    // ==================================================================
    // 无障碍
    // ==================================================================

    /** 把局面播报给 TalkBack：颜色不是唯一编码，这里补上文字信息。 */
    private void updateBoardDescription() {
        if (binding == null) {
            return;
        }
        ParkingEngine engine = viewModel.getEngine();
        if (engine.getState() != ParkingEngine.State.RUNNING) {
            return;
        }
        String front = "";
        if (engine.queueSize() > 0) {
            PassengerGroup group = engine.queueGroupAt(0);
            front = getString(R.string.parking_announce_front,
                group.count, colorNames[group.colorIndex]);
        }
        binding.parkingView.setContentDescription(getString(R.string.parking_announce_state,
            engine.getLevelIndex(), engine.getPassengersLeft(), engine.getFreeSlots(), front));
    }

    private void announce(String text) {
        if (binding != null) {
            binding.parkingView.announceForAccessibility(text);
        }
    }

    // ==================================================================
    // 导航
    // ==================================================================

    private void navigateUp() {
        NavController controller = NavHostFragment.findNavController(this);
        if (!controller.popBackStack()) {
            controller.navigateUp();
        }
    }
}
