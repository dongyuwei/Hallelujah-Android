package rkr.tinykeyboard.inputmethod;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CandidateAdapter extends RecyclerView.Adapter<CandidateAdapter.ViewHolder> {
    /**
     * The grid uses 12 spans per row: 4 candidates per row × 3 spans each. The
     * incomplete last row instead stretches its items across all 12 spans so
     * the candidate bar always covers the full width.
     */
    static final int SPANS_PER_ROW = 12;
    static final int NORMAL_SPAN = 3;
    private static final int CANDIDATES_PER_ROW = 4;

    private final List<String> candidateList;
    private final CandidateSelectionListener listener;

    public CandidateAdapter(List<String> candidates, CandidateSelectionListener listener) {
        this.candidateList = candidates.size() > 12 ? candidates.subList(0, 12) : candidates;
        this.listener = listener;
    }

    /** Span width for one item: normal cells are a quarter row; the incomplete last row stretches. */
    int getSpanSize(int position) {
        return spanSizeFor(position, candidateList.size());
    }

    static int spanSizeFor(int position, int itemCount) {
        int lastRowCount = itemCount % CANDIDATES_PER_ROW;
        if (lastRowCount == 0 || position < itemCount - lastRowCount) {
            return NORMAL_SPAN;
        }
        return SPANS_PER_ROW / lastRowCount;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext()).inflate(R.layout.candidate_item_layout, parent, false);
        return new ViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String candidate = candidateList.get(position);
        holder.textView.setText(candidate);
        if (getSpanSize(position) > NORMAL_SPAN) {
            holder.textView.setGravity(Gravity.CENTER);
        } else {
            holder.textView.setGravity(Gravity.CENTER_VERTICAL);
        }

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
