# JNI symbols are resolved by the existing tv.iqtvlite.app.RustBridge class name.
-keep class tv.iqtvlite.app.RustBridge { *; }
-keepnames class com.cjlhll.iptv.EpgData
-keepnames class com.cjlhll.iptv.EpgProgram
-keepattributes Signature,InnerClasses,EnclosingMethod
