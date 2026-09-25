# Keep class and member names so stack traces in the app's log files are readable without a mapping file.
-dontobfuscate

# The accessibility service, activity and debug receiver are referenced from the manifest (kept by AAPT).
# kotlinx.serialization and OkHttp ship their own consumer rules.
