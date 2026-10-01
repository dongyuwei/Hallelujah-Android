# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Endive (wasm runtime driving the rime engine) dispatches from bytecode we
# cannot statically see; keep it intact rather than rely on consumer rules.
-keep class run.endive.** { *; }

# Gson reflects on generic signatures (TypeToken anonymous subclasses):
# without these the dictionary loader crashes with "Missing type parameter"
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# the IME itself is small; keep names so stack traces stay readable
-keep class rkr.tinykeyboard.inputmethod.** { *; }

# If you keep the line number information, uncomment the
# following line to hide the original source file name.
#-renamesourcefileattribute SourceFile
