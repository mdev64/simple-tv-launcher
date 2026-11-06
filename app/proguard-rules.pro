# Minimal rules — no reflection, no Gson, no coroutines internals.
# AGP auto-generates keep rules for the manifest (Activity entry points).

# Line numbers only if a stack trace ever matters; strip source file names for size.
-keepattributes LineNumberTable,SourceFile
-renamesourcefileattribute T

# Drop Kotlin stdlib's unused coroutine/metadata helpers aggressively.
-dontwarn kotlin.**
-dontwarn kotlinx.**

# No custom views referenced from XML, so nothing to keep there.
