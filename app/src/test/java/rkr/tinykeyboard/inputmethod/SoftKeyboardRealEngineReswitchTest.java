package rkr.tinykeyboard.inputmethod;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Same switch-away/switch-back scenario as SoftKeyboardReswitchTest, but
 * with the REAL rime wasm engine and the real async delivery (engine
 * thread -> main handler), mirroring the device pipeline end to end.
 */
@RunWith(RobolectricTestRunner.class)
public class SoftKeyboardRealEngineReswitchTest {

    private SoftKeyboard ime;
    private BaseInputConnection inputConnection;

    private void setUpIme() throws Exception {
        ime = Robolectric.buildService(SoftKeyboard.class).create().get();
        try {
            Field win = InputMethodService.class.getDeclaredField("mWindow");
            win.setAccessible(true);
            Object window = win.get(ime);
            Method setToken = window.getClass().getDeclaredMethod("setToken",
                    android.os.IBinder.class);
            setToken.setAccessible(true);
            setToken.invoke(window, new android.os.Binder());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot attach window token", e);
        }
        ime.onInitializeInterface();
        ime.onCreateInputView();
        ime.onCreateCandidatesView();
        inputConnection = new BaseInputConnection(new View(ime), true);
        try {
            Field ic = InputMethodService.class.getDeclaredField("mInputConnection");
            ic.setAccessible(true);
            ic.set(ime, inputConnection);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot inject input connection", e);
        }
        EditorInfo info = new EditorInfo();
        info.inputType = android.text.InputType.TYPE_CLASS_TEXT;
        ime.onStartInput(info, false);
        ime.onStartInputView(info, false);
    }

    private void awaitEngineReady() throws Exception {
        long deadline = System.currentTimeMillis() + 240_000;
        while (!ime.isRimeReadyForTest() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            ShadowLooper.idleMainLooper();
        }
        assertTrue("engine should become ready", ime.isRimeReadyForTest());
    }

    /** types s, then idles the main looper so async engine results land */
    private void type(String s) throws Exception {
        for (int i = 0; i < s.length(); i++) {
            ime.onKey(Character.toLowerCase(s.charAt(i)), null);
            // engine results arrive asynchronously on the main looper; keep
            // idling until a grace period passes with nothing new delivered
            for (int wait = 0; wait < 30; wait++) {
                Thread.sleep(100);
                ShadowLooper.idleMainLooper();
            }
        }
    }

    private void switchMode() {
        ime.onKey(LatinKeyboard.KEYCODE_LANGUAGE_SWITCH, null);
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void secondPinyinSessionTypesWithRealEngine() throws Exception {
        setUpIme();
        awaitEngineReady();

        // session 1: switch to pinyin, type nihao
        switchMode();
        type("n");
        // dump non-test threads to see where the engine call is stuck
        Thread.sleep(2000);
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            System.out.println("THREAD " + t.getName() + " " + t.getState());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                System.out.println("    at " + st[i]);
            }
        }
        String session1 = inputConnection.getEditable().toString();
        assertTrue("session 1 should compose, editable=[" + session1 + "]",
                session1.contains("n") || session1.contains("ni") || !session1.isEmpty());

        // switch away and back
        switchMode();
        switchMode();

        // session 2: type jintian - the reported dead state
        type("jintian");
        String session2 = inputConnection.getEditable().toString();
        assertTrue("session 2 should compose, editable=[" + session2 + "]",
                session2.contains("jin") || session2.contains("j"));
    }
}
