# The game has no reflection, no serialization library and no JNI, so the
# default optimized rules are enough. Keep only the entry points the platform
# instantiates by name.
-keep class com.crylo.ludo.SetupActivity { *; }
-keep class com.crylo.ludo.GameActivity { *; }
