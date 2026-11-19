# Validation-only ABI roots consumed by the unshrunk Android instrumentation APK.
# Generated from test DEX definitions/references, not a blanket Kotlin/AndroidX keep.
# Facade family member matching preserves declarations inherited from Kotlin multifile parts.
# Regenerate explicitly with scripts/generate_instrumentation_keep.py after test API changes.

-keep class androidx.concurrent.futures.AbstractResolvableFuture
-keepclassmembers class androidx.concurrent.futures.AbstractResolvableFuture {
    androidx.concurrent.futures.ResolvableFuture create();
    boolean cancel(boolean);
    boolean isCancelled();
    boolean isDone();
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
    java.lang.Object get();
    java.lang.Object get(long,java.util.concurrent.TimeUnit);
    java.lang.Object getUninterruptibly(java.util.concurrent.Future);
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter$Completer
-keepclassmembers class androidx.concurrent.futures.CallbackToFutureAdapter$Completer {
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
}
-keep class androidx.concurrent.futures.CallbackToFutureAdapter$Resolver
-keep class androidx.concurrent.futures.CallbackToFutureAdapter
-keepclassmembers class androidx.concurrent.futures.CallbackToFutureAdapter {
    com.google.common.util.concurrent.ListenableFuture getFuture(androidx.concurrent.futures.CallbackToFutureAdapter$Resolver);
}
-keep class androidx.concurrent.futures.DirectExecutor
-keepclassmembers class androidx.concurrent.futures.DirectExecutor {
    androidx.concurrent.futures.DirectExecutor INSTANCE;
}
-keep class androidx.concurrent.futures.ResolvableFuture
-keepclassmembers class androidx.concurrent.futures.ResolvableFuture {
    androidx.concurrent.futures.ResolvableFuture create();
    boolean cancel(boolean);
    boolean isCancelled();
    boolean isDone();
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
    java.lang.Object get();
    java.lang.Object get(long,java.util.concurrent.TimeUnit);
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
-keep class androidx.lifecycle.Lifecycle$State
-keepclassmembers class androidx.lifecycle.Lifecycle$State {
    androidx.lifecycle.Lifecycle$State CREATED;
    androidx.lifecycle.Lifecycle$State DESTROYED;
    androidx.lifecycle.Lifecycle$State RESUMED;
    androidx.lifecycle.Lifecycle$State STARTED;
    androidx.lifecycle.Lifecycle$State[] values();
}
-keep class androidx.media3.common.AudioAttributes$Builder
-keepclassmembers class androidx.media3.common.AudioAttributes$Builder {
    <init>();
    androidx.media3.common.AudioAttributes build();
    androidx.media3.common.AudioAttributes$Builder setContentType(int);
    androidx.media3.common.AudioAttributes$Builder setUsage(int);
}
-keep class androidx.media3.common.AudioAttributes
-keep class androidx.media3.common.Format
-keepclassmembers class androidx.media3.common.Format {
    java.lang.String sampleMimeType;
}
-keep class androidx.media3.common.Player
-keepclassmembers class androidx.media3.common.Player {
    void addAnalyticsListener(androidx.media3.exoplayer.analytics.AnalyticsListener);
    void setAudioAttributes(androidx.media3.common.AudioAttributes,boolean);
}
-keep class androidx.media3.common.util.UnstableApi
-keep class androidx.media3.exoplayer.DecoderReuseEvaluation
-keep class androidx.media3.exoplayer.ExoPlayer$Builder
-keepclassmembers class androidx.media3.exoplayer.ExoPlayer$Builder {
    <init>(android.content.Context);
    androidx.media3.exoplayer.ExoPlayer build();
}
-keep class androidx.media3.exoplayer.ExoPlayer
-keepclassmembers class androidx.media3.exoplayer.ExoPlayer {
    void addAnalyticsListener(androidx.media3.exoplayer.analytics.AnalyticsListener);
    void setAudioAttributes(androidx.media3.common.AudioAttributes,boolean);
}
-keep class androidx.media3.exoplayer.analytics.AnalyticsListener$EventTime
-keep class androidx.media3.exoplayer.analytics.AnalyticsListener
-keep class androidx.media3.exoplayer.audio.AudioSink$AudioTrackConfig
-keep class androidx.tracing.Trace
-keepclassmembers class androidx.tracing.Trace {
    void beginSection(java.lang.String);
    void endSection();
    void forceEnableAppTracing();
}
-keep class com.cjlhll.iptv.AudioDiagnosticFields { *; }
-keep class com.cjlhll.iptv.CatchupPlayRequest { *; }
-keep class com.cjlhll.iptv.Channel { *; }
-keep class com.cjlhll.iptv.ChannelGrouper { *; }
-keep class com.cjlhll.iptv.EpgData { *; }
-keep class com.cjlhll.iptv.EpgLoadResult { *; }
-keep class com.cjlhll.iptv.EpgProgram { *; }
-keep class com.cjlhll.iptv.EpgRepository { *; }
-keep class com.cjlhll.iptv.LiveOwnerView { *; }
-keep class com.cjlhll.iptv.M3uParser { *; }
-keep class com.cjlhll.iptv.MediaPlaybackRequest { *; }
-keep class com.cjlhll.iptv.PlaybackFailure { *; }
-keep class com.cjlhll.iptv.PlaybackIntent$Companion { *; }
-keep class com.cjlhll.iptv.PlaybackIntent { *; }
-keep class com.cjlhll.iptv.PlaylistApplication { *; }
-keep class com.cjlhll.iptv.PlaylistCommit { *; }
-keep class com.cjlhll.iptv.PlaylistSelection { *; }
-keep class com.cjlhll.iptv.PreparedPlayback { *; }
-keep class com.cjlhll.iptv.ReturnToLive { *; }
-keep class com.cjlhll.iptv.SourceConfigLanServer$Info { *; }
-keep class com.cjlhll.iptv.SourceConfigLanServer { *; }
-keep class com.cjlhll.iptv.StreamPlayback { *; }
-keep class com.google.common.util.concurrent.ListenableFuture
-keepclassmembers class com.google.common.util.concurrent.ListenableFuture {
    androidx.concurrent.futures.ResolvableFuture create();
    boolean cancel(boolean);
    boolean isCancelled();
    boolean isDone();
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
    java.lang.Object get();
    java.lang.Object get(long,java.util.concurrent.TimeUnit);
    java.lang.Object getUninterruptibly(java.util.concurrent.Future);
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
-keep class kotlin.Deprecated
-keep class kotlin.DeprecationLevel
-keepclassmembers class kotlin.DeprecationLevel {
    kotlin.DeprecationLevel ERROR;
    kotlin.DeprecationLevel WARNING;
}
-keep class kotlin.Function
-keepclassmembers class kotlin.Function {
    <init>(int);
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    <init>(int,kotlin.coroutines.Continuation);
    java.lang.Object invoke();
    java.lang.Object invoke(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object);
    java.lang.Object receiver;
}
-keep class kotlin.Lazy
-keepclassmembers class kotlin.Lazy {
    java.lang.Object getValue();
}
-keep class kotlin.LazyKt
-keepclassmembers class kotlin.LazyKt** {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.LazyKt__LazyJVMKt
-keepclassmembers class kotlin.LazyKt__LazyJVMKt** {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.LazyKt__LazyKt
-keepclassmembers class kotlin.LazyKt__LazyKt** {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep class kotlin.Pair
-keepclassmembers class kotlin.Pair {
    java.lang.Object component1();
    java.lang.Object component2();
}
-keep class kotlin.ReplaceWith
-keep class kotlin.Result$Companion
-keep class kotlin.Result
-keepclassmembers class kotlin.Result {
    java.lang.Object constructor-impl(java.lang.Object);
    java.lang.Throwable exceptionOrNull-impl(java.lang.Object);
    kotlin.Result$Companion Companion;
}
-keep class kotlin.ResultKt
-keepclassmembers class kotlin.ResultKt** {
    java.lang.Object createFailure(java.lang.Throwable);
    void throwOnFailure(java.lang.Object);
}
-keep class kotlin.Unit
-keepclassmembers class kotlin.Unit {
    kotlin.Unit INSTANCE;
}
-keep class kotlin.collections.CollectionsKt
-keepclassmembers class kotlin.collections.CollectionsKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__CollectionsJVMKt
-keepclassmembers class kotlin.collections.CollectionsKt__CollectionsJVMKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__CollectionsKt
-keepclassmembers class kotlin.collections.CollectionsKt__CollectionsKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__IterablesKt
-keepclassmembers class kotlin.collections.CollectionsKt__IterablesKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__IteratorsJVMKt
-keepclassmembers class kotlin.collections.CollectionsKt__IteratorsJVMKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__IteratorsKt
-keepclassmembers class kotlin.collections.CollectionsKt__IteratorsKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__MutableCollectionsJVMKt
-keepclassmembers class kotlin.collections.CollectionsKt__MutableCollectionsJVMKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__MutableCollectionsKt
-keepclassmembers class kotlin.collections.CollectionsKt__MutableCollectionsKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt__ReversedViewsKt
-keepclassmembers class kotlin.collections.CollectionsKt__ReversedViewsKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt___CollectionsJvmKt
-keepclassmembers class kotlin.collections.CollectionsKt___CollectionsJvmKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.collections.CollectionsKt___CollectionsKt
-keepclassmembers class kotlin.collections.CollectionsKt___CollectionsKt** {
    java.lang.Object firstOrNull(java.util.List);
    java.lang.Object single(java.util.List);
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object);
    java.util.List listOf(java.lang.Object[]);
    java.util.List plus(java.util.Collection,java.lang.Object);
    java.util.List toMutableList(java.util.Collection);
}
-keep class kotlin.coroutines.AbstractCoroutineContextElement
-keep class kotlin.coroutines.Continuation
-keepclassmembers class kotlin.coroutines.Continuation {
    <init>(int,kotlin.coroutines.Continuation);
    <init>(kotlin.coroutines.Continuation);
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
    void invokeOnCancellation(kotlin.jvm.functions.Function1);
    void resume(java.lang.Object,kotlin.jvm.functions.Function1);
    void resumeWith(java.lang.Object);
}
-keep class kotlin.coroutines.ContinuationInterceptor
-keep class kotlin.coroutines.ContinuationKt
-keepclassmembers class kotlin.coroutines.ContinuationKt** {
    kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.CoroutineContext$Element
-keepclassmembers class kotlin.coroutines.CoroutineContext$Element {
    java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.CoroutineContext
-keepclassmembers class kotlin.coroutines.CoroutineContext {
    java.lang.Object await(kotlin.coroutines.Continuation);
    kotlin.coroutines.EmptyCoroutineContext INSTANCE;
}
-keep class kotlin.coroutines.EmptyCoroutineContext
-keepclassmembers class kotlin.coroutines.EmptyCoroutineContext {
    kotlin.coroutines.EmptyCoroutineContext INSTANCE;
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt
-keepclassmembers class kotlin.coroutines.intrinsics.IntrinsicsKt** {
    java.lang.Object getCOROUTINE_SUSPENDED();
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt
-keepclassmembers class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt** {
    java.lang.Object getCOROUTINE_SUSPENDED();
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt
-keepclassmembers class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt** {
    java.lang.Object getCOROUTINE_SUSPENDED();
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.BaseContinuationImpl
-keepclassmembers class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    <init>(int,kotlin.coroutines.Continuation);
    <init>(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.Boxing
-keepclassmembers class kotlin.coroutines.jvm.internal.Boxing {
    java.lang.Integer boxInt(int);
}
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl
-keepclassmembers class kotlin.coroutines.jvm.internal.ContinuationImpl {
    <init>(int,kotlin.coroutines.Continuation);
    <init>(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.CoroutineStackFrame
-keepclassmembers class kotlin.coroutines.jvm.internal.CoroutineStackFrame {
    <init>(int,kotlin.coroutines.Continuation);
    <init>(kotlin.coroutines.Continuation);
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
}
-keep class kotlin.coroutines.jvm.internal.DebugMetadata
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt
-keepclassmembers class kotlin.coroutines.jvm.internal.DebugProbesKt** {
    void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.SpillingKt
-keepclassmembers class kotlin.coroutines.jvm.internal.SpillingKt** {
    java.lang.Object nullOutSpilledVariable(java.lang.Object);
}
-keep class kotlin.coroutines.jvm.internal.SuspendFunction
-keepclassmembers class kotlin.coroutines.jvm.internal.SuspendFunction {
    <init>(int,kotlin.coroutines.Continuation);
}
-keep class kotlin.coroutines.jvm.internal.SuspendLambda
-keepclassmembers class kotlin.coroutines.jvm.internal.SuspendLambda {
    <init>(int,kotlin.coroutines.Continuation);
}
-keep class kotlin.io.CloseableKt
-keepclassmembers class kotlin.io.CloseableKt** {
    void closeFinally(java.io.Closeable,java.lang.Throwable);
}
-keep class kotlin.jvm.functions.Function0
-keepclassmembers class kotlin.jvm.functions.Function0 {
    java.lang.Object invoke();
}
-keep class kotlin.jvm.functions.Function1
-keep class kotlin.jvm.functions.Function2
-keep class kotlin.jvm.functions.Function4
-keepclassmembers class kotlin.jvm.functions.Function4 {
    java.lang.Object invoke(java.lang.Object,java.lang.Object,java.lang.Object,java.lang.Object);
}
-keep class kotlin.jvm.internal.CallableReference
-keepclassmembers class kotlin.jvm.internal.CallableReference {
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    java.lang.Object receiver;
}
-keep class kotlin.jvm.internal.DefaultConstructorMarker
-keep class kotlin.jvm.internal.FunctionBase
-keepclassmembers class kotlin.jvm.internal.FunctionBase {
    <init>(int);
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    <init>(int,kotlin.coroutines.Continuation);
    java.lang.Object receiver;
}
-keep class kotlin.jvm.internal.FunctionReference
-keepclassmembers class kotlin.jvm.internal.FunctionReference {
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    java.lang.Object receiver;
}
-keep class kotlin.jvm.internal.FunctionReferenceImpl
-keepclassmembers class kotlin.jvm.internal.FunctionReferenceImpl {
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    java.lang.Object receiver;
}
-keep class kotlin.jvm.internal.Intrinsics
-keepclassmembers class kotlin.jvm.internal.Intrinsics {
    boolean areEqual(java.lang.Object,java.lang.Object);
    void checkNotNull(java.lang.Object);
    void checkNotNull(java.lang.Object,java.lang.String);
    void checkNotNullExpressionValue(java.lang.Object,java.lang.String);
    void checkNotNullParameter(java.lang.Object,java.lang.String);
    void throwUninitializedPropertyAccessException(java.lang.String);
}
-keep class kotlin.jvm.internal.Lambda
-keepclassmembers class kotlin.jvm.internal.Lambda {
    <init>(int);
}
-keep class kotlin.jvm.internal.Ref$ObjectRef
-keepclassmembers class kotlin.jvm.internal.Ref$ObjectRef {
    <init>();
    java.lang.Object element;
}
-keep class kotlin.jvm.internal.StringCompanionObject
-keepclassmembers class kotlin.jvm.internal.StringCompanionObject {
    kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keep class kotlin.jvm.internal.markers.KMappedMarker
-keepclassmembers class kotlin.jvm.internal.markers.KMappedMarker {
    int size();
    java.lang.String get(java.lang.String);
    java.util.Iterator iterator();
    java.util.List values(java.lang.String);
    okhttp3.Headers$Builder newBuilder();
    okhttp3.Headers$Companion Companion;
}
-keep class kotlin.reflect.KCallable
-keepclassmembers class kotlin.reflect.KCallable {
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    java.lang.Object receiver;
}
-keep class kotlin.reflect.KFunction
-keepclassmembers class kotlin.reflect.KFunction {
    <init>(int,java.lang.Object,java.lang.Class,java.lang.String,java.lang.String,int);
    java.lang.Object receiver;
}
-keep class kotlin.text.CharsKt
-keepclassmembers class kotlin.text.CharsKt** {
    int checkRadix(int);
}
-keep class kotlin.text.CharsKt__CharJVMKt
-keepclassmembers class kotlin.text.CharsKt__CharJVMKt** {
    int checkRadix(int);
}
-keep class kotlin.text.CharsKt__CharKt
-keepclassmembers class kotlin.text.CharsKt__CharKt** {
    int checkRadix(int);
}
-keep class kotlin.text.Charsets
-keepclassmembers class kotlin.text.Charsets {
    java.nio.charset.Charset US_ASCII;
    java.nio.charset.Charset UTF_8;
}
-keep class kotlin.text.StringsKt
-keepclassmembers class kotlin.text.StringsKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__AppendableKt
-keepclassmembers class kotlin.text.StringsKt__AppendableKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__IndentKt
-keepclassmembers class kotlin.text.StringsKt__IndentKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__RegexExtensionsJVMKt
-keepclassmembers class kotlin.text.StringsKt__RegexExtensionsJVMKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__RegexExtensionsKt
-keepclassmembers class kotlin.text.StringsKt__RegexExtensionsKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringBuilderJVMKt
-keepclassmembers class kotlin.text.StringsKt__StringBuilderJVMKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringBuilderKt
-keepclassmembers class kotlin.text.StringsKt__StringBuilderKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringNumberConversionsJVMKt
-keepclassmembers class kotlin.text.StringsKt__StringNumberConversionsJVMKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringNumberConversionsKt
-keepclassmembers class kotlin.text.StringsKt__StringNumberConversionsKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringsJVMKt
-keepclassmembers class kotlin.text.StringsKt__StringsJVMKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt__StringsKt
-keepclassmembers class kotlin.text.StringsKt__StringsKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt___StringsJvmKt
-keepclassmembers class kotlin.text.StringsKt___StringsJvmKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.text.StringsKt___StringsKt
-keepclassmembers class kotlin.text.StringsKt___StringsKt** {
    boolean contains$default(java.lang.CharSequence,char,boolean,int,java.lang.Object);
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean equals(java.lang.String,java.lang.String,boolean);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    int indexOf$default(java.lang.CharSequence,char,int,boolean,int,java.lang.Object);
    java.lang.CharSequence trim(java.lang.CharSequence);
    java.lang.String repeat(java.lang.CharSequence,int);
    java.lang.String replace$default(java.lang.String,java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String substringBefore$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
    java.util.List split$default(java.lang.CharSequence,char[],boolean,int,int,java.lang.Object);
}
-keep class kotlin.time.Duration$Companion
-keep class kotlin.time.Duration
-keepclassmembers class kotlin.time.Duration {
    kotlin.time.Duration$Companion Companion;
}
-keep class kotlin.time.DurationKt
-keepclassmembers class kotlin.time.DurationKt** {
    long toDuration(int,kotlin.time.DurationUnit);
}
-keep class kotlin.time.DurationUnit
-keepclassmembers class kotlin.time.DurationUnit {
    kotlin.time.DurationUnit SECONDS;
}
-keep class kotlinx.coroutines.BuildersKt
-keepclassmembers class kotlinx.coroutines.BuildersKt** {
    java.lang.Object runBlocking$default(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
    java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2);
    kotlinx.coroutines.Deferred async$default(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2,int,java.lang.Object);
    kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2);
}
-keep class kotlinx.coroutines.CancellableContinuation$DefaultImpls
-keepclassmembers class kotlinx.coroutines.CancellableContinuation$DefaultImpls {
    boolean cancel$default(kotlinx.coroutines.CancellableContinuation,java.lang.Throwable,int,java.lang.Object);
}
-keep class kotlinx.coroutines.CancellableContinuation
-keepclassmembers class kotlinx.coroutines.CancellableContinuation {
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
    void invokeOnCancellation(kotlin.jvm.functions.Function1);
    void resume(java.lang.Object,kotlin.jvm.functions.Function1);
    void resumeWith(java.lang.Object);
}
-keep class kotlinx.coroutines.CancellableContinuationImpl
-keepclassmembers class kotlinx.coroutines.CancellableContinuationImpl {
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
}
-keep class kotlinx.coroutines.CoroutineDispatcher
-keep class kotlinx.coroutines.CoroutineScope
-keep class kotlinx.coroutines.CoroutineStart
-keepclassmembers class kotlinx.coroutines.CoroutineStart {
    kotlinx.coroutines.CoroutineStart DEFAULT;
    kotlinx.coroutines.CoroutineStart UNDISPATCHED;
}
-keep class kotlinx.coroutines.Deferred
-keepclassmembers class kotlinx.coroutines.Deferred {
    java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.DelayKt
-keepclassmembers class kotlinx.coroutines.DelayKt** {
    java.lang.Object delay(long,kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.DispatchedTask
-keepclassmembers class kotlinx.coroutines.DispatchedTask {
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
}
-keep class kotlinx.coroutines.Dispatchers
-keepclassmembers class kotlinx.coroutines.Dispatchers {
    kotlinx.coroutines.CoroutineDispatcher getUnconfined();
    kotlinx.coroutines.MainCoroutineDispatcher getMain();
}
-keep class kotlinx.coroutines.ExecutorsKt
-keepclassmembers class kotlinx.coroutines.ExecutorsKt** {
    kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}
-keep class kotlinx.coroutines.Job$DefaultImpls
-keepclassmembers class kotlinx.coroutines.Job$DefaultImpls {
    void cancel$default(kotlinx.coroutines.Job,java.util.concurrent.CancellationException,int,java.lang.Object);
}
-keep class kotlinx.coroutines.Job
-keepclassmembers class kotlinx.coroutines.Job {
    java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.MainCoroutineDispatcher
-keep class kotlinx.coroutines.TimeoutKt
-keepclassmembers class kotlinx.coroutines.TimeoutKt** {
    java.lang.Object withTimeout-KLykuaI(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep class kotlinx.coroutines.Waiter
-keepclassmembers class kotlinx.coroutines.Waiter {
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
}
-keep class kotlinx.coroutines.scheduling.Task
-keepclassmembers class kotlinx.coroutines.scheduling.Task {
    <init>(kotlin.coroutines.Continuation,int);
    java.lang.Object getResult();
    void initCancellability();
}
-keep class okhttp3.Handshake$Companion
-keepclassmembers class okhttp3.Handshake$Companion {
    okhttp3.Handshake get(javax.net.ssl.SSLSession);
}
-keep class okhttp3.Handshake
-keepclassmembers class okhttp3.Handshake {
    okhttp3.Handshake$Companion Companion;
    okhttp3.TlsVersion tlsVersion();
}
-keep class okhttp3.Headers$Builder
-keepclassmembers class okhttp3.Headers$Builder {
    <init>();
    okhttp3.Headers build();
    okhttp3.Headers$Builder add(java.lang.String);
    okhttp3.Headers$Builder add(java.lang.String,java.lang.String);
    okhttp3.Headers$Builder removeAll(java.lang.String);
}
-keep class okhttp3.Headers$Companion
-keepclassmembers class okhttp3.Headers$Companion {
    okhttp3.Headers of(java.lang.String[]);
}
-keep class okhttp3.Headers
-keepclassmembers class okhttp3.Headers {
    int size();
    java.lang.String get(java.lang.String);
    java.util.Iterator iterator();
    java.util.List values(java.lang.String);
    okhttp3.Headers$Builder newBuilder();
    okhttp3.Headers$Companion Companion;
}
-keep class okhttp3.HttpUrl$Builder
-keepclassmembers class okhttp3.HttpUrl$Builder {
    <init>();
    okhttp3.HttpUrl build();
    okhttp3.HttpUrl$Builder host(java.lang.String);
    okhttp3.HttpUrl$Builder port(int);
    okhttp3.HttpUrl$Builder scheme(java.lang.String);
}
-keep class okhttp3.HttpUrl$Companion
-keepclassmembers class okhttp3.HttpUrl$Companion {
    okhttp3.HttpUrl parse(java.lang.String);
}
-keep class okhttp3.HttpUrl
-keepclassmembers class okhttp3.HttpUrl {
    java.lang.String host();
    java.lang.String toString();
    okhttp3.HttpUrl resolve(java.lang.String);
    okhttp3.HttpUrl$Companion Companion;
}
-keep class okhttp3.Protocol$Companion
-keepclassmembers class okhttp3.Protocol$Companion {
    okhttp3.Protocol get(java.lang.String);
}
-keep class okhttp3.Protocol
-keepclassmembers class okhttp3.Protocol {
    okhttp3.Protocol H2_PRIOR_KNOWLEDGE;
    okhttp3.Protocol HTTP_1_1;
    okhttp3.Protocol HTTP_2;
    okhttp3.Protocol$Companion Companion;
}
-keep class okhttp3.Request$Builder
-keepclassmembers class okhttp3.Request$Builder {
    <init>();
    okhttp3.Request build();
    okhttp3.Request$Builder headers(okhttp3.Headers);
    okhttp3.Request$Builder url(java.lang.String);
}
-keep class okhttp3.Request
-keep class okhttp3.Response$Builder
-keepclassmembers class okhttp3.Response$Builder {
    <init>();
    okhttp3.Response build();
    okhttp3.Response$Builder code(int);
    okhttp3.Response$Builder headers(okhttp3.Headers);
    okhttp3.Response$Builder message(java.lang.String);
    okhttp3.Response$Builder protocol(okhttp3.Protocol);
    okhttp3.Response$Builder request(okhttp3.Request);
}
-keep class okhttp3.Response
-keep class okhttp3.TlsVersion
-keep class okhttp3.WebSocket
-keepclassmembers class okhttp3.WebSocket {
    <init>(okhttp3.internal.concurrent.TaskRunner,okhttp3.Request,okhttp3.WebSocketListener,java.util.Random,long,okhttp3.internal.ws.WebSocketExtensions,long);
    void failWebSocket(java.lang.Exception,okhttp3.Response);
    void initReaderAndWriter(java.lang.String,okhttp3.internal.ws.RealWebSocket$Streams);
    void loopReader();
}
-keep class okhttp3.WebSocketListener
-keepclassmembers class okhttp3.WebSocketListener {
    void onOpen(okhttp3.WebSocket,okhttp3.Response);
}
-keep class okhttp3.internal.Internal
-keepclassmembers class okhttp3.internal.Internal {
    okhttp3.Headers$Builder addHeaderLenient(okhttp3.Headers$Builder,java.lang.String);
    okhttp3.Headers$Builder addHeaderLenient(okhttp3.Headers$Builder,java.lang.String,java.lang.String);
}
-keep class okhttp3.internal.Util
-keepclassmembers class okhttp3.internal.Util {
    java.util.List immutableListOf(java.lang.Object[]);
    java.util.List toImmutableList(java.util.List);
    java.util.concurrent.ThreadFactory threadFactory(java.lang.String,boolean);
    void closeQuietly(java.io.Closeable);
    void closeQuietly(java.net.ServerSocket);
    void closeQuietly(java.net.Socket);
}
-keep class okhttp3.internal.concurrent.Task
-keepclassmembers class okhttp3.internal.concurrent.Task {
    <init>(java.lang.String,boolean);
}
-keep class okhttp3.internal.concurrent.TaskQueue
-keepclassmembers class okhttp3.internal.concurrent.TaskQueue {
    java.util.concurrent.CountDownLatch idleLatch();
    void schedule(okhttp3.internal.concurrent.Task,long);
}
-keep class okhttp3.internal.concurrent.TaskRunner$Backend
-keepclassmembers class okhttp3.internal.concurrent.TaskRunner$Backend {
    <init>(java.util.concurrent.ThreadFactory);
    void shutdown();
}
-keep class okhttp3.internal.concurrent.TaskRunner$RealBackend
-keepclassmembers class okhttp3.internal.concurrent.TaskRunner$RealBackend {
    <init>(java.util.concurrent.ThreadFactory);
    void shutdown();
}
-keep class okhttp3.internal.concurrent.TaskRunner
-keepclassmembers class okhttp3.internal.concurrent.TaskRunner {
    <init>(okhttp3.internal.concurrent.TaskRunner$Backend);
    java.util.List activeQueues();
    okhttp3.internal.concurrent.TaskQueue newQueue();
}
-keep class okhttp3.internal.http.HttpMethod
-keepclassmembers class okhttp3.internal.http.HttpMethod {
    boolean permitsRequestBody(java.lang.String);
}
-keep class okhttp3.internal.http2.ErrorCode$Companion
-keepclassmembers class okhttp3.internal.http2.ErrorCode$Companion {
    okhttp3.internal.http2.ErrorCode fromHttp2(int);
}
-keep class okhttp3.internal.http2.ErrorCode
-keepclassmembers class okhttp3.internal.http2.ErrorCode {
    okhttp3.internal.http2.ErrorCode NO_ERROR;
    okhttp3.internal.http2.ErrorCode$Companion Companion;
}
-keep class okhttp3.internal.http2.Header
-keepclassmembers class okhttp3.internal.http2.Header {
    <init>(java.lang.String,java.lang.String);
    <init>(okio.ByteString,java.lang.String);
    <init>(okio.ByteString,okio.ByteString);
    okio.ByteString RESPONSE_STATUS;
    okio.ByteString TARGET_AUTHORITY;
    okio.ByteString TARGET_METHOD;
    okio.ByteString TARGET_PATH;
}
-keep class okhttp3.internal.http2.Http2Connection$Builder
-keepclassmembers class okhttp3.internal.http2.Http2Connection$Builder {
    <init>(boolean,okhttp3.internal.concurrent.TaskRunner);
    okhttp3.internal.http2.Http2Connection build();
    okhttp3.internal.http2.Http2Connection$Builder listener(okhttp3.internal.http2.Http2Connection$Listener);
    okhttp3.internal.http2.Http2Connection$Builder socket$default(okhttp3.internal.http2.Http2Connection$Builder,java.net.Socket,java.lang.String,okio.BufferedSource,okio.BufferedSink,int,java.lang.Object);
}
-keep class okhttp3.internal.http2.Http2Connection$Listener
-keepclassmembers class okhttp3.internal.http2.Http2Connection$Listener {
    <init>();
}
-keep class okhttp3.internal.http2.Http2Connection
-keepclassmembers class okhttp3.internal.http2.Http2Connection {
    okhttp3.internal.http2.Http2Stream pushStream(int,java.util.List,boolean);
    void flush();
    void setSettings(okhttp3.internal.http2.Settings);
    void shutdown(okhttp3.internal.http2.ErrorCode);
    void start$default(okhttp3.internal.http2.Http2Connection,boolean,okhttp3.internal.concurrent.TaskRunner,int,java.lang.Object);
}
-keep class okhttp3.internal.http2.Http2Stream
-keepclassmembers class okhttp3.internal.http2.Http2Stream {
    int getId();
    okhttp3.Headers takeHeaders();
    okhttp3.internal.http2.Http2Connection getConnection();
    okio.Sink getSink();
    okio.Source getSource();
    void close(okhttp3.internal.http2.ErrorCode,java.io.IOException);
    void closeLater(okhttp3.internal.http2.ErrorCode);
    void enqueueTrailers(okhttp3.Headers);
    void writeHeaders(java.util.List,boolean,boolean);
}
-keep class okhttp3.internal.http2.Settings
-keepclassmembers class okhttp3.internal.http2.Settings {
    <init>();
}
-keep class okhttp3.internal.platform.Platform$Companion
-keepclassmembers class okhttp3.internal.platform.Platform$Companion {
    okhttp3.internal.platform.Platform get();
}
-keep class okhttp3.internal.platform.Platform
-keepclassmembers class okhttp3.internal.platform.Platform {
    java.lang.String getSelectedProtocol(javax.net.ssl.SSLSocket);
    okhttp3.internal.platform.Platform$Companion Companion;
    void afterHandshake(javax.net.ssl.SSLSocket);
    void configureTlsExtensions(javax.net.ssl.SSLSocket,java.lang.String,java.util.List);
}
-keep class okhttp3.internal.ws.RealWebSocket$Streams
-keepclassmembers class okhttp3.internal.ws.RealWebSocket$Streams {
    <init>(boolean,okio.BufferedSource,okio.BufferedSink);
}
-keep class okhttp3.internal.ws.RealWebSocket
-keepclassmembers class okhttp3.internal.ws.RealWebSocket {
    <init>(okhttp3.internal.concurrent.TaskRunner,okhttp3.Request,okhttp3.WebSocketListener,java.util.Random,long,okhttp3.internal.ws.WebSocketExtensions,long);
    void failWebSocket(java.lang.Exception,okhttp3.Response);
    void initReaderAndWriter(java.lang.String,okhttp3.internal.ws.RealWebSocket$Streams);
    void loopReader();
}
-keep class okhttp3.internal.ws.WebSocketExtensions$Companion
-keepclassmembers class okhttp3.internal.ws.WebSocketExtensions$Companion {
    okhttp3.internal.ws.WebSocketExtensions parse(okhttp3.Headers);
}
-keep class okhttp3.internal.ws.WebSocketExtensions
-keepclassmembers class okhttp3.internal.ws.WebSocketExtensions {
    okhttp3.internal.ws.WebSocketExtensions$Companion Companion;
}
-keep class okhttp3.internal.ws.WebSocketProtocol
-keepclassmembers class okhttp3.internal.ws.WebSocketProtocol {
    java.lang.String acceptHeader(java.lang.String);
    okhttp3.internal.ws.WebSocketProtocol INSTANCE;
}
-keep class okhttp3.internal.ws.WebSocketReader$FrameCallback
-keepclassmembers class okhttp3.internal.ws.WebSocketReader$FrameCallback {
    <init>(okhttp3.internal.concurrent.TaskRunner,okhttp3.Request,okhttp3.WebSocketListener,java.util.Random,long,okhttp3.internal.ws.WebSocketExtensions,long);
    void failWebSocket(java.lang.Exception,okhttp3.Response);
    void initReaderAndWriter(java.lang.String,okhttp3.internal.ws.RealWebSocket$Streams);
    void loopReader();
}
-keep class okio.Buffer
-keepclassmembers class okio.Buffer {
    <init>();
    boolean exhausted();
    java.lang.String readUtf8();
    long read(okio.Buffer,long);
    long size();
    okio.Buffer clone();
    okio.Buffer write(byte[]);
    okio.Buffer writeHexadecimalUnsignedLong(long);
    okio.Buffer writeUtf8(java.lang.String);
    void skip(long);
    void write(okio.Buffer,long);
}
-keep class okio.BufferedSink
-keepclassmembers class okio.BufferedSink {
    <init>();
    boolean exhausted();
    java.lang.String readUtf8();
    long read(okio.Buffer,long);
    long size();
    okio.Buffer clone();
    okio.Buffer write(byte[]);
    okio.Buffer writeHexadecimalUnsignedLong(long);
    okio.Buffer writeUtf8(java.lang.String);
    okio.BufferedSink writeUtf8(java.lang.String);
    void close();
    void flush();
    void skip(long);
    void write(okio.Buffer,long);
}
-keep class okio.BufferedSource
-keepclassmembers class okio.BufferedSource {
    <init>();
    boolean exhausted();
    java.lang.String readUtf8();
    java.lang.String readUtf8(long);
    java.lang.String readUtf8LineStrict();
    long read(okio.Buffer,long);
    long size();
    okio.Buffer clone();
    okio.Buffer write(byte[]);
    okio.Buffer writeHexadecimalUnsignedLong(long);
    okio.Buffer writeUtf8(java.lang.String);
    void skip(long);
    void write(okio.Buffer,long);
}
-keep class okio.ByteString$Companion
-keepclassmembers class okio.ByteString$Companion {
    okio.ByteString encodeUtf8(java.lang.String);
}
-keep class okio.ByteString
-keepclassmembers class okio.ByteString {
    okio.ByteString$Companion Companion;
}
-keep class okio.Okio
-keepclassmembers class okio.Okio {
    okio.BufferedSink buffer(okio.Sink);
    okio.BufferedSource buffer(okio.Source);
    okio.Sink sink(java.net.Socket);
    okio.Source source(java.net.Socket);
}
-keep class okio.Sink
-keepclassmembers class okio.Sink {
    <init>();
    boolean exhausted();
    java.lang.String readUtf8();
    long read(okio.Buffer,long);
    long size();
    okio.Buffer clone();
    okio.Buffer write(byte[]);
    okio.Buffer writeHexadecimalUnsignedLong(long);
    okio.Buffer writeUtf8(java.lang.String);
    okio.BufferedSink writeUtf8(java.lang.String);
    void close();
    void flush();
    void skip(long);
    void write(okio.Buffer,long);
}
-keep class okio.Source
-keepclassmembers class okio.Source {
    <init>();
    boolean exhausted();
    java.lang.String readUtf8();
    java.lang.String readUtf8(long);
    java.lang.String readUtf8LineStrict();
    long read(okio.Buffer,long);
    long size();
    okio.Buffer clone();
    okio.Buffer write(byte[]);
    okio.Buffer writeHexadecimalUnsignedLong(long);
    okio.Buffer writeUtf8(java.lang.String);
    void skip(long);
    void write(okio.Buffer,long);
}
-keep class okio.Timeout
-keepclassmembers class okio.Timeout {
    okio.Timeout NONE;
}
-keep class okio.Utf8
-keepclassmembers class okio.Utf8 {
    long size$default(java.lang.String,int,int,int,java.lang.Object);
}
-keep class androidx.test.orchestrator.listeners.OrchestrationListenerManager$TestEvent[]
-keepclassmembers class androidx.test.orchestrator.listeners.OrchestrationListenerManager$TestEvent[] {
    java.lang.Object clone();
}
-keep class androidx.test.orchestrator.listeners.result.TestResult$TestStatus[]
-keepclassmembers class androidx.test.orchestrator.listeners.result.TestResult$TestStatus[] {
    java.lang.Object clone();
}
-keep class androidx.test.platform.concurrent.DirectExecutor[]
-keepclassmembers class androidx.test.platform.concurrent.DirectExecutor[] {
    java.lang.Object clone();
}
-keep class androidx.test.platform.device.DeviceController$ScreenOrientation[]
-keepclassmembers class androidx.test.platform.device.DeviceController$ScreenOrientation[] {
    java.lang.Object clone();
}
-keep class androidx.test.runner.lifecycle.ApplicationStage[]
-keepclassmembers class androidx.test.runner.lifecycle.ApplicationStage[] {
    java.lang.Object clone();
}
-keep class androidx.test.runner.lifecycle.Stage[]
-keepclassmembers class androidx.test.runner.lifecycle.Stage[] {
    java.lang.Object clone();
}
-keep class androidx.test.runner.permission.RequestPermissionCallable$Result[]
-keepclassmembers class androidx.test.runner.permission.RequestPermissionCallable$Result[] {
    java.lang.Object clone();
}
-keep class androidx.test.runner.permission.UiAutomationShellCommand$PmCommand[]
-keepclassmembers class androidx.test.runner.permission.UiAutomationShellCommand$PmCommand[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.events.TestStatus$Status[]
-keepclassmembers class androidx.test.services.events.TestStatus$Status[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.events.discovery.TestDiscoveryEvent$EventType[]
-keepclassmembers class androidx.test.services.events.discovery.TestDiscoveryEvent$EventType[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.events.internal.Throwables$State[]
-keepclassmembers class androidx.test.services.events.internal.Throwables$State[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.events.platform.TestPlatformEvent$EventType[]
-keepclassmembers class androidx.test.services.events.platform.TestPlatformEvent$EventType[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.events.run.TestRunEvent$EventType[]
-keepclassmembers class androidx.test.services.events.run.TestRunEvent$EventType[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.storage.file.HostedFile$FileHost[]
-keepclassmembers class androidx.test.services.storage.file.HostedFile$FileHost[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.storage.file.HostedFile$FileType[]
-keepclassmembers class androidx.test.services.storage.file.HostedFile$FileType[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.storage.file.HostedFile$HostedFileColumn[]
-keepclassmembers class androidx.test.services.storage.file.HostedFile$HostedFileColumn[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.storage.file.PropertyFile$Authority[]
-keepclassmembers class androidx.test.services.storage.file.PropertyFile$Authority[] {
    java.lang.Object clone();
}
-keep class androidx.test.services.storage.file.PropertyFile$Column[]
-keepclassmembers class androidx.test.services.storage.file.PropertyFile$Column[] {
    java.lang.Object clone();
}
