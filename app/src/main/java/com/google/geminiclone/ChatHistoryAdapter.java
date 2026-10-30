package com.google.geminiclone;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class ChatHistoryAdapter extends RecyclerView.Adapter<ChatHistoryAdapter.HistoryViewHolder> {

    public interface OnHistoryActionListener {
        void onChatSelected(ChatSession session);
        void onChatDeleted(ChatSession session);
    }

    private final List<ChatSession> sessions;
    private final OnHistoryActionListener listener;
    private String selectedChatId = null;

    public ChatHistoryAdapter(List<ChatSession> sessions, OnHistoryActionListener listener) {
        this.sessions = sessions;
        this.listener = listener;
    }

    public void setSelectedChatId(String id) {
        this.selectedChatId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat_history, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        ChatSession session = sessions.get(position);
        holder.tvTitle.setText(session.getTitle());

        boolean isSelected = session.getId().equals(selectedChatId);
        if (isSelected) {
            holder.itemView.setBackgroundResource(R.drawable.bg_chip_pill);
            holder.tvTitle.setTextColor(Color.parseColor("#A8C7FA"));
            holder.ivIcon.setColorFilter(Color.parseColor("#A8C7FA"));
        } else {
            holder.itemView.setBackgroundResource(R.drawable.bg_suggestion_card);
            holder.tvTitle.setTextColor(Color.parseColor("#E3E3E3"));
            holder.ivIcon.setColorFilter(Color.parseColor("#C4C7C5"));
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChatSelected(session);
            }
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) {
                listener.onChatDeleted(session);
            }
        });
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class HistoryViewHolder extends RecyclerView.ViewHolder {
        ImageView ivIcon;
        TextView tvTitle;
        ImageButton btnDelete;

        HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            ivIcon = itemView.findViewById(R.id.ivChatIcon);
            tvTitle = itemView.findViewById(R.id.tvChatTitle);
            btnDelete = itemView.findViewById(R.id.btnDeleteChat);
        }
    }
}
