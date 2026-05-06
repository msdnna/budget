# Project-specific R8 / ProGuard rules. Library rules are bundled in their AARs
# (Compose, Room, Retrofit 3, OkHttp 5, Coil 2, Coroutines) and don't need to
# be repeated here.

# ── Gson model classes ──────────────────────────────────────────────────────
# All API request/response and model types are deserialized via reflection by
# Gson — keep names and fields so @SerializedName mappings still resolve and
# default-arg constructors stay reachable.
-keep class website.msdnna.budget_app.data.model.** { *; }
-keepclassmembers class website.msdnna.budget_app.data.model.** { <init>(...); }

# Gson reads generic type parameters and annotations through reflection.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ── Retrofit ApiService ─────────────────────────────────────────────────────
# Retrofit builds a dynamic proxy from the interface; method names + return
# types must survive shrinking.
-keep class website.msdnna.budget_app.data.api.ApiService { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface website.msdnna.budget_app.data.api.ApiService {
    <methods>;
}

# ── Room entities ───────────────────────────────────────────────────────────
# Room generates *_Impl DAOs at compile time, but the Entity column metadata
# is read reflectively when the schema is verified on first open.
-keep class website.msdnna.budget_app.data.db.** { *; }
