# Runa Chat for Android

The Runa AI shopping assistant ("Ask Quicklly") in your Android app: the chat as a screen, Ask
Quicklly in search as a view above your search results, and one call after checkout so orders
count what Ask Quicklly sold. One dependency, three callbacks to connect it to your cart and your
product screen.

The chat itself is hosted and updated by Runa and shown full screen by the library's own
screen, so improvements reach your users without app releases. The app provides only what the app
alone can do: who and where the shopper is, adding to the cart, what is in the cart, and opening
your product screen.

- Java, no dependencies beyond the Android SDK and `androidx.browser`, 44 KB
- minSdk 21 (Android 5.0+), tested on Android 14
- works from Java or Kotlin

## Install

```gradle
// settings.gradle (or the root build.gradle, in the repositories list)
maven { url 'https://jitpack.io' }

// app/build.gradle
implementation 'com.github.askruna:runa-chat-android:1.1.1'
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
        @Override public boolean openProduct(Activity chat, RunaChat.Product product) {
            // open your product screen for product.pid / product.sid, then return true
            return true;
        }
    });
```

**Required** — three callbacks:

| callback | when | what you do |
| --- | --- | --- |
| `setQuantity(Product p, int quantity)` | the shopper tapped ADD or changed a quantity | set that product's quantity in your cart (`pid`, `sid`; absolute, `0` removes) |
| `getCart()` | the chat needs to draw its steppers | return your cart: one `CartItem(pid, sid, quantity)` per line |
| `openProduct(Activity chat, Product p)` | the shopper tapped a product card | open your own product screen for that `pid` / `sid` and return `true` (left out, the product's web page opens in an in-app browser sheet — a safety net only) |

**Optional** — leave them out and the defaults apply:

| callback | when | default |
| --- | --- | --- |
| `openLink(Activity chat, String url)` | an outside link — rare in the chat (a recipe page, a size guide) | opens in an in-app browser sheet (Chrome Custom Tabs, with a close button); return `true` to show it your own way |
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
so a new store row can be drawn without a lookup. Prices, images and terms come from Runa's index
(refreshed nightly) and are for display: add the line through your usual add-to-cart call with
`pid` and `sid`, so your server prices it as it always does.

All callbacks run on the main thread. Keyboard, system bars, the back button, outside links,
the loading screen (the Ask Quicklly mark) and the error state are the library's job — see https://quicklly.askruna.ai/app/docs/.

## Ask Quicklly in search (1.1.0)

A view for your search screen, under the search field and above your own "N products found":
it shows the products the shopper meant (milk when "cow milk" lists butter first), a line about
them and follow-up chips. A chip opens the chat screen with that question. It uses the same
`Options` and `Callbacks` as the chat, so ADD, the steppers and product taps go to your cart and
your product screen.

```java
RunaSearchView answer = new RunaSearchView(this);      // or <ai.askruna.chat.RunaSearchView> in XML
resultsHeader.addView(answer, 0);                       // above your results, full width, height WRAP_CONTENT
answer.setup(this, options, callbacks);                 // the same Options and Callbacks as RunaChat.open

// on every search the shopper makes, with what your own results show:
answer.search("cow milk", 76, firstTitles);             // query, how many products you found, your first titles (≤ 10)
answer.clear();                                         // the search field was cleared
answer.destroy();                                       // in onDestroy()
```

- The view sizes itself: a one-line working state, then the answer (about 380 dp); with nothing to
  show it collapses to nothing. `setOnHeightChangeListener` tells you when it changes, if your layout
  needs to know (e.g. a RecyclerView header).
- `resultCount` and `firstTitles` are optional (`search(query)` works), but they let Ask Quicklly see
  what your results already show and answer the gap instead of repeating them.
- A new search reuses the view (no reload). Horizontal swipes on its product row stay in the view;
  vertical ones scroll your screen.
- `notifyCartChanged()` and `updateAddress(…)` reach the search view too.

## Orders (1.1.0)

After your checkout succeeds, report the order. The library hands it to Runa's order page in a
WebView the shopper never sees; it counts the products the shopper added from Ask Quicklly or
opened from it in the last 30 days, the same rule as on quicklly.com, and lets go.

```java
RunaChat.orderPlaced(this, options,
    new RunaChat.Order("QD237898", 54.32)                // your order number, the total paid
        .line("706824", "112333", 7.59, 2)               // per product: pid, sid, unit price paid, quantity
        .line("905", "112333", 15.45, 1),
    (reported, reason) -> Log.d("App", "order reported " + reported));   // optional
```

Send only the order number, the amounts and the lines: no names, addresses or payment details.
An order is counted once (the same order number again is ignored).

## Sample app

`sample/` is a small app standing in for a retailer's app: a cart, a product screen, an
"Ask Quicklly" button, a search screen with Ask Quicklly in search (`SearchActivity`) and a
"Place order" button. Everything a retailer writes is in `MainActivity` (`options()`,
`callbacks()`, `openChat()`, `placeOrder()`) and `SearchActivity`.

```
./gradlew :sample:installDebug      # on a connected device or emulator
```

`tests/e2e.mjs` drives the sample end to end on an emulator (the chat screen, native taps
through adb, the callbacks in logcat): `npm i playwright && node tests/e2e.mjs`.
`tests/e2e-search.mjs` does the same for the search view and `orderPlaced`.

## Documentation

The integration guide, the test checklist and a live demo: https://quicklly.askruna.ai/app/docs/.
The chat and the search answers are hosted and updated by Runa, so improvements reach your users
without an app release; the library only needs the three callbacks above.

## Versions

- **1.1.1** — the chat's loading screen: the Ask Quicklly mark (the search answer's sparkle in a spinning ring) instead of a plain spinner, until the chat has drawn.
- **1.1.0** — `RunaSearchView` (Ask Quicklly in search), `RunaChat.orderPlaced`.
- **1.0.3** — in-app browser fallback for outside links; `openProduct` required.

## License

MIT
