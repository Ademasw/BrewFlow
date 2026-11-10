"use client";

import { useSyncExternalStore } from "react";

// Pilot locales are a DEC-01 decision; Russian and English are candidates. Keys stay in one place.
const ru = {
  appName: "BrewFlow",
  login: "Логин",
  credential: "PIN или пароль",
  signIn: "Войти",
  signOut: "Выйти",
  wrongCredential: "Неверный логин или PIN.",
  locked: "Учётная запись заблокирована. Попросите менеджера сбросить PIN.",
  offline: "Нет связи с сервером. Проверьте сеть.",
  changeTitle: "Смените временный PIN или пароль",
  current: "Текущий",
  next: "Новый",
  save: "Сохранить",
  trainingBanner: "РЕЖИМ ОБУЧЕНИЯ — заказы не являются продажами",
  menu: "Меню",
  noProducts: "В меню нет товаров.",
  soldOut: "нет в наличии",
  lowStock: "мало",
  cart: "Корзина",
  cartEmpty: "Корзина пуста.",
  provisional: "Сумма предварительная: окончательную цену считает сервер.",
  getQuote: "Рассчитать",
  add: "Добавить",
  cancel: "Отмена",
  choose: "выберите",
  quantity: "Кол-во",
  remove: "Убрать",
  total: "Итого",
  confirmTitle: "Подтверждение внешней оплаты",
  quoteRef: "Код расчёта",
  expiresAt: "Действует до",
  method: "Способ оплаты",
  externalReference: "Номер чека / ссылки (необязательно)",
  attest: "Я провёл(а) оплату на ККМ / терминале на эту сумму",
  recordOrder: "Записать заказ",
  recordedNotVerified: "Записано сотрудником, не проверено системой.",
  timeoutTitle: "Ответ не получен",
  timeoutBody: "Не проводите оплату повторно. Проверьте, записан ли заказ.",
  checkStatus: "Проверить заказ",
  expiredTitle: "Расчёт просрочен",
  expiredBody: "Не проводите оплату повторно и не создавайте новый расчёт. Назовите менеджеру код:",
  consumedTitle: "Заказ по этому расчёту уже записан",
  openOrder: "Открыть заказ",
  recoverTitle: "Восстановление по ключу",
  notFoundYet: "Заказ с этим ключом не найден. Можно повторить ту же отправку с тем же ключом.",
  repeatSame: "Повторить ту же отправку",
  noPending: "Нет незавершённой отправки.",
  newOrder: "Новый заказ",
  order: "Заказ",
  status: "Статус",
  corrections: "Исправления",
  kds: "Кухня",
  noTickets: "Нет открытых заказов.",
  lastUpdated: "Обновлено",
  stale: "Нет связи с потоком событий — данные могут быть устаревшими. Переподключение…",
  start: "Начать",
  ready: "Готово",
  clear: "Выдан",
  NEW: "НОВЫЙ",
  PREPARING: "ГОТОВИТСЯ",
  READY: "ГОТОВ",
  CLEARED: "ВЫДАН",
  CANCELLED: "ОТМЕНЁН",
  TRAINING: "Обучение",
  CASH: "Наличные",
  EXTERNAL_CARD: "Карта (терминал)",
  EXTERNAL_QR: "QR",
  roleNoScreen: "Для вашей роли здесь нет экрана.",
} as const;

export type MessageKey = keyof typeof ru;

const en: Record<MessageKey, string> = {
  appName: "BrewFlow",
  login: "Login",
  credential: "PIN or password",
  signIn: "Sign in",
  signOut: "Sign out",
  wrongCredential: "Login or PIN is wrong.",
  locked: "This account is locked. Ask a manager to reset the PIN.",
  offline: "No connection to the server. Check the network.",
  changeTitle: "Change your temporary PIN or password",
  current: "Current",
  next: "New",
  save: "Save",
  trainingBanner: "TRAINING MODE — orders are not sales",
  menu: "Menu",
  noProducts: "No products in the menu.",
  soldOut: "sold out",
  lowStock: "low",
  cart: "Cart",
  cartEmpty: "The cart is empty.",
  provisional: "The total is provisional: the server prices the order.",
  getQuote: "Get quote",
  add: "Add",
  cancel: "Cancel",
  choose: "choose",
  quantity: "Qty",
  remove: "Remove",
  total: "Total",
  confirmTitle: "Confirm the external payment",
  quoteRef: "Quote code",
  expiresAt: "Valid until",
  method: "Payment method",
  externalReference: "Receipt / reference number (optional)",
  attest: "I completed the payment on the KKM / terminal for this amount",
  recordOrder: "Record order",
  recordedNotVerified: "Recorded by staff, not verified by the system.",
  timeoutTitle: "No response",
  timeoutBody: "Do not repeat the payment. Check whether the order was recorded.",
  checkStatus: "Check the order",
  expiredTitle: "The quote expired",
  expiredBody: "Do not repeat the payment or start a new quote. Give the manager this code:",
  consumedTitle: "This quote already has an order",
  openOrder: "Open order",
  recoverTitle: "Recover by key",
  notFoundYet: "No order with this key yet. You may repeat the same submit with the same key.",
  repeatSame: "Repeat the same submit",
  noPending: "No unfinished submit.",
  newOrder: "New order",
  order: "Order",
  status: "Status",
  corrections: "Corrections",
  kds: "Kitchen",
  noTickets: "No open tickets.",
  lastUpdated: "Updated",
  stale: "Event stream lost — the board may be stale. Reconnecting…",
  start: "Start",
  ready: "Ready",
  clear: "Clear",
  NEW: "NEW",
  PREPARING: "PREPARING",
  READY: "READY",
  CLEARED: "CLEARED",
  CANCELLED: "CANCELLED",
  TRAINING: "Training",
  CASH: "Cash",
  EXTERNAL_CARD: "Card (terminal)",
  EXTERNAL_QR: "QR",
  roleNoScreen: "There is no screen here for your role.",
};

type Locale = "ru" | "en";
const KEY = "bf.locale";
const listeners = new Set<() => void>();

function readLocale(): Locale {
  try {
    return localStorage.getItem(KEY) === "en" ? "en" : "ru";
  } catch {
    return "ru";
  }
}

export function setLocale(locale: Locale) {
  try {
    localStorage.setItem(KEY, locale);
  } catch {
    // storage unavailable: keep the default
  }
  listeners.forEach((l) => l());
}

function subscribe(cb: () => void) {
  listeners.add(cb);
  return () => {
    listeners.delete(cb);
  };
}

export function useLocale(): Locale {
  return useSyncExternalStore(subscribe, readLocale, () => "ru");
}

// One function per locale, created once: screens list `t` in effect dependencies, so a new function on every
// render would re-run their data loads in a loop.
const tRu = (key: MessageKey): string => ru[key];
const tEn = (key: MessageKey): string => en[key];
const trRu = (...pair: [ruText: string, enText: string]): string => pair[0];
const trEn = (...pair: [ruText: string, enText: string]): string => pair[1];

export function useT() {
  return useLocale() === "en" ? tEn : tRu;
}

/** Inline pair for admin screens: tr("Русский", "English"). */
export function useTr() {
  return useLocale() === "en" ? trEn : trRu;
}
