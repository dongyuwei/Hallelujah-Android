package rkr.tinykeyboard.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

import rkr.tinykeyboard.inputmethod.rime.RimeController;
import rkr.tinykeyboard.inputmethod.rime.RimeWasmEngine;

/**
 * Reproduces the on-device report "second switch to pinyin is dead" against
 * the real SoftKeyboard state machine: keys pressed after switching
 * pinyin -> english -> pinyin must still reach the rime controller.
 */
@RunWith(RobolectricTestRunner.class)
public class SoftKeyboardReswitchTest {

    /** Records every key the keyboard routes to the rime controller. */
    private static class FakeController extends RimeController {
        final List<String> keys = new ArrayList<>();
        RimeController.Listener listener;
        boolean ready = true;
        /** when set, delivered synchronously to the listener for each key */
        RimeWasmEngine.Result nextResult;

        FakeController() {
            super(null);
        }

        @Override
        public boolean isReady() {
            return ready;
        }

        @Override
        public void setListener(RimeController.Listener l) {
            listener = l;
        }

        @Override
        public void processKey(String key, char passthroughChar) {
            keys.add(key);
            if (nextResult != null && listener != null) {
                listener.onRimeResult(nextResult, passthroughChar);
            }
        }

        @Override
        public void selectCandidate(int index) {
            keys.add("#select" + index);
        }

        @Override
        public void destroy() {
        }
    }

    private SoftKeyboard ime;
    private FakeController rime;

    @Before
    public void setUp() {
        ime = Robolectric.buildService(SoftKeyboard.class).create().get();
        // replace the real engine controller (which boots wasm in onCreate)
        // with a synchronous fake so the test drives only the state machine
        rime = new FakeController();
        ime.rimeController.destroy(); // stop the real engine's threads
        ime.rimeController = rime;
        rime.setListener(ime);

        // the IME window token is normally set by the system before the
        // window shows; attachToken is hidden API, call it reflectively
        try {
            java.lang.reflect.Field win = android.inputmethodservice.InputMethodService.class
                    .getDeclaredField("mWindow");
            win.setAccessible(true);
            Object window = win.get(ime);
            java.lang.reflect.Method setToken = window.getClass()
                    .getDeclaredMethod("setToken", android.os.IBinder.class);
            setToken.setAccessible(true);
            setToken.invoke(window, new android.os.Binder());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot attach window token", e);
        }
        ime.onInitializeInterface();
        ime.onCreateInputView();
        ime.onCreateCandidatesView();
        EditorInfo info = new EditorInfo();
        info.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        // InputMethodService keeps no public API for injecting an input
        // connection; set the framework's mInputConnection reflectively
        try {
            java.lang.reflect.Field ic = android.inputmethodservice.InputMethodService.class
                    .getDeclaredField("mInputConnection");
            ic.setAccessible(true);
            ic.set(ime, new BaseInputConnection(new View(ime), false));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot inject input connection", e);
        }
        ime.onStartInput(info, false);
        ime.onStartInputView(info, false);
    }

    @Test
    public void keysReachRimeAfterSecondSwitch() {
        // session 1: switch to pinyin and type
        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null);
        assertTrue(ime.isPinyinModeForTest());
        ime.onKey(110, null); // n
        assertEquals(1, rime.keys.size());
        assertEquals("n", rime.keys.get(0));

        // switch away and back
        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null); // -> english
        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null); // -> pinyin
        assertTrue(ime.isPinyinModeForTest());

        // session 2: keys must still reach the engine
        ime.onKey(106, null); // j
        assertEquals(2, rime.keys.size());
        assertEquals("j", rime.keys.get(1));
    }

    @Test
    public void escapeIsSentOnSwitchWhileComposing() {
        // composing state: the fake delivers an ACCEPTED result for each key
        rime.nextResult = acceptedResult("n");

        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null); // -> pinyin
        ime.onKey(110, null); // n, composing now
        ShadowLooper.idleMainLooper();

        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null); // -> english
        assertEquals("{Escape}", rime.keys.get(rime.keys.size() - 1));

        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null); // -> pinyin
        ime.onKey(110, null); // n again
        assertEquals("n", rime.keys.get(rime.keys.size() - 1));
    }

    // ------------------------------------------------------------------

    private static RimeWasmEngine.Result acceptedResult(String preedit) {
        RimeWasmEngine.Result r = new RimeWasmEngine.Result();
        r.state = RimeWasmEngine.STATE_ACCEPTED;
        r.body = preedit;
        r.candidates = new ArrayList<>();
        r.candidates.add(new RimeWasmEngine.Candidate("候选", null));
        return r;
    }
}
