# Keep protobuf lite
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Protobuf generated messages (app/src/main/proto) are parsed via GeneratedMessageLite
# and rely on field names like `xxx_` at runtime; R8 obfuscation of these fields breaks parsing.
-keep class blbl.cat3399.proto.** { *; }

# Keep IjkPlayer Java API names (JNI depends on stable class/method/field names).
# Without this, release (R8) obfuscation can make `libijkplayer.so` fail to bind and crash on startup.
-keep class tv.danmaku.ijk.** { *; }
-keep class com.debugly.ijkplayer.** { *; }
