package rkr.tinykeyboard.inputmethod;

public class CandidateSelectionHandler implements CandidateAdapter.CandidateSelectionListener {

    private final SoftKeyboard keyboard;

    public CandidateSelectionHandler(SoftKeyboard keyboard) {
        this.keyboard = keyboard;
    }

    @Override
    public void onCandidateSelected(String candidate) {
        keyboard.getCurrentInputConnection().commitText(candidate, candidate.length());
        keyboard.reset();
    }
}
