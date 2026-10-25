# The page calls these methods by name through the WebView (the chat screen, the search view and the order
# reporter each expose a Bridge); keep them when the app is minified.
-keepclassmembers class ai.askruna.chat.** {
    @android.webkit.JavascriptInterface <methods>;
}
