# Only used when building with -Pminify (R8 is off by default). Test on a device before shipping.

# NewPipeExtractor: Rhino (YouTube signature/throttling JS) and the extractor itself use reflection.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**

# OkHttp optional TLS providers
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
