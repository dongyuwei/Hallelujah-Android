package rkr.tinykeyboard.inputmethod;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Horizontal single-row candidate strip: width-hugging items that scroll
 * sideways, like the suggestion bar of mainstream mobile keyboards.
 */
public class CandidateStripAdapter extends RecyclerView.Adapter<CandidateStripAdapter.ViewHolder> {

    /** The strip shows the first N candidates without scrolling. */
    private static final int MAX_STRIP_ITEMS = 20;

    private final List<String> candidateList;
    private final CandidateAdapter.CandidateSelectionListener listener;

    public CandidateStripAdapter(List<String> candidates,
            CandidateAdapter.CandidateSelectionListener listener) {
        this.candidateList = candidates.size() > MAX_STRIP_ITEMS
                ? candidates.subList(0, MAX_STRIP_ITEMS) : candidates;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View item = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.candidate_strip_item, parent, false);
        return new ViewHolder(item);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        final String candidate = candidateList.get(position);
        holder.textView.setText(candidate);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCandidateSelected(position, candidate);
            }
        });
    }

    @Override
    public int getItemCount() {
        return candidateList.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
        }
    }
}
