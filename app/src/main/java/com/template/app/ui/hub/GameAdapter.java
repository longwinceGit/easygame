package com.template.app.ui.hub;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.template.app.databinding.ItemGameBinding;
import com.template.app.game.core.GameDescriptor;
import com.template.app.game.core.GamePlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 游戏大厅列表适配器。
 * <p>
 * 卡片内容完全由 {@link GameDescriptor} 驱动，不含任何针对具体游戏的分支逻辑。
 */
public class GameAdapter extends RecyclerView.Adapter<GameAdapter.GameViewHolder> {

    /** 卡片点击回调。 */
    public interface OnGameClickListener {
        void onGameClick(@NonNull GamePlugin plugin);
    }

    private final List<GamePlugin> items = new ArrayList<>();
    private final OnGameClickListener listener;

    public GameAdapter(@NonNull OnGameClickListener listener) {
        this.listener = listener;
    }

    public void submit(@NonNull List<GamePlugin> data) {
        items.clear();
        items.addAll(data);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemGameBinding binding = ItemGameBinding.inflate(
            LayoutInflater.from(parent.getContext()), parent, false);
        return new GameViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull GameViewHolder holder, int position) {
        holder.bind(items.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class GameViewHolder extends RecyclerView.ViewHolder {

        private final ItemGameBinding binding;

        GameViewHolder(@NonNull ItemGameBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

        void bind(@NonNull GamePlugin plugin, @NonNull OnGameClickListener listener) {
            GameDescriptor descriptor = plugin.descriptor();
            int accent = ContextCompat.getColor(binding.getRoot().getContext(), descriptor.accentRes);

            binding.ivGameIcon.setImageResource(descriptor.iconRes);
            ImageViewCompat.setImageTintList(binding.ivGameIcon, ColorStateList.valueOf(accent));
            binding.tvGameTitle.setText(descriptor.titleRes);
            binding.tvGameSubtitle.setText(descriptor.subtitleRes);
            binding.btnGameStart.setTextColor(accent);

            binding.getRoot().setStrokeColor(accent);
            binding.getRoot().setEnabled(plugin.isEnabled());
            binding.getRoot().setAlpha(plugin.isEnabled() ? 1f : 0.55f);
            binding.btnGameStart.setEnabled(plugin.isEnabled());

            binding.getRoot().setOnClickListener(v -> {
                if (plugin.isEnabled()) {
                    listener.onGameClick(plugin);
                }
            });
            binding.getRoot().setContentDescription(
                binding.getRoot().getContext().getString(descriptor.titleRes));
        }
    }
}
