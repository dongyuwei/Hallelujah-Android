# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Endive (wasm runtime driving the rime engine) dispatches from bytecode we
# cannot statically see; keep it intact rather than rely on consumer rules.
-keep class run.endive.** { *; }

# If you keep the line number information, uncomment the
# following line to hide the original source file name.
#-renamesourcefileattribute SourceFile
