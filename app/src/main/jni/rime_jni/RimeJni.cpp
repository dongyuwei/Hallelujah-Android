// Minimal JNI bridge between the Android IME (Java) and librime's C API.
#include <jni.h>
#include <android/log.h>
#include <rime_api.h>

#define LOG_TAG "rime.hallelujah"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

RimeApi* rime = nullptr;
RimeSessionId session = 0;

void notificationHandler(void* context_object, RimeSessionId session_id,
                         const char* message_type, const char* message_value) {
  LOGI("notification: %s / %s", message_type ? message_type : "",
       message_value ? message_value : "");
}

jstring toJString(JNIEnv* env, const char* text) {
  return text ? env->NewStringUTF(text) : nullptr;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeStartup(
    JNIEnv* env, jclass, jstring sharedDataDir, jstring userDataDir,
    jboolean fullCheck) {
  rime = rime_get_api();
  if (!rime) {
    LOGE("rime_get_api() failed");
    return JNI_FALSE;
  }
  const char* shared = env->GetStringUTFChars(sharedDataDir, nullptr);
  const char* user = env->GetStringUTFChars(userDataDir, nullptr);

  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = shared;
  traits.user_data_dir = user;
  traits.log_dir = "";  // stderr only; visible through logcat
  traits.min_log_level = 1;
  traits.distribution_name = "Hallelujah";
  traits.distribution_code_name = "Hallelujah-Android";
  traits.distribution_version = "1.0";
  traits.app_name = "rkr.tinykeyboard.inputmethod.rime";
  rime->setup(&traits);
  rime->initialize(&traits);
  rime->set_notification_handler(notificationHandler, nullptr);
  rime->start_maintenance(fullCheck ? True : False);

  env->ReleaseStringUTFChars(sharedDataDir, shared);
  env->ReleaseStringUTFChars(userDataDir, user);

  session = rime->create_session();
  LOGI("startup done, session=%lu full_check=%d", (unsigned long) session,
       fullCheck ? 1 : 0);
  return session ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeProcessKey(
    JNIEnv*, jclass, jint keysym, jint mask) {
  if (!rime || !session) {
    return JNI_FALSE;
  }
  return rime->process_key(session, keysym, mask) ? JNI_TRUE : JNI_FALSE;
}

// Returns an rkr.tinykeyboard.inputmethod.RimeResponse holding everything the
// IME needs after a key event: pending commit text, composition preedit, the
// candidates of the current page and the highlighted index.
extern "C" JNIEXPORT jobject JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeGetResponse(JNIEnv* env,
                                                               jclass) {
  jstring commitText = nullptr;
  jstring preedit = nullptr;
  jobjectArray candidates = nullptr;
  jint highlighted = -1;

  if (rime && session) {
    RIME_STRUCT(RimeCommit, commit);
    if (rime->get_commit(session, &commit)) {
      commitText = toJString(env, commit.text);
      rime->free_commit(&commit);
    }
    RIME_STRUCT(RimeContext, context);
    if (rime->get_context(session, &context)) {
      preedit = toJString(env, context.composition.preedit);
      int count = context.menu.num_candidates;
      if (count > 9) count = 9;
      jclass stringClass = env->FindClass("java/lang/String");
      candidates = env->NewObjectArray(count, stringClass, nullptr);
      for (int i = 0; i < count; i++) {
        env->SetObjectArrayElement(
            candidates, i, toJString(env, context.menu.candidates[i].text));
      }
      highlighted = context.menu.highlighted_candidate_index;
      rime->free_context(&context);
    }
  }

  jclass responseClass =
      env->FindClass("rkr/tinykeyboard/inputmethod/RimeResponse");
  jmethodID constructor = env->GetMethodID(
      responseClass, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;I)V");
  return env->NewObject(responseClass, constructor, commitText, preedit,
                        candidates, highlighted);
}

extern "C" JNIEXPORT void JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeSelectCandidate(
    JNIEnv*, jclass, jint index) {
  if (rime && session) {
    rime->select_candidate_on_current_page(session, index);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeClearComposition(JNIEnv*,
                                                                    jclass) {
  if (rime && session) {
    rime->clear_composition(session);
  }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeIsMaintenancing(JNIEnv*,
                                                                   jclass) {
  return (rime && rime->is_maintenance_mode()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_rkr_tinykeyboard_inputmethod_RimeEngine_nativeFinalize(JNIEnv*, jclass) {
  if (rime) {
    rime->finalize();
    session = 0;
    rime = nullptr;
  }
}
