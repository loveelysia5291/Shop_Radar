# WebView calls annotated entry points by their JavaScript names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# WorkManager persists worker class names and instantiates their constructors.
-keep class com.aykkdk.shop.AuctionWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.aykkdk.shop.AutomaticRefreshWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.aykkdk.shop.RefreshScheduleWatchdog {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepattributes SourceFile,LineNumberTable
-keep class com.aykkdk.shop.PendingAlertDeliveryWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.aykkdk.shop.BackgroundNotificationTestWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
