package com.template.app.game.tetris.ui;

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
import com.template.app.R;
import com.template.app.databinding.FragmentTetrisBinding;
import com.template.app.game.tetris.engine.PlaceResult;
import com.template.app.game.tetris.engine.TetrisEngine;
import com.template.app.game.tetris.view.TetrisView;

/**
 * 拖拽方块游戏页。
 * <p>
 * 职责边界：只做「把引擎状态渲染到控件」和「把 View 的手势转成 ViewModel 命令」，
 * 游戏规则一律不在这里。
 */
public class TetrisFragment extends Fragment {

    private FragmentTetrisBinding binding;
    private TetrisViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentTetrisBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(TetrisViewModel.class);

        binding.tetrisView.attach(viewModel.getEngine());
        binding.tetrisView.setListener(new TetrisView.Listener() {
            @Override
            public void onPlaceRequested(int slot, int left, int fromTop) {
                viewModel.place(slot, left, fromTop);
            }

            @Override
            public void onRotateRequested(int slot) {
                viewModel.rotate(slot);
            }
        });

        binding.btnBack.setOnClickListener(v -> navigateUp());
        binding.btnPause.setOnClickListener(v -> togglePause());
        binding.btnRestart.setOnClickListener(v -> viewModel.restart());

        binding.tvHint.setVisibility(viewModel.isDragHintShown() ? View.GONE : View.VISIBLE);

        viewModel.getScore().observe(getViewLifecycleOwner(),
            value -> binding.tvScore.setText(String.valueOf(value)));
        viewModel.getLines().observe(getViewLifecycleOwner(),
            value -> binding.tvLines.setText(String.valueOf(value)));
        viewModel.getLevel().observe(getViewLifecycleOwner(),
            value -> binding.tvLevel.setText(String.valueOf(value)));
        viewModel.getBest().observe(getViewLifecycleOwner(),
            value -> binding.tvBest.setText(String.valueOf(value == null ? 0 : value)));

        viewModel.getState().observe(getViewLifecycleOwner(), this::renderState);
        viewModel.getRevision().observe(getViewLifecycleOwner(), ignored -> binding.tetrisView.invalidate());
        viewModel.getLastPlacement().observe(getViewLifecycleOwner(), this::onPlaced);
        viewModel.getGameOverEvent().observe(getViewLifecycleOwner(), this::onGameOver);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (viewModel == null) {
            return;
        }
        TetrisEngine.State state = viewModel.getState().getValue();
        if (state == null || state == TetrisEngine.State.READY) {
            // 与挪车一致：存在未完成局时先问「继续 / 重新开始」，否则直接开新局
            if (viewModel.hasResumableSession()) {
                showContinueDialog();
            } else {
                viewModel.start();
            }
        } else if (state == TetrisEngine.State.PAUSED) {
            viewModel.resume();
        }
    }

    /** 再次进入时弹出：继续（从存档接续）或重新开始（清空棋盘）。 */
    private void showContinueDialog() {
        int score = viewModel.getSavedScore();
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tetris_continue_title)
            .setMessage(getString(R.string.tetris_continue_message, score))
            .setCancelable(false)
            .setPositiveButton(R.string.tetris_continue_positive,
                (dialog, which) -> viewModel.continueRun())
            .setNegativeButton(R.string.tetris_continue_negative,
                (dialog, which) -> viewModel.abandonAndRestart())
            .show();
    }

    @Override
    public void onPause() {
        if (viewModel != null) {
            viewModel.pause();
            // 退出只是暂停：落盘当前局面，下次进入可继续
            viewModel.persistProgress();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        binding.tetrisView.setListener(null);
        binding = null;
        super.onDestroyView();
    }

    // ==================================================================
    // 渲染
    // ==================================================================

    private void renderState(TetrisEngine.State state) {
        if (binding == null) {
            return;
        }
        boolean running = state == TetrisEngine.State.RUNNING;
        boolean paused = state == TetrisEngine.State.PAUSED;
        binding.btnPause.setEnabled(running || paused);
        binding.btnPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        binding.btnPause.setContentDescription(getString(paused ? R.string.tetris_resume : R.string.tetris_pause));
        binding.btnRestart.setEnabled(state != TetrisEngine.State.READY);
        binding.tetrisView.invalidate();
    }

    private void onPlaced(@Nullable PlaceResult result) {
        if (binding == null || result == null || !result.success) {
            return;
        }
        int clearedCount = result.clearedRows.length;
        binding.tetrisView.startClearFlash(result.clearedRows);
        if (clearedCount > 0) {
            // 只有消行才得分，因此得分动画也只在消行时播放
            binding.tetrisView.startScorePopup(
                result.gained, clearedCount, result.combo, result.clearedRows);
            pulse(binding.tvScore);
        }
        binding.tetrisView.performHapticFeedback(clearedCount > 0
            ? HapticFeedbackConstants.LONG_PRESS
            : HapticFeedbackConstants.KEYBOARD_TAP);

        if (!viewModel.isDragHintShown()) {
            viewModel.markDragHintShown();
            binding.tvHint.setVisibility(View.GONE);
        }
    }

    /** 数值变化的脉冲反馈：先放大再回弹，让总分的变化被看见。 */
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

    private void onGameOver(@Nullable Integer score) {
        if (binding == null || score == null) {
            return;
        }
        viewModel.clearGameOverEvent();
        Integer bestValue = viewModel.getBest().getValue();
        // 本局成绩已写入存档，但 Room 的 LiveData 是异步回调的，
        // 此刻读到的可能还是"本局之前"的最高分，取 max 避免显示"本局 1240 / 最高 800"
        int best = Math.max(bestValue == null ? 0 : bestValue, score);
        String message = getString(R.string.tetris_game_over_message, score, best);
        binding.tetrisView.announceForAccessibility(message);
        new MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tetris_game_over_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.tetris_play_again, (dialog, which) -> viewModel.restart())
            .setNegativeButton(R.string.tetris_back_to_hub, (dialog, which) -> navigateUp())
            .show();
    }

    // ==================================================================
    // 交互
    // ==================================================================

    private void togglePause() {
        TetrisEngine.State state = viewModel.getState().getValue();
        if (state == TetrisEngine.State.PAUSED) {
            viewModel.resume();
        } else if (state == TetrisEngine.State.RUNNING) {
            viewModel.pause();
        }
    }

    private void navigateUp() {
        NavController controller = NavHostFragment.findNavController(this);
        if (!controller.popBackStack()) {
            controller.navigateUp();
        }
    }
}
