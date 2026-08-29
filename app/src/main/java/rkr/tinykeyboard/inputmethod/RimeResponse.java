package rkr.tinykeyboard.inputmethod;

/**
 * Everything the IME needs after one librime key event, fetched in a single
 * JNI round trip. Fields are filled by native code (RimeJni.cpp).
 */
public class RimeResponse {
    public final String commitText;   // non-null when librime has text to commit
    public final String preedit;      // non-null while a composition is active
    public final String[] candidates; // current page, may be empty
    public final int highlighted;     // index within the current page, -1 if none

    public RimeResponse(String commitText, String preedit, String[] candidates,
                        int highlighted) {
        this.commitText = commitText;
        this.preedit = preedit;
        this.candidates = candidates != null ? candidates : new String[0];
        this.highlighted = highlighted;
    }
}
