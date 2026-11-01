import { Injectable, computed, inject } from '@angular/core';

import { LocaleStore } from '@foodstack/ui';

/**
 * The storefront's own words in each shipped language. Store content (names, menus) comes
 * translated from the API; this covers buttons, labels and messages around it. A language the
 * operator enables without a dictionary here shows English chrome around translated content.
 */
const EN = {
  signIn: 'Sign in',
  signOut: 'Sign out',
  language: 'Language',
  heroTitle: 'Food from places near you',
  whereTo: 'Where should we deliver?',
  addressPlaceholder: 'Search your area or street',
  useMyLocation: 'Use my location',
  locating: 'Finding you…',
  locationDenied: 'Location is off. Type your area instead.',
  noAddressMatch: 'No match. Try a nearby landmark or area.',
  addressSearchDown: 'Address search is not available right now. Use your location instead.',
  deliveringTo: 'Delivering to',
  pickupNear: 'Pickup near',
  change: 'Change',
  currentLocation: 'Your current location',
  searchPlaceholder: 'Search stores or dishes',
  delivery: 'Delivery',
  pickup: 'Pickup',
  all: 'All',
  storesFound: '{n} stores',
  storeFound: '1 store',
  openNow: 'Open',
  closedNow: 'Closed',
  closedForNow: 'Closed for now',
  minutes: '{n} min',
  km: '{n} km',
  noStoresTitle: 'No stores here yet',
  noStoresText: 'Try another area, switch to pickup, or clear your search.',
  clearSearch: 'Clear search',
  loadMore: 'Show more',
  searchFailed: 'Search is not available right now. Try again in a moment.',
  retry: 'Try again',
  pickLocationTitle: 'Pick a location to see stores',
  pickLocationText: 'We show the stores that deliver to you, open ones first.',
  back: 'All stores',
  menu: 'Menu',
  soldOut: 'Sold out',
  from: 'from',
  deliveryFee: 'Delivery {fee}',
  freeDelivery: 'Free delivery',
  freeAbove: 'Free above {amount}',
  minOrder: 'Minimum order {amount}',
  notDeliverable: 'Does not deliver to your location. Pickup may be available.',
  pickupOnly: 'Pickup only',
  setLocationForFee: 'Set your location to see the delivery fee',
  hours: 'Opening hours',
  storeMissing: 'This store is not available',
  storeMissingText: 'It may have closed or moved. Find another store nearby.',
  emptyMenu: 'The menu is on its way.',
  choices: 'Choices',
  orderingSoon: 'Ordering opens soon. Browse the menu for now.',
  branches: '{n} branches',
  closedAllDay: 'Closed',
  today: 'Today',
  opensAt: 'Today {hours}',
} as const;

export type UiKey = keyof typeof EN;
type Dictionary = Record<UiKey, string>;

const AR: Dictionary = {
  signIn: 'تسجيل الدخول',
  signOut: 'تسجيل الخروج',
  language: 'اللغة',
  heroTitle: 'طعام من أماكن قريبة منك',
  whereTo: 'إلى أين نوصل طلبك؟',
  addressPlaceholder: 'ابحث عن منطقتك أو شارعك',
  useMyLocation: 'استخدم موقعي',
  locating: 'جارٍ تحديد موقعك…',
  locationDenied: 'الموقع غير مفعّل. اكتب منطقتك بدلًا من ذلك.',
  noAddressMatch: 'لا نتائج. جرّب معلمًا أو منطقة قريبة.',
  addressSearchDown: 'البحث عن العناوين غير متاح الآن. استخدم موقعك بدلًا من ذلك.',
  deliveringTo: 'التوصيل إلى',
  pickupNear: 'الاستلام بالقرب من',
  change: 'تغيير',
  currentLocation: 'موقعك الحالي',
  searchPlaceholder: 'ابحث عن مطاعم أو أطباق',
  delivery: 'توصيل',
  pickup: 'استلام',
  all: 'الكل',
  storesFound: '{n} مطاعم',
  storeFound: 'مطعم واحد',
  openNow: 'مفتوح',
  closedNow: 'مغلق',
  closedForNow: 'مغلق مؤقتًا',
  minutes: '{n} دقيقة',
  km: '{n} كم',
  noStoresTitle: 'لا توجد مطاعم هنا بعد',
  noStoresText: 'جرّب منطقة أخرى أو الاستلام أو امسح البحث.',
  clearSearch: 'مسح البحث',
  loadMore: 'عرض المزيد',
  searchFailed: 'البحث غير متاح الآن. حاول بعد قليل.',
  retry: 'حاول مجددًا',
  pickLocationTitle: 'اختر موقعًا لعرض المطاعم',
  pickLocationText: 'نعرض المطاعم التي توصل إليك، والمفتوحة أولًا.',
  back: 'كل المطاعم',
  menu: 'القائمة',
  soldOut: 'نفد',
  from: 'من',
  deliveryFee: 'التوصيل {fee}',
  freeDelivery: 'توصيل مجاني',
  freeAbove: 'مجاني فوق {amount}',
  minOrder: 'الحد الأدنى للطلب {amount}',
  notDeliverable: 'لا يوصل إلى موقعك. قد يتوفر الاستلام.',
  pickupOnly: 'استلام فقط',
  setLocationForFee: 'حدد موقعك لمعرفة رسوم التوصيل',
  hours: 'ساعات العمل',
  storeMissing: 'هذا المطعم غير متاح',
  storeMissingText: 'ربما أغلق أو انتقل. ابحث عن مطعم آخر قريب.',
  emptyMenu: 'القائمة قادمة قريبًا.',
  choices: 'خيارات',
  orderingSoon: 'الطلب متاح قريبًا. تصفح القائمة الآن.',
  branches: '{n} فروع',
  closedAllDay: 'مغلق',
  today: 'اليوم',
  opensAt: 'اليوم {hours}',
};

