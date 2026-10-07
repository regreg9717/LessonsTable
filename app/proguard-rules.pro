# POI 引用了一些桌面 JVM 才有的可选类，运行时读取路径用不到
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.apache.poi.**
-dontwarn org.brotli.dec.BrotliInputStream
-dontwarn com.graphbuilder.**
