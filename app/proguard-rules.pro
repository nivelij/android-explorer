# Commons Compress references optional codecs (zstd, brotli) that we don't ship;
# keep it quiet about them and don't strip the classes we do use reflectively.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }

# junrar
-dontwarn com.github.junrar.**
-keep class com.github.junrar.** { *; }
