package rkr.tinykeyboard.inputmethod;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CandidateAdapter extends RecyclerView.Adapter<CandidateAdapter.ViewHolder> {
    private final List<String> candidateList;
    private final CandidateSelectionListener listener;

    public CandidateAdapter(List<String> candidates, CandidateSelectionListener listener) {
        this.candidateList = candidates.size() > 12 ? candidates.subList(0, 12) : candidates;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String candidate = candidateList.get(position);
        holder.textView.setText(candidate);

        // Handle item click
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCandidateSelected(candidate);
            }
        });
    }

    @Override
    public int getItemCount() {
        return candidateList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
        }
    }

    public interface CandidateSelectionListener {
        void onCandidateSelected(String candidate);
    }
}
