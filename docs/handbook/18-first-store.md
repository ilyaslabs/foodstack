# 18 Your first restaurant

[Back to the handbook](README.md)

Put your first restaurant on the marketplace, give its owner access, build the menu, set where it delivers, make it live, and follow one test order from the customer to the kitchen and back.

Every restaurant on FoodStack is a **branch** of a **brand**. The brand holds the name, logo and menu. Each branch has its own address, opening hours, delivery area, sold-out dishes and orders. A restaurant with one location is a brand with one branch.

## What you need

- Your brand and rules set ([guide 17](17-branding-and-rules.md)), with at least the city the restaurant is in.
- From the restaurant: its name and branch name, legal name and tax number, the cuisines, the street address, the owner's email, the commission you agreed (for example 15 %), the menu with prices, and a square logo (at least 512 px) and a wide cover photo (at least 1600 px).
- The restaurant's position on a map. Open the address in any map app, press and hold on the entrance, and copy the two numbers (latitude and longitude, such as `31.4834, 74.3256`).
- About 45 minutes, most of it on the menu.

## Steps

### Add the restaurant

1. In the back office, open **Stores**, then **Onboard restaurant**.
2. Fill in the brand name, the branch name (the area, such as `Model Town`), the legal name, tax number, cuisines, a short description, the address, the city, the latitude and longitude, the usual preparation time, and the commission. Click **Onboard restaurant**.
3. The branch page opens with the status **Draft**. A box "Before this branch can go live" lists what is still missing. Customers cannot see a draft.
4. On the **Profile** tab, upload the logo and the cover photo, choose the order ticket paper (58 or 80 mm, as on the restaurant's printer), and save.

### Give the owner access

5. Open the **Managers** tab. Give access to the owner's email:
   - **Brand owner**: sees and runs every branch of this brand.
   - **Branch manager**: sees and runs this one branch only.

   Set a starting password and give it to them in person or through a password manager. They see **Branches** (brand owner) or **My store** (branch manager) when they sign in, and nothing about other restaurants.

### Build the menu

You or the owner can do this; most operators do the first menu together with the owner.

6. On the **Menu** tab, click **Start the menu**, and add a section (for example `Grill`).
7. Add each dish: name, price, a photo, tags such as **Spicy**, and option groups if the customer chooses something (for example `Size`: pick exactly 1 of `Half` or `Full +400`). A dish sold at only some branches is set to **Some branches**.
8. Drag sections and dishes into the order customers should see.

If you switched on price approval on **Ordering**, changes to branch prices wait in **Price approvals** for you.

### Opening hours and delivery

9. On the **Hours** tab, set the hours for each day and any holidays, and click **Save hours**. The branch's time zone comes from its position.
10. On the **Delivery** tab, choose who delivers:
    - **The restaurant delivers itself**: draw one or more zones on the map (or a circle with a radius), each with its own delivery charge, minimum order and "free above" amount. Click **Save zones**. Customers outside every zone see "Out of area".
    - **Your riders deliver**: switch on **Platform riders deliver for this branch** and save. Customers then pay your delivery price ([guide 17](17-branding-and-rules.md), Delivery pricing). You need riders: add them under **Riders**.

    Pickup can be switched on as well, or instead of delivery.

### Go live

11. At the top of the branch page, click **Go live**. If something is missing, the box says what ("Add at least one dish on the Menu tab", "Add a delivery zone on the Delivery tab, or switch on pickup"). When everything is there: "The branch is live. Customers nearby can find it."

### A test order

12. On a phone or a private browser window, open `https://example.com/`, choose a delivery address inside the restaurant's area, and find the restaurant. It appears within a few seconds of going live.
13. Add a dish, open the cart, choose **Cash**, and click **Place order**.
14. In the back office, signed in as the owner (or as yourself, on the branch's page), open **Live orders** and click **Turn on sound** (browsers allow sound only after a click; the page remembers it). The order appears under **New** within a second or two, and the page beeps until someone acts. Click it, then **Accept**, and set the preparation time. A kitchen ticket prints (the browser's print window opens).
15. Watch the customer's order page: it moves to **Accepted** by itself.
16. On the order in **Live orders**, click **Cancel order** with the reason `Test order`. The customer's page says **Cancelled**.

Show the owner the **Live orders** page on the tablet or computer the kitchen will use, and leave it open there: that is how orders arrive.

## Check it worked

- **Stores** lists the restaurant as **Live**.
- The test order is in **Orders** as **Cancelled**, with every step on its timeline.
- The owner signed in with their own password and saw only their restaurant.

## Undo

- **Not ready after all**: click **Pause** at the top of the branch page. Customers cannot find or order from it until you click **Go live** again. Nothing is deleted.
- **Closed for an afternoon**: the owner switches off **Taking orders** at the top of the page, with a reason customers can read ("Back at 6 pm").
- **A dish was wrong**: fix it on the **Menu** tab; deleting a dish keeps it on past orders.
- **An owner leaves**: remove their access on the **Managers** tab, or block their account in **Users**.

## If something goes wrong

| Problem | What to do |
|---|---|
| **Go live** is refused | The box on the branch page lists what is missing: a dish, a delivery zone or pickup, or the location |
| The restaurant does not appear in the customer app | Is the customer's address inside a zone? Is the branch open now (**Hours**)? Search also looks only within about 8 km by road; customers can tap "Show restaurants up to …" to look further |
| "Saved. Note: the branch itself is outside every zone." | The zones are saved, but check you drew them in the right place |
| No beep in **Live orders** | Click **Turn on sound**. If it says **Tap to allow sound**, tap it: the browser was restarted. Keep the page open |
| No kitchen ticket | The browser's print window opens on **Accept**; choose the kitchen printer once and the browser remembers it |
| The order is rejected by itself after a few minutes | Nobody accepted it within **Time to accept** (**Ordering**). The customer was told and refunded |
| The owner cannot sign in | Check the email on the **Managers** tab; set a new starting password there |
