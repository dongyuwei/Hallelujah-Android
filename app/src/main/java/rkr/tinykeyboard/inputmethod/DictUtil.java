package rkr.tinykeyboard.inputmethod;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

public class DictUtil {
    static String getFileContentFromAssets(Context context, String fileName) {
        String content;
        try {
            InputStream is = context.getAssets().open(fileName);

            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            content = new String(buffer, "UTF-8");
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }

        return content;
    }
}
