# kotlinx.serialization: keep generated serializers of our @Serializable models.
-keepclassmembers @kotlinx.serialization.Serializable class app.zalatv.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class app.zalatv.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp optional platform classes.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
