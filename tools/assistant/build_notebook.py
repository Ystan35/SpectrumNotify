"""Writes train_assistant.ipynb from the cells below, embedding gen_data.py and test_sentences.txt (one file to upload to Colab)."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
gen = open(os.path.join(HERE, "gen_data.py"), encoding="utf-8").read()
tests = open(os.path.join(HERE, "test_sentences.txt"), encoding="utf-8").read()
para = open(os.path.join(HERE, "paraphrase.py"), encoding="utf-8").read()
extra_path = os.path.join(HERE, "extra_templates.json")
extra = open(extra_path, encoding="utf-8").read() if os.path.exists(extra_path) else "{}\n"

cells = [
    ("md", """# Spectrum Notify assistant: train the on-device model
Trains a small model **from scratch** that reads a question and gives
1. its **intent** (next_class, classes_on, class_where, route, deadlines, reminders_list, add_reminder, help, other), and
2. a **slot tag** per word (COURSE, KIND, DATE, TIME, FROM, TO, TASK).

The app answers with its own data (classes, shuttle planner, deadlines), so the model never makes up a class time.

**Run:** Runtime → Change runtime type → GPU (a CPU also works, in a few minutes), then Runtime → Run all.
At the end `assistant.tflite` and `assistant_meta.json` download: put both in `app/src/main/assets/`.
If you ran the Gemini step, `extra_templates.json` downloads too: read it, then put it in `tools/assistant/`."""),
    ("code", "%%writefile gen_data.py\n" + gen),
    ("code", "%%writefile test_sentences.txt\n" + tests),
    ("code", "%%writefile paraphrase.py\n" + para),
    ("code", "%%writefile extra_templates.json\n" + extra),
    ("md", """## Optional: more ways of asking, from Gemini
Gemini writes new question **templates** per intent (English, Manglish, Malay; short and long) with the same gaps
(`{course}`, `{date}`, ...). `paraphrase.py` drops any that use a wrong gap, repeat a template, appear under two
intents, or match a hand-written test sentence, so the test score stays honest. The training data then takes about
40% of each intent's sentences from these; the app itself is unchanged and still never sends anything off the phone.

Only the existing templates and intent descriptions are sent to Google. Nothing from users.

**To use it:** get a key at aistudio.google.com, add it in Colab's 🔑 Secrets panel as `GOOGLE_API_KEY` (notebook
access on), and set `USE_GEMINI = True`. Each run adds to the templates already in `extra_templates.json`."""),
    ("code", """USE_GEMINI = False
MODEL = "gemini-flash-latest"  # any Gemini model your key can use
PER_INTENT = 40                # templates asked for per intent and run

import json, importlib, paraphrase
importlib.reload(paraphrase)
have = paraphrase.load_extra()
if USE_GEMINI:
    !pip -q install -U google-genai
    from google import genai
    from google.colab import userdata
    client = genai.Client(api_key=userdata.get("GOOGLE_API_KEY"))
    kept, dropped = paraphrase.clean(paraphrase.ask_gemini(client, MODEL, n=PER_INTENT), have)
    for intent, t, why in dropped:
        print(f"drop  {intent:<15} {why:<22} {t}")
    for intent, ts in kept.items():
        print(f"\\n{intent}: {len(ts)} kept"); print("\\n".join("  " + t for t in ts))
    have = paraphrase.merge(have, kept)
    json.dump(have, open("extra_templates.json", "w"), ensure_ascii=False, indent=1)
print(sum(map(len, have.values())), "extra templates will be used:", {i: len(t) for i, t in have.items()})"""),
    ("code", """import json, numpy as np, tensorflow as tf, random
from gen_data import generate, tokens, INTENTS, TAGS
print("TensorFlow", tf.__version__, "| GPU:", tf.config.list_physical_devices("GPU"))

data = generate(per_intent=1500)
MAXLEN = 24
# vocabulary: words seen at least twice; 0 = padding, 1 = unknown word
from collections import Counter
cnt = Counter(w for x in data for w in x["tokens"])
VOCAB = ["<pad>", "<unk>"] + sorted(w for w, c in cnt.items() if c >= 2)
W = {w: i for i, w in enumerate(VOCAB)}

def enc(toks):
    ids = [W.get(w, 1) for w in toks][:MAXLEN]
    return ids + [0] * (MAXLEN - len(ids))

def word_dropout(toks, p=0.05):  # teaches the model to cope with words it never saw (new places, typos)
    return [t if random.random() > p or t.startswith("<") else "<unk-train>" for t in toks]

