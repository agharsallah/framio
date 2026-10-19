-dontobfuscate

# R8 merges small classes (coroutine continuations, lambdas) across libraries into one class. A merged class then
# carries androidx's SDK_INT-guarded calls under one of our names, and the API 23 guard can't tell them apart (it
# reported android.app.RemoteAction "used by" SettingsStore$1). Keeping our and the plain-Java libraries' classes
# as classes (members still shrink, inline and optimize; +16 kB) stops that merging, so references keep their owner.
# The guard also attributes code in R8's merged lambda groups by line now (tools/api23_guard.py, M2).
-keep,allowshrinking,allowobfuscation,allowoptimization class dev.framio.pomodoro.**
-keep,allowshrinking,allowobfuscation,allowoptimization class kotlinx.coroutines.** { }
-keep,allowshrinking,allowobfuscation,allowoptimization class kotlinx.serialization.** { }
