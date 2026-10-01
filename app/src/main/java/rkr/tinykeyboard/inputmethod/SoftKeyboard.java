/*
 * Copyright (C) 2008-2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.tinykeyboard.inputmethod;

import android.content.Context;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.os.Build;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import rkr.tinykeyboard.inputmethod.rime.RimeController;
import rkr.tinykeyboard.inputmethod.rime.RimeWasmEngine;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SoftKeyboard extends InputMethodService
        implements KeyboardView.OnKeyboardActionListener, RimeController.Listener {

    private KeyboardView mInputView;
    private RecyclerView candidatesRecyclerView;
    private int mLastDisplayWidth;
    private boolean mCapsLock;
    private long mLastShiftTime;

    private LatinKeyboard mSymbolsKeyboard;
    private LatinKeyboard mSymbolsShiftedKeyboard;
    private LatinKeyboard mQwertyKeyboard;

    private LatinKeyboard mCurKeyboard;

    private ExecutorService executorService;
    private StringBuilder compositionText = new StringBuilder();
    private Map<String, List<String>> pinyinMap = new HashMap<>();
    private volatile CandidateProvider candidateProvider;
    private InputMode inputMode = InputMode.English;

    private RimeController rimeController;
    /** True while the rime engine holds a composition for this input field. */
    private boolean pinyinComposing;

    /** Keysym names for punctuation fed to the rime engine (from the my_rime key map). */
    private static final Map<Character, String> PUNCTUATION_KEYS;
    static {
        Map<Character, String> m = new HashMap<>();
        m.put(',', "comma");             m.put('.', "period");
        m.put('?', "question");          m.put('!', "exclam");
        m.put('\'', "apostrophe");       m.put(';', "semicolon");
        m.put(':', "colon");             m.put('-', "minus");
        m.put('"', "quotedbl");          m.put('/', "slash");
        m.put('\\', "backslash");        m.put('@', "at");
        m.put('#', "numbersign");        m.put('$', "dollar");
        m.put('%', "percent");           m.put('&', "ampersand");
        m.put('*', "asterisk");          m.put('(', "parenleft");
        m.put(')', "parenright");        m.put('+', "plus");
        m.put('=', "equal");             m.put('<', "less");
        m.put('>', "greater");           m.put('[', "bracketleft");
        m.put(']', "bracketright");      m.put('{', "braceleft");
        m.put('}', "braceright");        m.put('~', "asciitilde");
        m.put('`', "quoteleft");         m.put('_', "underscore");
        m.put('^', "asciicircum");       m.put('|', "bar");
        PUNCTUATION_KEYS = m;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (pinyinMap.isEmpty()) {
            executorService = Executors.newSingleThreadExecutor();
            loadDictionaryAsync();
        }
        if (rimeController == null) {
            rimeController = RimeController.create(this, executorService);
            rimeController.setListener(this);
        }
    }

    private void loadDictionaryAsync() {
        executorService.execute(() -> {
            DictionaryDb.init(getApplicationContext());

            Gson gson = new Gson();
            String pinyinJson = DictUtil.getContentFromAssets(getApplicationContext(), "cedict.json");
            Type pinyinType = new TypeToken<Map<String, List<String>>>() {
            }.getType();
            pinyinMap = gson.fromJson(pinyinJson, pinyinType);

            String phonexJson = DictUtil.getContentFromAssets(getApplicationContext(), "phonex_encoded_words.json");
            Type phonexType = new TypeToken<Map<String, List<String>>>() {
            }.getType();
            SpellChecker spellChecker = new SpellChecker(gson.fromJson(phonexJson, phonexType));

            candidateProvider = new CandidateProvider(DictionaryDb.getInstance(), pinyinMap,
                    new NorvigSpellChecker(DictionaryDb.getInstance()),
                    new TrieSpellChecker(DictionaryDb.getInstance()::getWordTrie,
                            DictionaryDb.getInstance()),
                    spellChecker);

            System.out.println("Hallelujah dictionary is ready now!");
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (rimeController != null) {
            rimeController.destroy();
            rimeController = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Override
    public View onCreateCandidatesView() {
        LayoutInflater inflater = getLayoutInflater();
        View candidatesView = inflater.inflate(R.layout.candidates_view_layout, null);

        candidatesRecyclerView = candidatesView.findViewById(R.id.candidatesRecyclerView);
        GridLayoutManager layoutManager = new GridLayoutManager(this, CandidateAdapter.SPANS_PER_ROW);
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                RecyclerView.Adapter<?> adapter = candidatesRecyclerView.getAdapter();
                return adapter instanceof CandidateAdapter
                        ? ((CandidateAdapter) adapter).getSpanSize(position)
                        : CandidateAdapter.NORMAL_SPAN;
            }
        });
        candidatesRecyclerView.setLayoutManager(layoutManager);

        return candidatesView;
    }

    @Override
    public void onComputeInsets(InputMethodService.Insets outInsets) {
        // https://stackoverflow.com/questions/11840627/rejusting-ui-with-candidateview-visible-in-custom-keyboard
        super.onComputeInsets(outInsets);
        if (!isFullscreenMode()) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets;
        }
    }

    private void updateCandidatesList(List<String> candidates) {
        setCandidatesViewShown(!candidates.isEmpty());
        CandidateSelectionHandler selectionHandler = new CandidateSelectionHandler(this);
        CandidateAdapter adapter = new CandidateAdapter(candidates, selectionHandler);
        candidatesRecyclerView.setAdapter(adapter);
    }

    Context getDisplayContext() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            // createDisplayContext is not available.
            return this;
        }
        // TODO (b/133825283): Non-activity components Resources / DisplayMetrics update when
        //  moving to external display.
        // An issue in Q that non-activity components Resources / DisplayMetrics in
        // Context doesn't well updated when the IME window moving to external display.
        // Currently we do a workaround is to create new display context directly and re-init
        // keyboard layout with this context.
        final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        return createDisplayContext(wm.getDefaultDisplay());
    }

    @Override
    public void onInitializeInterface() {
        final Context displayContext = getDisplayContext();

        if (mQwertyKeyboard != null) {
            // Configuration changes can happen after the keyboard gets recreated,
            // so we need to be able to re-build the keyboards if the available
            // space has changed.
            int displayWidth = getMaxWidth();
            if (displayWidth == mLastDisplayWidth) return;
            mLastDisplayWidth = displayWidth;
        }
        mQwertyKeyboard = new LatinKeyboard(displayContext, R.xml.qwerty);
        mSymbolsKeyboard = new LatinKeyboard(displayContext, R.xml.symbols);
        mSymbolsShiftedKeyboard = new LatinKeyboard(displayContext, R.xml.symbols_shift);
        updateStatusOfSwitchKey();
    }

    @Override
    public View onCreateInputView() {
        mInputView = (KeyboardView) getLayoutInflater().inflate(R.layout.input, null);
        mInputView.setOnKeyboardActionListener(this);
        mInputView.setPreviewEnabled(false);
        setLatinKeyboard(mQwertyKeyboard);
        return mInputView;
    }

    private void setLatinKeyboard(LatinKeyboard nextKeyboard) {
        // The switch key toggles English/Pinyin input mode inside this IME, so
        // it must stay visible even when the system wouldn't offer switching
        // to another input method (e.g. single-IME devices).
        nextKeyboard.setLanguageSwitchKeyVisibility(true);
        mInputView.setKeyboard(nextKeyboard);
        if (nextKeyboard == mQwertyKeyboard && mInputView != null) {
            updateStatusOfSwitchKey();
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);

        // https://issuetracker.google.com/issues/246132117
        setCandidatesViewShown(true);

        // We are now going to initialize our state based on the type of
        // text being edited.
        switch (attribute.inputType & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
            case InputType.TYPE_CLASS_PHONE:
                // Numbers and dates default to the symbols keyboard, with
                // no extra features.
                mCurKeyboard = mSymbolsKeyboard;
                break;

            default:
                // For all unknown input types, default to the alphabetic
                // keyboard with no special features.
                mCurKeyboard = mQwertyKeyboard;
                updateShiftKeyState(attribute);
        }

        // Update the label on the enter key, depending on what the application
        // says it will do.
        mCurKeyboard.setImeOptions(getResources(), attribute.imeOptions);
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        compositionText = new StringBuilder();
        abandonPinyinComposition();

        mCurKeyboard = mQwertyKeyboard;
        if (mInputView != null) {
            mInputView.closing();
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting) {
        super.onStartInputView(attribute, restarting);
        // Apply the selected keyboard to the input view.
        setLatinKeyboard(mCurKeyboard);
        mInputView.closing();
    }

    private void updateShiftKeyState(EditorInfo attr) {
        if (attr != null && mInputView != null && mQwertyKeyboard == mInputView.getKeyboard()) {
            int caps = 0;
            EditorInfo ei = getCurrentInputEditorInfo();
            if (ei != null && ei.inputType != InputType.TYPE_NULL) {
                caps = getCurrentInputConnection().getCursorCapsMode(attr.inputType);
            }
            mInputView.setShifted(mCapsLock || caps != 0);
        }
    }

    private void keyDownUp(int keyEventCode) {
        getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode));
        getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyEventCode));
    }

    // Implementation of KeyboardViewListener

    public void onKey(int primaryCode, int[] keyCodes) {
        if (primaryCode == Keyboard.KEYCODE_DONE) {
            if (pinyinComposing && rimeReady()) {
                rimeController.processKey("{Return}", (char) 0);
            } else {
                commitInput();
                keyDownUp(KeyEvent.KEYCODE_ENTER);
            }
        } else if (primaryCode == Keyboard.KEYCODE_DELETE) {
            handleBackspace();
        } else if (primaryCode == Keyboard.KEYCODE_SHIFT) {
            handleShift();
        } else if (primaryCode == LatinKeyboard.KEYCODE_LANGUAGE_SWITCH) {
            handleLanguageSwitch();
        } else if (primaryCode == Keyboard.KEYCODE_MODE_CHANGE && mInputView != null) {
            Keyboard current = mInputView.getKeyboard();
            if (current == mSymbolsKeyboard || current == mSymbolsShiftedKeyboard) {
                setLatinKeyboard(mQwertyKeyboard);
            } else {
                setLatinKeyboard(mSymbolsKeyboard);
                mSymbolsKeyboard.setShifted(false);
            }
        } else {
            handleCharacter(primaryCode);
        }
    }

    public void onText(CharSequence text) {
    }

    private void handleBackspace() {
        if (pinyinComposing && rimeReady()) {
            rimeController.processKey("{BackSpace}", (char) 0);
            return;
        }
        keyDownUp(KeyEvent.KEYCODE_DEL);
        updateShiftKeyState(getCurrentInputEditorInfo());

        if (compositionText.length() >= 1) {
            compositionText.deleteCharAt(compositionText.length() - 1);
        }
        updateCandidateViewAndComposingText();
    }

    private void updateCandidateViewAndComposingText() {
        List<String> candidates = candidateProvider == null
                ? new ArrayList<>()
                : candidateProvider.getDisplayCandidates(compositionText.toString(), inputMode);
        updateCandidatesList(candidates);

        getCurrentInputConnection().setComposingText(compositionText, compositionText.length());
    }

    private void handleShift() {
        if (mInputView == null) {
            return;
        }

        Keyboard currentKeyboard = mInputView.getKeyboard();
        if (mQwertyKeyboard == currentKeyboard) {
            // Alphabet keyboard
            checkToggleCapsLock();
            mInputView.setShifted(mCapsLock || !mInputView.isShifted());
        } else if (currentKeyboard == mSymbolsKeyboard) {
            mSymbolsKeyboard.setShifted(true);
            setLatinKeyboard(mSymbolsShiftedKeyboard);
            mSymbolsShiftedKeyboard.setShifted(true);
        } else if (currentKeyboard == mSymbolsShiftedKeyboard) {
            mSymbolsShiftedKeyboard.setShifted(false);
            setLatinKeyboard(mSymbolsKeyboard);
            mSymbolsKeyboard.setShifted(false);
        }
    }

    private void handleCharacter(int primaryCode) {
        if (useRimePinyin()) {
            String key = rimeKeyFor(primaryCode);
            if (key != null) {
                rimeController.processKey(key, Character.toLowerCase((char) primaryCode));
                return;
            }
        }
        if (isInputViewShown()) {
            if (mInputView.isShifted()) {
                primaryCode = Character.toUpperCase(primaryCode);
            }
        }
        char ch = (char) primaryCode;
        compositionText.append(ch);
        if (Character.isLetter(ch)) {
            updateCandidateViewAndComposingText();
        } else { // If char not in [a~z] or [A~Z], commit whole composition text.
            commitInput();
        }
        updateShiftKeyState(getCurrentInputEditorInfo());
    }

    private boolean rimeReady() {
        return rimeController != null && rimeController.isReady();
    }

    /**
     * Route keys to the rime engine once it is ready and no legacy-style
     * composition is in flight (the cedict fallback keeps serving keys typed
     * while the engine was still booting).
     */
    private boolean useRimePinyin() {
        return inputMode == InputMode.Pinyin && rimeReady() && compositionText.length() == 0;
    }

    /**
     * Maps a primary key code to the my_rime key format: bare a-z0-9 and
     * space, "{keysym}" for punctuation. Returns null for keys the engine
     * should not see.
     */
    private static String rimeKeyFor(int primaryCode) {
        char ch = Character.toLowerCase((char) primaryCode);
        if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == ' ') {
            return String.valueOf(ch);
        }
        String keysym = PUNCTUATION_KEYS.get(ch);
        return keysym == null ? null : "{" + keysym + "}";
    }

    // ------------------------------------------------------------------
    // rime engine callbacks (main thread)
    // ------------------------------------------------------------------

    @Override
    public void onRimeReady() {
        // nothing to do; the next keystroke in Pinyin mode goes to the engine
    }

    @Override
    public void onRimeResult(RimeWasmEngine.Result result, char passthroughChar) {
        // Stale results after switching back to English are dropped; the
        // composition was already finalized by abandonPinyinComposition().
        if (inputMode != InputMode.Pinyin || getCurrentInputConnection() == null) {
            return;
        }
        switch (result.state) {
            case RimeWasmEngine.STATE_COMMITTED:
                if (result.committed != null && !result.committed.isEmpty()) {
                    getCurrentInputConnection().commitText(result.committed, 1);
                }
                pinyinComposing = false;
                clearPinyinUi();
                break;
            case RimeWasmEngine.STATE_ACCEPTED:
                if (result.committed != null && !result.committed.isEmpty()) {
                    getCurrentInputConnection().commitText(result.committed, 1);
                }
                getCurrentInputConnection().setComposingText(result.preedit(), 1);
                pinyinComposing = !result.preedit().isEmpty();
                List<String> texts = new ArrayList<>(result.candidates.size());
                for (RimeWasmEngine.Candidate c : result.candidates) {
                    texts.add(c.text);
                }
                updateCandidatesList(texts);
                break;
            case RimeWasmEngine.STATE_REJECTED:
                getCurrentInputConnection().setComposingText("", 0);
                getCurrentInputConnection().finishComposingText();
                pinyinComposing = false;
                clearPinyinUi();
                break;
            default: // UNHANDLED: commit the raw character, like the my_rime editor
                if (passthroughChar != 0) {
                    getCurrentInputConnection().commitText(
                            String.valueOf(passthroughChar), 1);
                }
                break;
        }
    }

    /** A candidate was tapped; rime compositions select by page index. */
    public void onCandidateSelected(int index, String candidate) {
        if (pinyinComposing && rimeReady()) {
            rimeController.selectCandidate(index);
        } else {
            getCurrentInputConnection().commitText(candidate, candidate.length());
            reset();
        }
    }

    private void clearPinyinUi() {
        getCurrentInputConnection().finishComposingText();
        updateCandidatesList(new ArrayList<>());
    }

    /** Drops any live rime composition (mode switch, input finished, ...). */
    private void abandonPinyinComposition() {
        if (pinyinComposing && rimeReady()) {
            rimeController.processKey("{Escape}", (char) 0);
        }
        if (pinyinComposing && getCurrentInputConnection() != null) {
            getCurrentInputConnection().finishComposingText();
        }
        pinyinComposing = false;
    }

    public void reset() {
        compositionText = new StringBuilder();
        updateCandidateViewAndComposingText();
    }

    private void commitInput() {
        getCurrentInputConnection().commitText(compositionText.toString(), compositionText.length());
        reset();
    }

    private void handleLanguageSwitch() {
        abandonPinyinComposition();
        reset();
        inputMode = inputMode == InputMode.English ? InputMode.Pinyin : InputMode.English;
        updateStatusOfSwitchKey();
    }

    private void updateStatusOfSwitchKey() {
        // like fcitx5-android, the space bar carries the language indicator
        CharSequence spaceLabel = inputMode == InputMode.Pinyin ? "中文" : "EN";
        if (mQwertyKeyboard != null) {
            mQwertyKeyboard.setSpaceLanguageLabel(spaceLabel);
        }
        if (mSymbolsKeyboard != null) {
            mSymbolsKeyboard.setSpaceLanguageLabel(spaceLabel);
        }
        if (mSymbolsShiftedKeyboard != null) {
            mSymbolsShiftedKeyboard.setSpaceLanguageLabel(spaceLabel);
        }
        if (mInputView != null) {
            mInputView.invalidateAllKeys();
        }
    }

    private void checkToggleCapsLock() {
        long now = System.currentTimeMillis();
        if (mLastShiftTime + 800 > now) {
            mCapsLock = !mCapsLock;
            mLastShiftTime = 0;
        } else {
            mLastShiftTime = now;
        }
    }

    public void swipeRight() {
    }

    public void swipeLeft() {
    }

    public void swipeDown() {
    }

    public void swipeUp() {
    }

    public void onPress(int primaryCode) {
    }

    public void onRelease(int primaryCode) {
    }
}
