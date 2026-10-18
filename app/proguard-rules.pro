# kotlinx.serialization: keep generated serializers of our @Serializable models.
-keepclassmembers @kotlinx.serialization.Serializable class app.remotely.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.remotely.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp optional platform classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
