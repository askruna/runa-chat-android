# The page calls these methods by name through the WebView; keep them when the app is minified.
-keepclassmembers class ai.askruna.chat.RunaChatActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
