# This is a configuration file for ProGuard.
# http://proguard.sourceforge.net/index.html#manual/usage.html

-dontusemixedcaseclassnames
# AlarmManager seems to call onReceive in its own process
# mayhap it is just a pain to assume this.
# In actuality, theaea should not be imported from the non-nested api(...)
# annotations, though.

# Preserve line numbers for debugging stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
