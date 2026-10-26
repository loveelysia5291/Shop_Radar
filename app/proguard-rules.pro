# WebView calls annotated entry points by their JavaScript names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
# WorkManager persists worker class names and instantiates their constructors.
-keep class com.example.shopradar.AuctionWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.shopradar.AutomaticRefreshWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.shopradar.RefreshScheduleWatchdog {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepattributes SourceFile,LineNumberTable
-keep class com.example.shopradar.PendingAlertDeliveryWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.example.shopradar.BackgroundNotificationTestWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
