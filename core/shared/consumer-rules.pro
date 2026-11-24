# nuvx-core via UniFFI + JNA: JNA finds native functions and structures by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class com.licht.nuvx.core.** { *; }
-dontwarn java.awt.**
