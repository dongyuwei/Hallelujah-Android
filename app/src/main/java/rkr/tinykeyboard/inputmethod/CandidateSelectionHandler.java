package rkr.tinykeyboard.inputmethod;

public class CandidateSelectionHandler implements CandidateAdapter.CandidateSelectionListener {

    private final SoftKeyboard keyboard;

    public CandidateSelectionHandler(SoftKeyboard keyboard) {
        this.keyboard = keyboard;
    }

    @Override
    public void onCandidateSelected(int index, String candidate) {
        keyboard.onCandidateSelected(index, candidate);
    }
}