const UR: Dictionary = {
  signIn: 'سائن اِن',
  signOut: 'سائن آؤٹ',
  language: 'زبان',
  heroTitle: 'آپ کے قریب کی جگہوں سے کھانا',
  whereTo: 'ہم کہاں پہنچائیں؟',
  addressPlaceholder: 'اپنا علاقہ یا گلی تلاش کریں',
  useMyLocation: 'میری لوکیشن استعمال کریں',
  locating: 'آپ کو تلاش کیا جا رہا ہے…',
  locationDenied: 'لوکیشن بند ہے۔ اپنا علاقہ لکھیں۔',
  noAddressMatch: 'کوئی نتیجہ نہیں۔ قریبی مشہور جگہ یا علاقہ لکھیں۔',
  addressSearchDown: 'پتہ تلاش ابھی دستیاب نہیں۔ اپنی لوکیشن استعمال کریں۔',
  deliveringTo: 'ڈیلیوری',
  pickupNear: 'پک اپ قریب',
  change: 'تبدیل کریں',
  currentLocation: 'آپ کی موجودہ لوکیشن',
  searchPlaceholder: 'ریسٹورنٹ یا ڈش تلاش کریں',
  delivery: 'ڈیلیوری',
  pickup: 'پک اپ',
  all: 'سب',
  storesFound: '{n} ریسٹورنٹ',
  storeFound: '1 ریسٹورنٹ',
  openNow: 'کھلا ہے',
  closedNow: 'بند ہے',
  closedForNow: 'فی الحال بند',
  minutes: '{n} منٹ',
  km: '{n} کلومیٹر',
  noStoresTitle: 'یہاں ابھی کوئی ریسٹورنٹ نہیں',
  noStoresText: 'کوئی اور علاقہ آزمائیں، پک اپ چنیں، یا تلاش صاف کریں۔',
  clearSearch: 'تلاش صاف کریں',
  loadMore: 'مزید دکھائیں',
  searchFailed: 'تلاش ابھی دستیاب نہیں۔ تھوڑی دیر بعد کوشش کریں۔',
  retry: 'دوبارہ کوشش کریں',
  pickLocationTitle: 'ریسٹورنٹ دیکھنے کے لیے لوکیشن چنیں',
  pickLocationText: 'ہم وہ ریسٹورنٹ دکھاتے ہیں جو آپ تک ڈیلیور کرتے ہیں، کھلے پہلے۔',
  back: 'تمام ریسٹورنٹ',
  menu: 'مینو',
  soldOut: 'ختم',
  from: 'سے',
  deliveryFee: 'ڈیلیوری {fee}',
  freeDelivery: 'مفت ڈیلیوری',
  freeAbove: '{amount} سے اوپر مفت',
  minOrder: 'کم از کم آرڈر {amount}',
  notDeliverable: 'آپ کی لوکیشن پر ڈیلیوری نہیں۔ پک اپ دستیاب ہو سکتا ہے۔',
  pickupOnly: 'صرف پک اپ',
  setLocationForFee: 'ڈیلیوری فیس دیکھنے کے لیے لوکیشن چنیں',
  hours: 'اوقات',
  storeMissing: 'یہ ریسٹورنٹ دستیاب نہیں',
  storeMissingText: 'شاید بند ہو گیا یا منتقل ہو گیا۔ قریب کوئی اور ریسٹورنٹ تلاش کریں۔',
  emptyMenu: 'مینو جلد آ رہا ہے۔',
  choices: 'انتخاب',
  orderingSoon: 'آرڈر جلد شروع ہوں گے۔ ابھی مینو دیکھیں۔',
  branches: '{n} برانچیں',
  closedAllDay: 'بند',
  today: 'آج',
  opensAt: 'آج {hours}',
};

const DICTIONARIES: Record<string, Dictionary> = { en: EN, ar: AR, ur: UR };

/** Looks up storefront words in the customer's language, with `{name}` placeholders. */
@Injectable({ providedIn: 'root' })
export class Ui {
  private readonly locale = inject(LocaleStore);

  private readonly dictionary = computed(() => DICTIONARIES[this.locale.language()] ?? EN);

  t(key: UiKey, values?: Record<string, string | number>): string {
    let text = this.dictionary()[key] ?? EN[key];
    if (values) {
      for (const [name, value] of Object.entries(values))
        text = text.replace(`{${name}}`, String(value));
    }
    return text;
  }

  /** A number in the customer's language (Arabic-Indic digits in Arabic, for example). */
  number(value: number, fractionDigits = 0): string {
    try {
      return new Intl.NumberFormat(this.locale.language(), {
        maximumFractionDigits: fractionDigits,
      }).format(value);
    } catch {
      return value.toFixed(fractionDigits);
    }
  }

  distance(meters: number): string {
    return this.t('km', { n: this.number(meters / 1000, meters < 10_000 ? 1 : 0) });
  }

  minutes(value: number): string {
    return this.t('minutes', { n: this.number(value) });
  }
}
