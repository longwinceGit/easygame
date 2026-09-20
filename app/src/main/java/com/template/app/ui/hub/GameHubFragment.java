package com.template.app.ui.hub;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.GridLayoutManager;

import com.template.app.databinding.FragmentGameHubBinding;
import com.template.app.game.core.GamePlugin;

import java.util.List;

/**
 * 游戏大厅：展示 {@link com.template.app.game.core.GameRegistry} 中已注册的全部游戏。
 */
public class GameHubFragment extends Fragment {

    private FragmentGameHubBinding binding;
    private GameHubViewModel viewModel;
    private GameAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentGameHubBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = new ViewModelProvider(this).get(GameHubViewModel.class);

        adapter = new GameAdapter(this::openGame);
        binding.rvGames.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        binding.rvGames.setAdapter(adapter);
        binding.rvGames.setHasFixedSize(true);

        viewModel.getGames().observe(getViewLifecycleOwner(), this::render);
    }

    private void render(@Nullable List<GamePlugin> games) {
        if (binding == null || games == null) {
            return;
        }
        adapter.submit(games);
        boolean empty = games.isEmpty();
        binding.rvGames.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void openGame(@NonNull GamePlugin plugin) {
        NavController controller = NavHostFragment.findNavController(this);
        controller.navigate(plugin.descriptor().navDestinationRes);
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }
}
