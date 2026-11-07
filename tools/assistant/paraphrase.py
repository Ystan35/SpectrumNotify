"""
More ways of asking, written by Gemini, checked here, saved to extra_templates.json for gen_data.py.

Gemini only writes new *templates* (questions with {course}, {date}, ... gaps), never labels or filled sentences:
gen_data.py still fills the gaps and tags every word, so slot tags stay exact. Only the existing templates and
short intent descriptions are sent to Google, never a user's question or test_sentences.txt.

Each new template is kept only if:
  - it uses only gaps its intent already uses (a {time} in a "where is my class" question would teach wrong slots),
  - it is not already a template of any intent, and Gemini did not give it for two intents,
  - it is not one of the hand-written test sentences (that would inflate the honest score),
  - it has at least one real word besides the gaps.
Review extra_templates.json before committing it: this file only checks shape, not meaning.
"""
import json, os, re

from gen_data import T, tokens

HERE = os.path.dirname(os.path.abspath(__file__))
EXTRA = os.path.join(HERE, "extra_templates.json")
GAP = re.compile(r"\{(\w+)\}")

ABOUT = {
    "next_class": "asks when or what their next class / lecture / event is",
    "classes_on": "asks which classes they have on a day, or whether a day is free",
    "class_where": "asks where (room, hall, venue) a class is",
    "route": "asks how to get somewhere on campus, which shuttle bus to take, or where the nearest bus stop is",
    "deadlines": "asks what assignments, quizzes or tasks are due",
    "reminders_list": "asks to see reminders they already set",
    "add_reminder": "asks the app to set a new reminder",
    "help": "greets, thanks, or asks what the assistant can do",
    "emergency": "is in danger, hurt, unsafe, facing an emergency, or thinking of self-harm",
    "schoolwork": "asks the assistant to do their homework, essay, quiz or assignment for them",
    "other": "anything outside the app: general knowledge, chit-chat, jokes, asking for secrets or passwords, "
             "or asking the assistant to act for them (mark attendance, email a lecturer, extend a deadline). "
             "Never danger, injury or self-harm: those are emergency",
}

PROMPT = """You help train a tiny on-device intent classifier for a university student app in Malaysia (Universiti Malaya).
Intent "{intent}": the student {about}.

Existing templates for this intent:
{examples}

Write {n} NEW templates for the same intent, as a student would really type them on a phone:
- mix plain English, Malaysian English / Manglish (lah, can ah, got class or not), Malay, and English-Malay mixes;
- vary length: very short (2-3 words) to a full sentence; casual, lowercase, no emoji;
- only these gaps may appear, written exactly like this, each at most once: {gaps}{none}
- do not repeat or trivially reword the existing templates.
Return a JSON list of strings."""


def gaps_of(intent):
    return sorted({g for t in T[intent] for g in GAP.findall(t)})


def key(text):
    """The words of a sentence or template, without gaps, codes, times or numbers: for spotting repeats."""
    return tuple(w for w in tokens(GAP.sub(" ", text)) if not w.startswith("<"))


def test_keys(path=os.path.join(HERE, "test_sentences.txt")):
    rows = [l.split("\t", 1) for l in open(path, encoding="utf-8") if l.strip() and not l.startswith("#")]
    return {key(r[1]) for r in rows if len(r) == 2}


def load_extra(path=EXTRA):
    return json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}


def clean(proposed, have=None):
    """proposed: {intent: [templates]} -> ({intent: [kept]}, [(intent, template, why) dropped])."""
    have = have or {}
    known = {key(t) for ts in T.values() for t in ts} | {key(t) for ts in have.values() for t in ts}
    tests = test_keys()
    seen = {}  # key -> intents that proposed it
    for intent, ts in proposed.items():
        for t in ts:
            seen.setdefault(key(t), set()).add(intent)
    kept, dropped = {}, []
    for intent, ts in proposed.items():
        allowed = set(gaps_of(intent))
        out = []
        for t in ts:
            t = re.sub(r"\s+", " ", str(t)).strip().lower()
            g = GAP.findall(t)
            k = key(t)
            why = ("not a string" if not t else
                   "unknown gap" if not set(g) <= allowed else
                   "repeated gap" if len(g) != len(set(g)) else
                   "stray brace" if "{" in GAP.sub("", t) or "}" in GAP.sub("", t) else
                   "no words" if not k else
                   "in test_sentences" if k in tests else
                   "already a template" if k in known or k in {key(x) for x in out} else
                   "given for two intents" if len(seen.get(k, ())) > 1 else "")
            if why:
                dropped.append((intent, t, why))
            else:
                out.append(t)
        kept[intent] = out
    return kept, dropped


def ask_gemini(client, model, n=40, intents=None):
    """One request per intent; returns {intent: [templates]} unchecked."""
    from google.genai import types
    out = {}
    for intent in intents or T:
        gaps = gaps_of(intent)
        prompt = PROMPT.format(intent=intent, about=ABOUT[intent], n=n,
                               examples="\n".join("- " + t for t in T[intent]),
                               gaps=", ".join("{" + g + "}" for g in gaps) or "none",
                               none="" if gaps else " (write plain questions with no gaps)")
        r = client.models.generate_content(
            model=model, contents=prompt,
            config=types.GenerateContentConfig(temperature=1.0, response_mime_type="application/json",
                                               response_schema=list[str]))
        try:
            out[intent] = [t for t in json.loads(r.text) if isinstance(t, str)]
        except (ValueError, TypeError):
            print("could not read Gemini's answer for", intent, "- skipped")
            out[intent] = []
        print(f"{intent}: {len(out[intent])} proposed")
    return out


def merge(have, new):
    return {i: have.get(i, []) + new.get(i, []) for i in T if have.get(i) or new.get(i)}


if __name__ == "__main__":
    # offline self-check of the filter
    k, d = clean({"next_class": ["bila {course} class lah", "when is my next class", "next {kind} at {time}", "venue {course}"],
                  "class_where": ["venue {course}", "kelas {course} kat mana"],
                  "emergency": ["yo when's my next class"]})
    assert k["next_class"] == ["bila {course} class lah"], k
    assert k["class_where"] == ["kelas {course} kat mana"], k
    assert {(i, w) for i, _, w in d} == {("next_class", "already a template"), ("next_class", "unknown gap"),
                                          ("next_class", "given for two intents"), ("class_where", "given for two intents"),
                                          ("emergency", "in test_sentences")}, d
    print("filter ok;", sum(map(len, load_extra().values())), "extra templates saved")
