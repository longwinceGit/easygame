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
    private String[] colorNames;

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

        binding.btnBack.setOnClickListener(v -> navigateUp());
        binding.btnSort.setOnClickListener(v -> {
            if (binding.parkingView.isBusy()) {
                return;
            }
            viewModel.sortQueue();
        });
        binding.btnRefresh.setOnClickListener(v -> {
            if (binding.parkingView.isBusy()) {
                return;
            }
            viewModel.refreshLevel();
        });
        binding.btnRemove.setOnClickListener(v -> viewModel.toggleRemoveMode());

        renderHint();

        viewModel.getScore().observe(getViewLifecycleOwner(), this::renderScore);
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
        viewModel.getFinishEvent().observe(getViewLifecycleOwner(), this::onFinish);
        viewModel.getBoardEvent().observe(getViewLifecycleOwner(), this::onBoardSteps);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel == null) {
            return;
        }
        ParkingEngine.State state = viewModel.getState().getValue();
        if (state == null || state == ParkingEngine.State.READY) {
            viewModel.startRun();
        }
    }

    @Override
    public void onDestroyView() {
        binding.parkingView.setListener(null);
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
        } else if (result.exited) {
            announce(getString(R.string.parking_announce_parked,
                viewModel.getEngine().getFreeSlots()));
        }
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
        viewModel.clearBoardEvent();
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
        if (solved) {
            showSolvedDialog();
        } else {
            showStuckDialog();
        }
    }

    private void showSolvedDialog() {
        int level = viewModel.getLevel().getValue() == null ? 1 : viewModel.getLevel().getValue();
        int score = viewModel.getScore().getValue() == null ? 0 : viewModel.getScore().getValue();
        Integer best = viewModel.getBest().getValue();
        // Room 的 LiveData 是异步回调的，此刻读到的可能还是本局之前的最高分，取 max
        int bestScore = Math.max(best == null ? 0 : best, score);
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
