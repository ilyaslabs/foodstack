# 18 Your brand and rules

[Back to the handbook](README.md)

Make the marketplace yours: name, logo and colours, languages and currency, what customers pay on top of the food, and how delivery is priced. Everything here is in the back office; nothing needs the server.

## What you need

- Signed in as an administrator ([guide 17](17-first-sign-in.md)).
- Your logo as a PNG or SVG with a transparent background, and your main brand colour (for example `#B71C1C`).
- Your decisions on fees, tax and delivery prices. Change them any time later; new orders use the new rules.
- About 30 minutes.

## Steps

Every page below has a **Save** button. After saving, each says when it takes effect (for most, "within a minute").

1. **Branding** (menu on the left): the marketplace's name, a short tagline, the logo and the main colour. Customers see them in the header, the browser tab, emails and buttons. The page checks that text stays readable on your colour.
2. **Languages**: the languages customers can choose (English, Arabic, Urdu and others), the default one for new visitors, the currency and the time zone. Arabic and Urdu switch the whole app to right-to-left.
3. **Ordering**:
   - **Payment methods**: cash, card ([guide 16](16-card-payments.md)), and whether customers may save a card.
   - **Fees and tax**: a service fee (a fixed amount, a percent of the food, or both) and tax (percent of the food after discounts).
   - **Tips and timing**: whether customers can tip the rider (all of it goes to the rider), and **Time to accept**: how long a restaurant has to accept a new order before it is rejected and refunded (3 minutes is a good start).
   - **Menu prices**: whether restaurants' price changes need your approval first.
   - **Branches collecting card payments**: whether you allow some restaurants to receive card money directly, and their default credit limit.
4. **Cities**: add each city you serve, with its centre on the map. Restaurants and riders belong to a city; coupons can be limited to one.
5. **Delivery pricing** (only if you run your own riders): what customers pay for delivery by your riders: a base charge, a charge per kilometre, the furthest distance you deliver, and optional busy-hour windows with a higher price.
6. **Rider pay** (only if you run your own riders): the riders' share of the delivery charge and a minimum per delivery, how long a rider has to accept an offer, how far to look for riders, when to alert your support team that nobody has taken a delivery, and above what order total the rider must ask the customer for the delivery code.
7. **Messages** (optional): the wording of every email and in-app message, in each language. Pick a message, edit the heading or text, and watch the **Preview**. **Use FoodStack's text** puts the original back.
8. **Sign-in options** (optional): let customers sign in with Google or Apple, by pasting the client ids from Google's and Apple's developer consoles.

## Check it worked

- Open `https://example.com/` in a private window: your name, logo and colour are there, in your default language.
- Put a dish in a cart once you have a restaurant ([guide 19](19-first-store.md)): checkout shows your fee and tax, and the total adds up.

## Undo

Change the setting back and save. Nothing here deletes data. If two people edit the same page at once, the second save is refused with a message to reload, so nobody overwrites the other by accident.

## If something goes wrong

| Problem | What to do |
|---|---|
| Customers still see the old name or colour | Wait a minute and reload. Browsers keep the old logo a little longer: a private window shows the new one |
| The logo is refused | Use PNG, JPEG, WebP or SVG under 10 MB |
| A message's wording is refused | Only the message's own placeholders (shown under **Insert**) may be used, written exactly as listed |
| Delivery pricing does nothing | It applies only to restaurants that use your riders (their **Delivery** tab). Restaurants that deliver themselves set their own charges |