X = np.array([enc(word_dropout(x["tokens"])) for x in data], dtype=np.int32)
YI = np.array([INTENTS.index(x["intent"]) for x in data], dtype=np.int32)
YS = np.array([[TAGS.index(t) for t in x["tags"]][:MAXLEN] + [0] * (MAXLEN - min(MAXLEN, len(x["tags"]))) for x in data], dtype=np.int32)
print(len(data), "sentences,", len(VOCAB), "words")"""),
    ("code", """L = tf.keras.layers
inp = L.Input((MAXLEN,), dtype="int32", name="ids")
x = L.Embedding(len(VOCAB), 96)(inp)
x = L.SpatialDropout1D(0.1)(x)
# stacked dilated convolutions: each word sees about 15 words around it; all ops are built into TFLite
for d in (1, 2, 4, 1):
    x = L.Conv1D(128, 3, padding="same", dilation_rate=d, activation="relu")(x)
slots = L.Dense(len(TAGS), activation="softmax", name="slots")(x)
intent = L.Dense(len(INTENTS), activation="softmax", name="intent")(L.Dropout(0.3)(L.GlobalMaxPooling1D()(x)))
model = tf.keras.Model(inp, [intent, slots])
model.compile("adam", loss={"intent": "sparse_categorical_crossentropy", "slots": "sparse_categorical_crossentropy"},
              metrics={"intent": "accuracy", "slots": "accuracy"})
model.summary()
model.fit(X, {"intent": YI, "slots": YS}, validation_split=0.1, epochs=12, batch_size=64,
          callbacks=[tf.keras.callbacks.EarlyStopping(patience=2, restore_best_weights=True)])"""),
    ("code", """def predict(sentence, m=model):
    toks = tokens(sentence)
    pi, ps = m.predict(np.array([enc(toks)]), verbose=0)
    tags = [TAGS[i] for i in ps[0].argmax(-1)[:len(toks)]]
    return INTENTS[pi[0].argmax()], float(pi[0].max()), list(zip(toks, tags))

ok = 0; rows = [l.split("\\t") for l in open("test_sentences.txt") if l.strip() and not l.startswith("#")]
for want, s in rows:
    got, conf, tagged = predict(s.strip())
    ok += got == want
    if got != want: print("MISS", want, "->", got, f"{conf:.2f}", "|", s.strip())
print(f"hand-written test: {ok}/{len(rows)} intents right")
for s in ["how do i get from KK12 to FSKTM by 10am", "remind me to submit lab report tomorrow at 9pm", "where is WIX1001"]:
    print(predict(s))"""),
    ("code", """# TFLite export (float16 weights: about half the size, same answers), then check the .tflite gives the same intents
model.export("assistant_saved")
conv = tf.lite.TFLiteConverter.from_saved_model("assistant_saved")
conv.optimizations = [tf.lite.Optimize.DEFAULT]
conv.target_spec.supported_types = [tf.float16]
tfl = conv.convert()
open("assistant.tflite", "wb").write(tfl)

it = tf.lite.Interpreter(model_content=tfl); it.allocate_tensors()
inp_d = it.get_input_details()[0]; outs = it.get_output_details()
def tfl_intent(s):
    it.set_tensor(inp_d["index"], np.array([enc(tokens(s))], dtype=np.int32)); it.invoke()
    o = next(o for o in outs if len(o["shape"]) == 2)  # [1, intents]; slots are [1, MAXLEN, tags]
    return INTENTS[it.get_tensor(o["index"])[0].argmax()]
same = sum(tfl_intent(s.strip()) == predict(s.strip())[0] for _, s in rows)
print(f"tflite agrees with keras on {same}/{len(rows)}; size {len(tfl)//1024} KB")

# everything the app needs to read the model; "checks" lets the app test its tokeniser against Python's
checks = [{"text": s.strip(), "ids": enc(tokens(s.strip())), "intent": tfl_intent(s.strip())} for _, s in rows[:12]]
json.dump({"maxlen": MAXLEN, "vocab": VOCAB, "intents": INTENTS, "tags": TAGS, "checks": checks},
          open("assistant_meta.json", "w"), ensure_ascii=False)
from google.colab import files
files.download("assistant.tflite"); files.download("assistant_meta.json")
if USE_GEMINI: files.download("extra_templates.json")"""),
]

nb = {"nbformat": 4, "nbformat_minor": 5,
      "metadata": {"accelerator": "GPU", "colab": {"provenance": []}, "kernelspec": {"name": "python3", "display_name": "Python 3"}},
      "cells": [{"cell_type": "markdown" if k == "md" else "code", "metadata": {}, "source": src,
                 **({} if k == "md" else {"execution_count": None, "outputs": []})} for k, src in cells]}
json.dump(nb, open(os.path.join(HERE, "train_assistant.ipynb"), "w", encoding="utf-8"), indent=1, ensure_ascii=False)
print("wrote train_assistant.ipynb")
