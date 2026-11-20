# The JS bridge calls into Kotlin by name; keep the interface members.
-keepclassmembers class app.recordly.android.NativeBridge {
    public *;
}
# @JavascriptInterface members are looked up reflectively by the WebView.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
