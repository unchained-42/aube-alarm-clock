# WorkManager instantiates Worker subclasses via reflection (by fully-qualified class name
# stored in the WorkSpec, using the (Context, WorkerParameters) constructor) — without a keep
# rule R8 can rename or strip either and break that lookup at runtime with no compile-time
# warning.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ZXing decodes with a small, resource-driven dispatcher rather than compile-time-visible
# references everywhere; keeping the whole (small) library avoids relying on every one of
# those paths being reachable to R8's static analysis.
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
