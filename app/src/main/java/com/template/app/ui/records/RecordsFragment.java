package com.template.app.ui.records;

import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.template.app.R;
import com.template.app.databinding.FragmentRecordsBinding;
import com.template.app.databinding.ItemGameBestBinding;
import com.template.app.databinding.ItemRecordBinding;
import com.template.app.data.model.GameBest;
import com.template.app.data.model.GameRecord;
import com.template.app.game.core.GamePlugin;
import com.template.app.game.core.GameRegistry;

import java.util.List;

/**
 * 战绩页：每款游戏的历史最高分 + 最近对局列表。
 */
public class RecordsFragment extends Fragment {

    private FragmentRecordsBinding binding;
    private RecordsViewModel viewModel;
    private LayoutInflater inflater;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        this.inflater = inflater;
        binding = FragmentRecordsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(RecordsViewModel.class);

        viewModel.getBestPerGame().observe(getViewLifecycleOwner(), this::renderBest);
        viewModel.getRecent().observe(getViewLifecycleOwner(), this::renderRecent);
    }

    private void renderBest(@Nullable List<GameBest> bests) {
        if (binding == null) {
            return;
        }
        binding.containerBest.removeAllViews();
        if (bests == null || bests.isEmpty()) {
            binding.tvBestEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.tvBestEmpty.setVisibility(View.GONE);
        for (GameBest best : bests) {
            ItemGameBestBinding item = ItemGameBestBinding.inflate(inflater, binding.containerBest, false);
            item.tvGameName.setText(gameName(best.gameId));
            item.tvBestScore.setText(String.valueOf(best.score));
            item.tvPlayCount.setText(getString(R.string.records_play_count, best.plays));
            binding.containerBest.addView(item.getRoot());
        }
    }

    private void renderRecent(@Nullable List<GameRecord> records) {
        if (binding == null) {
            return;
        }
        binding.containerRecent.removeAllViews();
        if (records == null || records.isEmpty()) {
            binding.tvRecentEmpty.setVisibility(View.VISIBLE);
            return;
        }
        binding.tvRecentEmpty.setVisibility(View.GONE);
        long now = System.currentTimeMillis();
        for (GameRecord record : records) {
            ItemRecordBinding item = ItemRecordBinding.inflate(inflater, binding.containerRecent, false);
            item.tvGameName.setText(gameName(record.getGameId()));
            item.tvScore.setText(String.valueOf(record.getScore()));
            item.tvMeta.setText(getString(R.string.records_meta,
                record.getLines(),
                DateUtils.getRelativeTimeSpanString(record.getPlayedAt(), now,
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)));
            binding.containerRecent.addView(item.getRoot());
        }
    }

    /** 由 gameId 解析出游戏名称；未注册的游戏直接回退显示 ID。 */
    private String gameName(@Nullable String gameId) {
        if (gameId == null) {
            return "";
        }
        GamePlugin plugin = GameRegistry.find(gameId);
        if (plugin == null) {
            return gameId;
        }
        return getString(plugin.descriptor().titleRes);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        inflater = null;
        super.onDestroyView();
    }
}
