# HiveMQ MQTT Client(用注解和反射,需保留)
-keep class com.hivemq.client.** { *; }
-dontwarn com.hivemq.client.**

# 保留协议消息类(虽无反射,保险起见)
-keep class com.applecase.gateway.net.Protocol$Message { *; }

# 保留 Parcelable/Serializable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
