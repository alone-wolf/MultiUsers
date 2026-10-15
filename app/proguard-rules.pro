# R8 rules for the release build. The app has no reflection-based entry points of its
# own; these rules document the AndroidX components that are instantiated by name.

# WorkManager resolves Worker subclasses reflectively.
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Manifest-declared components (AGP also keeps these automatically).
-keep class top.writerpass.multiusers.MultiUsersApp { *; }
-keep class top.writerpass.multiusers.MainActivity { *; }
