# JNI symbols are resolved by the existing tv.iqtvlite.app.RustBridge class name.
-keep class tv.iqtvlite.app.RustBridge { *; }
# Parsed EPG cache is private Java serialization; preserve its schema.
-keep class com.cjlhll.iptv.EpgData { *; }
-keep class com.cjlhll.iptv.EpgProgram { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
