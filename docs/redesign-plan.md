# Spectrum Notify redesign: analysis and plan

Branch: `redesign` (from `test` at 8848749). Nothing below is built yet.

## Who it is for and what it does

A university student on a Moodle campus (UM's Spectrum first, USM and others now) checks the phone between classes. The app's job is to answer three questions quickly:

- What's next, and when do I have to leave?
- Did I sign my attendance?
- What's due?

Everything else is setup that you do once.

## What the current UI gets right (keep)

- **One token system:**
  - `Ui` colours, `Type` scale and `Radius` live in Ui.kt.
  - Every screen is procedural, so changing a token restyles the whole app.
- **Accessibility built in:**
  - text scale 1.0/1.2/1.4
  - high contrast
  - big touch targets
  - vector icons instead of emoji
  - TalkBack descriptions
- **Content and behaviour:**
  - the swipeable Home hero
  - the Day/Week/Month timetable
  - the notification filters

## What's wrong (from the five tabs on the emulator, 5 Nov)

1. **No identity.**
   - **Dark mode:** navy `#12151C`, periwinkle `#8FA8FF` and a blue-violet gradient hero. That's a stock Material starter look.
   - **Light mode:** cream `#F5F3EE`, the most common generated-app palette.
   - **Font:** the system font only.
2. **The same header twice.**
   - Every tab opens with the bell logo and "Spectrum Notify", then the page title.
   - That costs about 110dp before any content.
   - "Spectrum" is also the wrong name for USM students.
3. **Pills everywhere.**
   - Every action is a large pill, filled or outlined: three on the hero, three on every timetable card, and three stacked in Profile.
   - **Hero:** the action pills sit inside a fourth pill.
   - **Timetable:** each card is ~400px tall for one class, so a screen shows only two and a half classes.
4. **Colour means too many things.**
   - **On Courses and the Timetable, colour means the course:** WIX1003 orange, WIX1001 purple.
   - **On Notifications, colour means the category:** materials green, announcements orange, updates purple.
   - **Filled buttons take that colour too:** the green "Video 1" and orange "Open".
   - The same orange means a course on one tab and a category on the next.
5. **The "Aa" toolbox button covers content.**
   - It sits over the bottom-right of every tab.
   - In all five screenshots it covers card text (the Due soon cards, a timetable card, a notification, Settings).
6. **Template chrome.**
   - All-caps labels (NEXT CLASS, DUE SOON, MATERIALS).
   - Middle-dot meta strings everywhere.
   - Warnings stack on cards: on Courses, the orange Occ warning and the Verify box together make one course card a full screen tall.
7. **Settings reads like a form.**
   - "Log in with Spectrum" shows even when the site is USM's.
   - Three full-width buttons are stacked under Profile.

## Direction: "the day as a ruler"

What's specific to this app is time: class times, the countdown, leave-by, attendance windows and due-in-4d. So the identity comes from a **time rail**: a thin vertical line with hour ticks, plus a "now" marker in one signal colour. This rail is the one bold element. It runs down the left of Home and the Day timetable, and everything else stays quiet.

### Colour (dark is the default, since you use it)

| Token | Dark | Light | Role |
|---|---|---|---|
| Base | `#121A1C` slate-teal | `#F6F7F5` cool paper | page |
| Surface | `#1A2427` | `#FFFFFF` | cards, sheets |
| Line | `#2C3A3E` | `#D9DEDC` | rail, dividers |
| Ink | `#E8EDE9` | `#1B2426` | text |
| Muted | `#93A3A2` | `#5B6766` | secondary text (4.5:1 on both) |
| Signal | `#F2B544` amber | `#9A6400` text / `#F2B544` fill | now marker, countdown, the one primary action per screen |

- **Course colours** stay as the only other hues, and they always mean the course: on cards, the rail and the widgets.
- **Notification categories** become icons only, with no colour.
- **Red** is kept for errors and "not logged in".
- **High contrast:** the same logic, with brighter ink and a stronger line colour.

### Type

- **Typeface:** one family, Atkinson Hyperlegible Next (Braille Institute, OFL), bundled in `res/font`.
  - It's built for low-vision readability, which fits the text-scale and high-contrast features the app already has.
  - It doesn't look like stock Roboto.
- **Times** use tabular figures so they line up on the rail.
- **Scale:** 12 / 14 / 16 / 20 / 26 / 34. The 34 is used only for the next class's start time on Home.
- **Weights:** Regular, SemiBold and Bold.
- **Labels:** sentence case, no all caps.

### Shape

- **Radii:** 6 for controls and 14 for cards, plus pill for chips only.
- **Buttons:** one filled Signal button per screen at most. Secondary actions are icon buttons (48dp target) or text buttons.

### Layouts

**Home**

```
Thu 5 Nov                         ☀  Aa
Good afternoon · not logged in (red, tappable)
│
├ 10:00  GUL1001 · UMLEADER         ← course colour bar
│        Tomorrow · starts in 16h 27m   (Signal)
│        [ Join class ]  ✓  ↗           (1 filled + 2 icon buttons)
│
├ 12:00  WIX1003 Tutorial
╪ now ─────────────────────── (Signal line)
│
Due soon
  Tutorial 5 Submission     WIX1002   4d
  Tutorial 1 / LAB          WIX1002   4d
```

- The rail runs down the left edge, and today's and tomorrow's classes hang off it.
- Swiping the hero still works: it moves along the rail.
- "Due soon" becomes a compact list instead of a sideways carousel.

**Timetable, Day view**

```
08 ┤
09 ┤ ▌WIX1001  Tutorial · BK2      ✓ ↗ 🔗
10 ┤ ▌
11 ┤
12 ┤ ▌WIX1003  Tutorial · MM4      ✓ ↗
   ╪ now ───
```

- Block heights follow the class length, so a 4h class looks 4h long.
- Each class takes about a third of today's height.

**Other screens**

- **Notifications:** compact rows. Course code in the course colour, title, then one line of detail. There's one text action, and a category icon on the left.
- **Courses:** one card per course. The Occ warning and "Verify" merge into a single banner with one action.
- **Settings:**
  - plain list rows with dividers, not a card per row
  - Profile shows the site host (spectrum.um.edu.my / elearning.usm.my) and a single Log in / Log out button
- **Header:** the page title is the header. The theme toggle and the toolbox ("Aa") move into it, so nothing floats over content.

### Review against the brief (what I changed)

- **Light mode:** I first considered keeping a warm cream light mode. I dropped it because cream is the most common generated look, and cool paper fits the rail better.
- **Hero:** I first considered a big countdown number with a small label for the hero. That's the default treatment, so the time sits on the rail instead.
- **Dark palette:** one amber on a dark base is close to the "dark background with one bright accent" cliché. So the base is a visible slate-teal rather than near-black, and amber appears only where it means "now / act now", never as decoration.

## Build phases (each one: build, emulator before/after screenshots, APK to Telegram)

1. **Tokens.** Palette, font, type scale, radii, and the `button`/`chip`/`segmented` helpers in Ui.kt. This restyles the whole app in one go and is the lowest risk.
2. **Chrome.**
   - `headerRow` drops the logo bar.
   - The toolbox moves into the header.
   - The bottom nav gets restyled.
3. **Home.** The rail hero, compact actions and the Due soon list (MainActivity hero and HeroPager).
4. **Timetable Day view.** Blocks sized by duration on the rail (TimetableActivity and DayTimeline).
5. **Notifications, Courses and Settings rows.**
6. **Widgets.** Next class and Links get the new palette and type.

Swipe gestures, accessibility settings, alarms and data are unchanged. This is a visual and layout change only.

## Open questions for you

- Dark stays the default? (Plan assumes yes.)
- OK to bundle a font (~200 KB added to the APK)?
- Start with phase 1 + 2 as one test APK so you can judge the look before the bigger layout changes?
