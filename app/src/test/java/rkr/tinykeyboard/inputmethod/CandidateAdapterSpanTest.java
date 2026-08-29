package rkr.tinykeyboard.inputmethod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CandidateAdapterSpanTest {

    @Test
    public void fullRows_useQuarterWidthCells() {
        for (int count : new int[]{4, 8, 12}) {
            for (int position = 0; position < count; position++) {
                assertEquals(CandidateAdapter.NORMAL_SPAN,
                        CandidateAdapter.spanSizeFor(position, count));
            }
        }
    }

    @Test
    public void singleItem_stretchesAcrossFullRow() {
        assertEquals(CandidateAdapter.SPANS_PER_ROW, CandidateAdapter.spanSizeFor(0, 1));
    }

    @Test
    public void twoLastRowItems_eachStretchToHalfRow() {
        // 6 items: row 1 is full (4 cells), row 2 has two half-row cells.
        for (int position = 0; position < 4; position++) {
            assertEquals(CandidateAdapter.NORMAL_SPAN, CandidateAdapter.spanSizeFor(position, 6));
        }
        assertEquals(CandidateAdapter.SPANS_PER_ROW / 2, CandidateAdapter.spanSizeFor(4, 6));
        assertEquals(CandidateAdapter.SPANS_PER_ROW / 2, CandidateAdapter.spanSizeFor(5, 6));
    }

    @Test
    public void threeLastRowItems_eachStretchToThirdRow() {
        // 7 items: row 1 is full (4 cells), row 2 has three third-row cells.
        for (int position = 0; position < 4; position++) {
            assertEquals(CandidateAdapter.NORMAL_SPAN, CandidateAdapter.spanSizeFor(position, 7));
        }
        assertEquals(CandidateAdapter.SPANS_PER_ROW / 3, CandidateAdapter.spanSizeFor(4, 7));
        assertEquals(CandidateAdapter.SPANS_PER_ROW / 3, CandidateAdapter.spanSizeFor(5, 7));
        assertEquals(CandidateAdapter.SPANS_PER_ROW / 3, CandidateAdapter.spanSizeFor(6, 7));
    }

    @Test
    public void completeRowsBeforeIncompleteOne_stayNormal() {
        // 5 items: row 1 has 4 normal cells, row 2 has one stretched cell.
        for (int position = 0; position < 4; position++) {
            assertEquals(CandidateAdapter.NORMAL_SPAN, CandidateAdapter.spanSizeFor(position, 5));
        }
        assertEquals(CandidateAdapter.SPANS_PER_ROW, CandidateAdapter.spanSizeFor(4, 5));
    }

    @Test
    public void spansOfEveryRow_alwaysSumToFullRow() {
        for (int count = 1; count <= 12; count++) {
            int firstRowSpans = 0;
            for (int position = 0; position < Math.min(count, 4); position++) {
                firstRowSpans += CandidateAdapter.spanSizeFor(position, count);
            }
            assertEquals(CandidateAdapter.SPANS_PER_ROW, firstRowSpans);
        }
    }
}
