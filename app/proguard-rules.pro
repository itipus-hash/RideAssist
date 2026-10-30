# 保留行号与异常信息，便于现场排障
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# BLE 协议解析模型（纯数据类，保留字段名便于日志）
-keep class com.yanyang.yybms.core.protocol.model.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**