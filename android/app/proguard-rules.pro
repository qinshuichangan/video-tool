# JNI：native 方法和它们所在的类不能被改名
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.example.videotool.NativeEncoders { *; }
