# Assistant rules

Approved by the developer on 2026-11-06. The app enforces each rule in code; none relies on the model behaving.
The model only names the kind of question and marks its details (course, day, time, places, reminder text).
Every reply is a fixed sentence filled with the app's own data.

| # | Rule | How the app enforces it |
|---|---|---|
| 1 | **Only your data.** Answers come only from what the app has synced (classes, timetable, shuttle schedule, deadlines, reminders). Missing data is said plainly, never guessed. | Replies are templates filled from Checker, Timetable, ShuttleModel and NotifyLog; no generated text exists. Empty result → "I don't have that. Check <source>." Two extras that are not your data: the weather (Open-Meteo forecast for UM's campus, credited and marked as a forecast that can be wrong) and jokes (a fixed list in the app, never generated). |
| 2 | **No fake notices or made-up data.** Never says a class is cancelled or moved, or a deadline extended, unless Spectrum posted it. | Each reply names its source and sync time ("From Spectrum, synced 5 min ago") with a link to the page. When nothing confirms it: "No notice found. Confirm with your lecturer or the faculty office." |
| 3 | **No promises or actions for you.** Cannot submit work, mark attendance, contact lecturers or change anything on Spectrum. | The only actions change this phone only, each after a Yes: adding a reminder, marking a task done or hiding it, setting a class online or in person. Requests for anything else get rule 5's hand-over. |
| 4 | **Protect secrets.** | There is no hidden prompt. The chat code has no access to the login token or password. Text that looks like a password, token or long key is warned about and not kept. |
| 5 | **Hand over to a person.** | Lecturer or faculty office (class matters); shuttle office (phone and email from bus.json); UM IT help desk (login); the developer (app bugs). |
| 6 | **Ask, don't guess.** | Below about 70% model confidence: no answer, two or three tappable guesses instead. An emergency guess needs 90%; between 70% and 90% it asks, with a one-tap "I need emergency help" button. |
| 7 | **Say how sure it is.** | Bus times: "from the schedule; buses can run late". Walking times are estimates. |
| 8 | **Stays on this phone.** | Questions and answers never leave the device; the chat is not saved after it closes. The only exception is feedback (rule 11), and only when you tap Send. |
| 9 | **Emergencies first.** A message that looks like an emergency or a safety worry shows the numbers, then nothing else. | The app checks emergency words itself (English and Malay, including ular, darah, ragut, rompak, pisau, cemas, lemas) before the model, so a model miss still shows them. A bare "tolong" / "tolong saya" counts; "tolong" inside a request ("tolong ingatkan saya…") goes to the model. Numbers below. |
| 10 | **No schoolwork.** "Do my homework", "write my essay": a polite no and the course page. | Its own question kind (schoolwork). |
| 11 | **Learn from misses, with permission.** A question it cannot answer gets a polite reply and a "Send to the developer" button, the same feedback ticket as Settings → Send feedback. | Off unless tapped. The user sees exactly what is sent (the question, app version, phone model) before Send. Never login details: rule 4's check runs in the app, and the feedback script removes anything that looks like a password or key again. Tickets go to a private repo; see tools/feedback/SETUP.md. |
| 12 | **A kind word.** Stress, tiredness or sadness that is not an emergency gets a short kind reply, the counselling number and gentle next steps; never advice on health or medicine. | Recognised by its words (English and Malay), after the emergency check. |

## Emergency numbers (rule 9)

Shown at UM (site spectrum.um.edu.my). Other universities see 999 only.

Shown first, in this order (each number taps to dial):

| Who | Number | Source |
|---|---|---|
| Police, ambulance, fire | 999 | National |
| UM Security Office / Pej Keselamatan (Polis Bantuan UM) | 03-7967 7777 | UM emergency list (from the developer) |
| UMMC / PPUM (hospital) | 03-7949 2898 / 2190 | UM emergency list |
| UM counselling (Centre for Counselling and Disability Empowerment) | 03-7967 3342 / 3322, kaunseling@um.edu.my | registrar.um.edu.my |

Under "More numbers":

| Who | Number | Source |
|---|---|---|
| Student Clinic / Klinik Pelajar | 03-7967 3212 | UM emergency list |
| HEP & Alumni | 03-7967 3201 / 3507 / 3290 | UM emergency list |
| Police station / Balai Polis | 03-2282 4222 / 2207 | UM emergency list |
| Fire station / Bomba | 03-2282 4444 / 03-2214 2222 | UM emergency list |
| UM Security 24-hour hotline (also listed by UM) | 03-7967 7070 | um.edu.my office directory |

Check these each semester; a wrong emergency number is worse than none.
