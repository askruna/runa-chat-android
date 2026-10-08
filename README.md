# Runa Chat for Android

The Runa AI shopping assistant ("Ask Quicklly") as a screen in your Android app. One dependency,
one call to open it, two callbacks to connect it to your cart.

The chat itself is hosted and updated by Runa and shown full screen by the library's own
screen, so improvements reach your users without app releases. The app provides only what the app
alone can do: who and where the shopper is, adding to the cart, what is in the cart, and opening
your product screen.

- Java, no dependencies beyond the Android SDK, 17 KB
- minSdk 21 (Android 5.0+), tested on Android 14
- works from Java or Kotlin

## Install

```gradle
// settings.gradle (or the root build.gradle, in the repositories list)
maven { url 'https://jitpack.io' }

// app/build.gradle
implementation 'com.github.askruna:runa-chat-android:1.0.2'
```

Nothing to add to your manifest: the library declares its own screen and the INTERNET permission.

## Use

```java
RunaChat.open(this,
    new RunaChat.Options("quicklly")        // your client key from Runa
        .zip("60610")                       // the delivery ZIP (required)
        .userId("12345")                    // customer id, or a stable id for guests
        .address("1140 N Wells St, Chicago").city("Chicago").state("IL"),
    new RunaChat.Callbacks() {
        @Override public void setQuantity(RunaChat.Product product, int quantity) {
            // your add-to-cart: product.pid (product id), product.sid (store id), quantity (absolute; 0 = remove)
        }
        @Override public List<RunaChat.CartItem> getCart() {
            // what is in your cart now: one CartItem(pid, sid, quantity) per line
        }
    });
```

That is the whole integration. Optional callbacks on the same object:

| callback | when | default |
| --- | --- | --- |
| `openProduct(Activity chat, Product p)` | the shopper tapped a product card | opens the product's web page in the browser; return `true` after opening your own product screen |
| `openLink(Activity chat, String url)` | an outside link (a recipe, a web page) | opens in the browser |
| `onClose()` | the chat screen closed | — |
| `onMessage(String type, JSONObject payload)` | any other message from the page, e.g. analytics events | — |

Two more calls you may need:

- `RunaChat.notifyCartChanged()` — call after the cart changes outside the chat (your cart or
  product screen) while the chat may be open; the chat updates its quantities.
- `RunaChat.updateAddress(zip, address, city, state)` — the shopper changed the delivery
  address; an open chat re-scopes to it and starts a new conversation.

Optional: `RunaChat.warmUp(context)` early (e.g. when the grocery tab opens) makes the first
open of the chat faster. `Options.question("…")` opens the chat with a question already sent
(deep links, "ask about this product"); `Options.debug(true)` logs every message (tag `RunaChat`).

`setQuantity` is called with everything the chat knows about the product (`title`, `price`,
`image`, `storeName`, `storeImage`, `url`, `fastDelivery`, and `raw` with the whole payload) and the
store's terms for the shopper's ZIP (`minOrder`, `deliveryFee`, `deliveryRange`, `instantDelivery`),
so a new store row can be created without a lookup. The recommended
pattern is to look the product up by `pid` + `sid` with the same API your product screen uses
and add it through your normal add-to-cart path, so prices, tax and inventory always come from
your own system.

All callbacks run on the main thread. The library handles the keyboard, system bars and the
notch, the Android back button (the chat closes its own menus first), outside links, loading and
error states, and keeps outside links out of the chat screen.

## Sample app

`sample/` is a small app standing in for a retailer's app: a cart, a product screen and an
"Ask Quicklly" button. Everything a retailer writes is in `MainActivity.openChat()`.

```
./gradlew :sample:installDebug      # on a connected device or emulator
```

`tests/e2e.mjs` drives the sample end to end on an emulator (the chat screen, native taps
through adb, the callbacks in logcat): `npm i playwright && node tests/e2e.mjs`.

## Documentation

The integration guide, the test checklist and a live demo: https://quicklly.askruna.ai/app/docs/.
The chat itself is hosted and updated by Runa, so improvements reach your users without an app
release; the library only needs the two callbacks above.

## License

MIT
