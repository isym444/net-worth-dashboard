/**
 * English bank holidays and paycheck-date math. No external API — every
 * holiday (including the Easter-based ones) is computed locally so this
 * works offline and needs no backend support.
 */

function startOfDay(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function addDays(date: Date, days: number): Date {
  const result = new Date(date);
  result.setDate(result.getDate() + days);
  return result;
}

function isWeekend(date: Date): boolean {
  const day = date.getDay();
  return day === 0 || day === 6;
}

function dateKey(date: Date): string {
  return `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

function nthWeekdayOfMonth(year: number, month: number, weekday: number, n: number): Date {
  const first = new Date(year, month, 1);
  const offset = (weekday - first.getDay() + 7) % 7;
  return addDays(first, offset + (n - 1) * 7);
}

function lastWeekdayOfMonth(year: number, month: number, weekday: number): Date {
  const lastDay = new Date(year, month + 1, 0);
  const offset = (lastDay.getDay() - weekday + 7) % 7;
  return addDays(lastDay, -offset);
}

/** Anonymous Gregorian algorithm (Meeus/Jones/Butcher) for Easter Sunday. */
function easterSunday(year: number): Date {
  const a = year % 19;
  const b = Math.floor(year / 100);
  const c = year % 100;
  const d = Math.floor(b / 4);
  const e = b % 4;
  const f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4);
  const k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const monthDay = h + l - 7 * m + 114;
  const month = Math.floor(monthDay / 31) - 1; // 0-indexed
  const day = (monthDay % 31) + 1;
  return new Date(year, month, day);
}

/**
 * Full set of English bank holidays for a year, with weekend substitution
 * applied. Substitution is done generically (shift to the next free weekday,
 * skipping any date already claimed) rather than special-cased per holiday,
 * so it also reproduces the real Christmas/Boxing Day double-shift when the
 * 25th falls on a Saturday or Sunday.
 */
export function getEnglishBankHolidays(year: number): Date[] {
  const easter = easterSunday(year);

  const raw: Date[] = [
    new Date(year, 0, 1), // New Year's Day
    addDays(easter, -2), // Good Friday
    addDays(easter, 1), // Easter Monday
    nthWeekdayOfMonth(year, 4, 1, 1), // Early May bank holiday
    lastWeekdayOfMonth(year, 4, 1), // Spring bank holiday
    lastWeekdayOfMonth(year, 7, 1), // Summer bank holiday
    new Date(year, 11, 25), // Christmas Day
    new Date(year, 11, 26), // Boxing Day
  ];

  const used = new Set<string>();
  const holidays: Date[] = [];

  for (const date of raw) {
    let candidate = date;
    if (isWeekend(candidate)) {
      candidate = addDays(candidate, candidate.getDay() === 6 ? 2 : 1);
    }
    while (used.has(dateKey(candidate))) {
      candidate = addDays(candidate, 1);
      if (isWeekend(candidate)) {
        candidate = addDays(candidate, candidate.getDay() === 6 ? 2 : 1);
      }
    }
    used.add(dateKey(candidate));
    holidays.push(candidate);
  }

  return holidays;
}

function isBankHoliday(date: Date, holidays: Set<string>): boolean {
  return holidays.has(dateKey(date));
}

function previousWorkingDay(date: Date, holidays: Set<string>): Date {
  let candidate = date;
  while (isWeekend(candidate) || isBankHoliday(candidate, holidays)) {
    candidate = addDays(candidate, -1);
  }
  return candidate;
}

/**
 * Paycheck lands on the 23rd of the month, or the nearest working day before
 * it if the 23rd is a weekend or bank holiday. Returns the next occurrence
 * on or after `from` — if `from` is today's (adjusted) payday, that counts
 * as the next paycheck rather than skipping to next month.
 */
export function nextPaycheckDate(from: Date): Date {
  const year = from.getFullYear();
  const month = from.getMonth();
  const today = startOfDay(from);

  const thisMonthHolidays = new Set(getEnglishBankHolidays(year).map(dateKey));
  const thisMonthPayday = previousWorkingDay(new Date(year, month, 23), thisMonthHolidays);
  if (thisMonthPayday.getTime() >= today.getTime()) {
    return thisMonthPayday;
  }

  const nextMonth = new Date(year, month + 1, 23);
  const nextMonthHolidays = new Set(getEnglishBankHolidays(nextMonth.getFullYear()).map(dateKey));
  return previousWorkingDay(nextMonth, nextMonthHolidays);
}

/**
 * Calendar days between `from` and `payday` (e.g. payday tomorrow = 1).
 * Floored at 1 so a same-day payday still divides cleanly rather than by zero.
 */
export function daysUntilPaycheck(from: Date, payday: Date): number {
  const diffDays = Math.round((startOfDay(payday).getTime() - startOfDay(from).getTime()) / 86400000);
  return Math.max(1, diffDays);
}
